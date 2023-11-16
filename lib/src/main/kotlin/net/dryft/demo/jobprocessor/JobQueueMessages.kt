package net.dryft.demo.jobprocessor

import kotlinx.coroutines.channels.Channel

/**
 * All messages sent on the job queue channel must be of this type
 */
sealed interface JobQueueMessages<Job, JobResult>

/**
 * The job request method is the main thing we expect -- it contains the abstract Job,
 * plus a channel that's used to return the eventual JobResult to the caller.
 */
class JobRequest<Job, JobResult>(val job: Job) : JobQueueMessages<Job, JobResult> {
    val returnChannel = Channel<JobResult>(1)
}

/**
 * This message is used to trigger a dispatch of batches at a regular cadence, so that the maximum time
 * spent waiting never exceeds a set amount.
 */
class SubmissionLatencyEvent<Job, JobResult> : JobQueueMessages<Job, JobResult>
