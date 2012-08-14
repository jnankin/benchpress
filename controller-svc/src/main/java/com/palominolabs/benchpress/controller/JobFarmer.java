package com.palominolabs.benchpress.controller;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.netflix.curator.x.discovery.ServiceInstance;
import com.palominolabs.benchpress.job.JobStatus;
import com.palominolabs.benchpress.job.PartitionStatus;
import com.palominolabs.benchpress.job.json.Job;
import com.palominolabs.benchpress.job.json.Partition;
import com.palominolabs.benchpress.task.reporting.TaskPartitionFinishedReport;
import com.palominolabs.benchpress.task.reporting.TaskProgressReport;
import com.palominolabs.benchpress.worker.WorkerControl;
import com.palominolabs.benchpress.worker.WorkerControlFactory;
import com.palominolabs.benchpress.worker.WorkerFinder;
import com.palominolabs.benchpress.worker.WorkerMetadata;
import org.joda.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.ws.rs.core.Response;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Tends to Jobs
 */
@Singleton
public final class JobFarmer {
    private static final Logger logger = LoggerFactory.getLogger(JobFarmer.class);

    private final WorkerFinder workerFinder;
    private final WorkerControlFactory workerControlFactory;

    private final UUID controllerId = UUID.randomUUID();
    private final Map<UUID, JobStatus> jobs = new HashMap<UUID, JobStatus>();
    // todo make final
    private String httpListenHost;
    private int httpListenPort;
    private static final String REPORT_PATH = "/report";
    private static final String PROGRESS_PATH = REPORT_PATH + "/progress";
    private static final String FINISHED_PATH = REPORT_PATH + "/finished";

    @Inject
    JobFarmer(WorkerFinder workerFinder, WorkerControlFactory workerControlFactory) {
        this.workerFinder = workerFinder;
        this.workerControlFactory = workerControlFactory;
    }

    /**
     * Farm out a job to the available workers.
     *
     * @param job The job to cultivate
     * @return 202 on success with Job in the body, 412 on failure
     */
    public Response submitJob(Job job) {
        JobStatus jobStatus = new JobStatus(job);

        // Create a set of workers we can lock
        Set<WorkerMetadata> lockedWorkers = new HashSet<WorkerMetadata>();
        for (ServiceInstance<WorkerMetadata> instance : workerFinder.getWorkers()) {
            WorkerMetadata workerMetadata = instance.getPayload();
            WorkerControl workerControl = workerControlFactory.getWorkerControl(workerMetadata);
            if (!workerControl.acquireLock(controllerId)) {
                logger.warn("Unable to lock worker <" + workerControl.getMetadata().getWorkerId() + ">");
                continue;
            }

            lockedWorkers.add(workerMetadata);
        }

        if (lockedWorkers.isEmpty()) {
            logger.warn("No unlocked workers");
            return Response.status(Response.Status.PRECONDITION_FAILED).entity("No unlocked workers found").build();
        }

        List<Partition> partitions = job.partition(lockedWorkers.size(), getProgressUrl(job.getJobId()), getFinishedUrl(job.getJobId()));
        TandemIterator titerator = new TandemIterator(partitions.iterator(), lockedWorkers.iterator());

        // Submit the partition to the worker
        while (titerator.hasNext()) {
            TandemIterator.Pair pair = titerator.next();
            workerControlFactory.getWorkerControl(pair.workerMetadata).submitPartition(job.getJobId(), pair.partition);
            jobStatus.addPartitionStatus(new PartitionStatus(pair.partition, pair.workerMetadata));
        }

        // Save the JobStatus for our accounting
        jobs.put(job.getJobId(), jobStatus);

        logger.info("Cultivating job");
        return Response.status(Response.Status.ACCEPTED).entity(job).build();
    }

    /**
     * Get info about a job.
     *
     * @param jobId The job to retrieve
     * @return A Job object corresponding to the given jobId
     */
    public JobStatus getJob(UUID jobId) {
        return jobs.get(jobId);
    }

    /**
     * Get the jobs that this farmer is cultivating.
     *
     * @return A Set of job IDs
     */
    public Set<UUID> getJobIds() {
        return jobs.keySet();
    }

