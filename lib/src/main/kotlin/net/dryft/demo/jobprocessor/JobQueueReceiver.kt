package net.dryft.demo.jobprocessor

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import net.dryft.demo.batchprocessor.BatchProcessor
import kotlin.time.Duration

/**
 * This is the other half of the system, which does most of the actual work.
 * It listens to the work queue, and when enough jobs have arrived, it dispatches them to the BatchProcessor.
 * It also runs a timer, which should guarantee that jobs never wait more than the maxSubmissionLatency before
 * being submitted. (Although if the BatchProcessor is slow, then there isn't much we can do, as this code blocks
 * on the call to BatchProcessor. A future enhancement would be to enable parallel calls to it.)
 */
class JobQueueReceiver<Job, JobResult>(
    val batchProcessor: BatchProcessor<Job, JobResult>,
    val incomingChannel: Channel<JobQueueMessages<Job, JobResult>>,
    val maxBatchSize: Int,
    val maxSubmissionLatency: Duration
) {
    private var currentBatch = mutableListOf<JobRequest<Job, JobResult>>()
    private var maxSubmissionLatencyTimerJob: kotlinx.coroutines.Job? = null

    /**
     * Starts the JobQueueReceiver. It'll stop itself automatically once the incoming channel is closed.
     */
    suspend fun run() = coroutineScope {
        maxSubmissionLatencyTimerJob = launch {
            maxSubmissionLatencyTimer()
        }

        for (message in incomingChannel) {
            when (message) {
                is SubmissionLatencyEvent -> submitBatch()
                is JobRequest -> {
                    currentBatch += message
                    if (currentBatch.size >= maxBatchSize) {
                        submitBatch()
                    }
                }
            }
        }

        // The channel has been closed at the other end, which means we're in shutdown.
        maxSubmissionLatencyTimerJob?.cancelAndJoin()
        // Submit whatever is left in the queue before we close up shop
        submitBatch()
    }


    /**
     * Submits the batch to the BatchProcessor, and then starts a new empty batch.
     * Sends the results back to the requesters via their provided channels.
     * Note that it will quickly no-op and return if there's nothing waiting in the current batch.
     * This happens because of the max-latency timer events.
     */
    private suspend fun submitBatch() {
        if (currentBatch.size == 0) return

        val thisBatch = currentBatch
        currentBatch = mutableListOf() // Create a fresh batch

        val results: List<JobResult> = batchProcessor(thisBatch.map { it.job }.toList())
        // Assumes number of results matches number of requests, and that things are in same order.
        // In the real world, I would want more checking and error handling around cases where that accidentally
        // was not true.
        val returnChannels = thisBatch.map { it.returnChannel }

        results.zip(returnChannels) { result, returnChannel -> returnChannel.send(result) }
        returnChannels.forEach { it.close() }
    }

    /**
     * A timer that ensures a batch is submitted within the specified maximum time duration.
     * It just regularly dispatches a "latency event" into the job queue, and these are used to trigger
     * a batch submission.
     */
    private suspend fun maxSubmissionLatencyTimer() {
        // Could also just while(true) and wait for a ChannelClosed exception to occur?
        while (!incomingChannel.isClosedForReceive and !incomingChannel.isClosedForSend) {
            delay(maxSubmissionLatency)
            incomingChannel.send(SubmissionLatencyEvent())
        }
    }

}