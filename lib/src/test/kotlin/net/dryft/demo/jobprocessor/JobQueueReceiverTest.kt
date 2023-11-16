package net.dryft.demo.jobprocessor

import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import net.dryft.demo.batchprocessor.BatchProcessor
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * To be honest, these tests could have lived in the JobProcessorTest just as easily as in here, since this stuff
 * was still testable from the top-level.
 */
class JobQueueReceiverTest {
    // Define concrete Job and JobResult classes:
    data class TestJob(val i: Int)
    data class TestJobResult(val j: Int)

    // Create a batch processor for the tests, which logs number of calls
    class CountingBatchProcessor {
        var calls = 0
        val processor: BatchProcessor<TestJob, TestJobResult> =
            fun(jobs: List<TestJob>): List<TestJobResult> {
                calls += 1
                return jobs.map { TestJobResult(it.i * 10) }
            }
    }

    @Test
    fun `a single job causes a single BatchProcessor call`() = runBlocking {
        val batchProcessor = CountingBatchProcessor()
        val jobProcessor = JobProcessor(batchProcessor.processor, maxBatchSize = 1)
        assertEquals(jobProcessor.submitJob(TestJob(1)).j, 10)
        assertEquals(batchProcessor.calls, 1)
        jobProcessor.shutdown()
    }

    @Test
    fun `Less jobs than maxbatchsize causes a single BatchProcessor call`() = runBlocking {
        val batchProcessor = CountingBatchProcessor()
        val jobProcessor = JobProcessor(batchProcessor.processor, maxBatchSize = 10)
        val deferredResults = (1..10).map { i ->
            async { jobProcessor.submitJob(TestJob(i)) }
        }
        deferredResults.forEach { it.await() } // wait for the results to all come back..

        // Check there was only one BatchProcessor call made:
        assertEquals(batchProcessor.calls, 1)
        jobProcessor.shutdown()
    }

    @Test
    fun `Three and half times maxbatchsize causes four BatchProcessor calls`() = runBlocking {
        val batchProcessor = CountingBatchProcessor()
        val jobProcessor = JobProcessor(batchProcessor.processor, maxBatchSize = 10)
        val deferredResults = (1..35).map { i ->
            async { jobProcessor.submitJob(TestJob(i)) }
        }
        deferredResults.forEach { it.await() } // wait for the results to all come back..

        // Check there was only one BatchProcessor call made:
        assertEquals(batchProcessor.calls, 4)
        jobProcessor.shutdown()
    }
}