    /**
     * Aggregate a worker's results about a partition.
     *
     * @param jobId The jobId that this taskProgressReport is for
     * @param taskProgressReport The results data
     * @return ACCEPTED if we handled the taskProgressReport, NOT_FOUND if this farmer doesn't know the given jobId
     */
    public Response handleProgressReport(UUID jobId, TaskProgressReport taskProgressReport) {
        if (!jobs.containsKey(jobId)) {
            logger.warn("Couldn't find job");
            return Response.status(Response.Status.NOT_FOUND).build();
        }

        JobStatus jobStatus = jobs.get(jobId);
        PartitionStatus partitionStatus = jobStatus.getPartitionStatus(taskProgressReport.getPartitionId());
        partitionStatus.addProgressReport(taskProgressReport);

        logger.info("Progress for partition <" + taskProgressReport.getPartitionId() + ">: " + taskProgressReport.toString());

        return Response.status(Response.Status.ACCEPTED).build();
    }

    /**
     * Handle a completed partition
     *
     * @param jobId The jobId that this taskProgressReport is for
     * @param taskPartitionFinishedReport The results data
     * @return ACCEPTED if we handled the taskProgressReport, NOT_FOUND if this farmer doesn't know the given jobId
     */
    public Response handlePartitionFinishedReport(UUID jobId, TaskPartitionFinishedReport taskPartitionFinishedReport) {
        if (!jobs.containsKey(jobId)) {
            logger.warn("Couldn't find job <" + jobId + ">");
            return Response.status(Response.Status.NOT_FOUND).build();
        }

        JobStatus jobStatus = jobs.get(jobId);
        PartitionStatus partitionStatus = jobStatus.getPartitionStatus(taskPartitionFinishedReport.getPartitionId());
        logger.info("Partition <" + partitionStatus.getPartition().getPartitionId() + "> finished");

        WorkerControl workerControl = workerControlFactory.getWorkerControl(partitionStatus.getWorkerMetadata());
        workerControl.releaseLock(controllerId);

        if (!(partitionStatus.computeQuantaCompleted() == partitionStatus.getPartition().getTask().getNumQuanta())) {
            logger.warn("Worker <" + workerControl.getMetadata().getWorkerId() + "> gave TaskPartitionFinishedReport but quanta don't add up");
        } else {
            Duration totalDuration = new Duration(0);
            for (Integer partitionId : jobStatus.getPartitionStatuses().keySet()) {
                totalDuration = totalDuration.plus(jobStatus.getPartitionStatus(partitionId).computeTotalDuration());
            }
            jobStatus.setFinalDuration(totalDuration);
        }

        return Response.status(Response.Status.ACCEPTED).build();
    }

    public void setListenAddress(String httpListenHost) {
        this.httpListenHost = httpListenHost;
    }

    public void setListenPort(int httpListenPort) {
        this.httpListenPort = httpListenPort;
    }

    private String getProgressUrl(UUID jobId) {
        return "http://" + httpListenHost + ":" + httpListenPort + "/job/" + jobId + PROGRESS_PATH;
    }

    private String getFinishedUrl(UUID jobId) {
        return "http://" + httpListenHost + ":" + httpListenPort + "/job/" + jobId + FINISHED_PATH;
    }

    final class TandemIterator implements Iterator<TandemIterator.Pair> {
        private final Iterator<Partition> piterator;
        private final Iterator<WorkerMetadata> witerator;

        public TandemIterator(Iterator<Partition> piterator, Iterator<WorkerMetadata> witerator) {
            this.piterator = piterator;
            this.witerator = witerator;
        }

        @Override
        public boolean hasNext() {
            return piterator.hasNext() && witerator.hasNext();
        }

        @Override
        public Pair next() {
            return new Pair(piterator.next(), witerator.next());
        }

        /**
         * Does nothing.
         */
        @Override
        public void remove() {
            // No.
        }

        public class Pair {
            final Partition partition;
            final WorkerMetadata workerMetadata;

            public Pair(Partition partition, WorkerMetadata workerMetadata) {
                this.partition = partition;
                this.workerMetadata = workerMetadata;
            }
        }
    }
}