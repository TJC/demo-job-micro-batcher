package net.dryft.demo.jobprocessor

import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import net.dryft.demo.batchprocessor.BatchProcessor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.milliseconds

class JobProcessorTest {
    // Define concrete Job and JobResult classes:
    data class TestJob(val i: Int)
    data class TestJobResult(val j: Int)

    // Create a batch processor for the tests, with predictable results - multiplies input by 10
    private val testBatchProcessor: BatchProcessor<TestJob, TestJobResult> =
        fun(jobs: List<TestJob>): List<TestJobResult> {
            return jobs.map { TestJobResult(it.i * 10) }
        }

    /*
     Not implemented, as handling of these cases hasn't really been defined in the specification, but normally
     I'd want to these to test these:
     - Handling of total failures in BatchProcessor (ie. it throws an exception)
     - Handling of erroneous responses from BatchProcessor (ie. returns zero or few results, rather than correct amount)
     */


    @Test
    fun `a single job processes OK`() = runBlocking {
        val jobProcessor = JobProcessor(testBatchProcessor, maxBatchSize = 1)
        assertEquals(
            jobProcessor.submitJob(TestJob(1)),
            TestJobResult(10),
            "Submitting a single job returns right result"
        )
        jobProcessor.shutdown()
    }

    @Test
    fun `submitting a full batch of jobs gets all their results back`() = runBlocking {
        val inputRange = (0..32).toList()
        val jobProcessor = JobProcessor(testBatchProcessor, maxBatchSize = inputRange.size)

        val resultsDeferred = inputRange.map { i ->
            async { jobProcessor.submitJob(TestJob(i)) }
        }
        val results = resultsDeferred.map { it.await().j }
        inputRange.forEach { i ->
            assertEquals(results[i], i * 10)
        }
        jobProcessor.shutdown()
    }

    // Note that because submitJob() normally blocks until the result comes back, we have to launch them all
    // via `async` so that they run in parallel. This can theoretically cause the results to come back out
    // of order in the test though. But as of writing it seems to be OK.
    @Test
    fun `submitting a half a batch of jobs gets all their results back at around the maxSubmission time`() =
        runBlocking {
            val inputRange = (0..32).toList()
            val desiredLatency = 1000.milliseconds
            val allowedTime = 1250.milliseconds
            val jobProcessor =
                JobProcessor(
                    testBatchProcessor,
                    maxBatchSize = inputRange.size * 2,
                    maxSubmissionLatency = desiredLatency
                )

            withTimeout(allowedTime) {
                val resultsDeferred = inputRange.map { i ->
                    async { jobProcessor.submitJob(TestJob(i)) }
                }

                val results = resultsDeferred.map { it.await().j }
                inputRange.forEach { i ->
                    assertEquals(results[i], i * 10)
                }
            }
            jobProcessor.shutdown()
        }

    @Test
    fun `shutdown() command returns when no jobs submitted`() = runBlocking {
        val jobProcessor = JobProcessor(testBatchProcessor)
        withTimeout(500.milliseconds) {
            jobProcessor.shutdown()
            assert(true)
        }
    }

    // This test relies on some assumptions and is a bit odd. Normally submitJob() blocks, so we have to use
    // async to run it in a separate place.
    @Test
    fun `shutdown() command doesn't break up pending jobs`() = runBlocking {
        val jobProcessor = JobProcessor(testBatchProcessor, maxBatchSize = 10)
        // Note that we submit 1 job here, less than the max batch size. It should be held for a while.
        val deferredResult = async { jobProcessor.submitJob(TestJob(1)) }
        // Allow the scheduler to get to the async task we launched, by having a very short delay (could have used yield() too)
        delay(20)
        // Then we call shutdown() and wait for it to return
        jobProcessor.shutdown()
        // Now we check the results we received:
        assertEquals(deferredResult.await().j, 10)
    }

}
