package net.dryft.demo.jobprocessor

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import net.dryft.demo.batchprocessor.BatchProcessor
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds


/**
 * Batching job processor.
 *
 * This is the public-facing API, which essentially just receives Jobs and pops them into a queue. The
 * JobQueueReceiver does most of the actual batching work.
 *
 * @param Job The type of submitted jobs. (Usually inferred)
 * @param JobResult The type of returned job results. (Usually inferred)
 * @property batchProcessor The function that processes a list of Jobs
 * @property maxBatchSize The maximum number of jobs to include in a single batch. (Must be >= 1)
 * @property maxSubmissionLatency The longest to wait while accumulating a batch, before submitting it to the batchProcessor
 */
class JobProcessor<Job, JobResult>(
    val batchProcessor: BatchProcessor<Job, JobResult>,
    val maxBatchSize: Int = 20,
    val maxSubmissionLatency: Duration = 250.milliseconds
) {
    private var shutdownPending: Boolean = false
    private val jobsInProgress = AtomicInteger(0)
    private val incomingJobQueue = Channel<JobQueueMessages<Job, JobResult>>(capacity = 2 * maxBatchSize)
    private var queueReceiver: kotlinx.coroutines.Job? = null

    init {
        if (maxBatchSize < 1) {
            throw Exception("maxBatchSize must be >= 1")
        }
        if (maxSubmissionLatency.isInfinite() or maxSubmissionLatency.isNegative()) {
            // Zero durations are allowed I guess, but would effectively disable the batching system...
            throw Exception("maxSubmissionLatency must be a zero or positive non-infinite duration")
        }

        // Launch the queue receiver coroutine in its own context, as it will block on BatchProcessor calls.
        queueReceiver = CoroutineScope(Dispatchers.IO).launch {
            JobQueueReceiver(batchProcessor, incomingJobQueue, maxBatchSize, maxSubmissionLatency).run()
        }
    }


    /**
     * Submit a single job to the processor, and receive the single result back.
     * Note that this method will essentially pause while waiting for a batch to fill up.
     */
    suspend fun submitJob(job: Job): JobResult {
        if (shutdownPending) {
            throw Exception("Shutdown pending, no new jobs are being accepted")
        }

        jobsInProgress.incrementAndGet()
        val jobRequest = JobRequest<Job, JobResult>(job)
        incomingJobQueue.send(jobRequest)
        val result = jobRequest.returnChannel.receive()
        jobsInProgress.decrementAndGet()
        return result
    }

    /**
     * Command the system to safely shutdown. Will only return once all jobs have been completed.
     */
    suspend fun shutdown() {
        shutdownPending = true
        incomingJobQueue.close()
        while (jobsInProgress.get() > 0) {
            delay(maxSubmissionLatency)
        }
        queueReceiver?.join()
    }
}
