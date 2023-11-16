# Demo coding challenge

This code was written as part of a coding challenge, and I thought I'd pop it up on my Github
for future reference if applying at other companies.

In the spirit of the coding challenge, this solution doesn't use external dependencies in the solution, and was written quickly.

There is room for some improvement in the design. Currently there can only be a single call to BatchProcessor in flight. In an optimised world, you might want to allow multiple overlapping calls to be in flight.

The current design was built with coroutine-level concurrency in mind, not full threading.
It should be fairly resilient to multi-threaded callers, due to the channel pipeline design, but
there are probably some rare race conditions that could occur during setup and shutdown
that I haven't explored.

In the current design, there is always a single timer running to manage the maximum latency.
If this API only sees low throughput, I'd want to change this, so the timer is not running when a batch is empty.
In a higher throughput situation, I think the current design is simpler and thus better.

## API documentation

This is a job processor which does behind-the-scenes micro-batching.
A caller is free to make many requests to the JobSubmit method, which will suspend
until a batch is formed and dispatched, at which point all the callers will return.

This has been designed as a generic solution, and as such the data structures of a
`Job` and `JobResult` must be defined by the implementer, as well as passing in a
function `BatchProcessor(List<Job>): List<JobResult>`.

Example usage:

```kotlin
import kotlinx.coroutines.runBlocking
import net.dryft.demo.batchprocessor.BatchProcessor
import net.dryft.demo.jobprocessor.JobProcessor

data class MyJob(val name: String)
data class MyResult(val greeting: String)

private val myBatchProcessor: BatchProcessor<MyJob, MyResult> =
    fun(jobs: List<MyJob>): List<MyResult> {
        return jobs.map { j -> MyResult("Hello ${j.name}!") }
    }

runBlocking {
    val jobProcessor = JobProcessor(myBatchProcessor)
    val result = jobProcessor.submitJob(MyJob("Toby"))
    println(result.greeting)
    jobProcessor.shutdown()
}
```

### JobProcessor.submitJob(Job): JobResult

Submits a job, and returns the job result.

### JobProcessor.shutdown()

Called to shutdown the batching system. It will dispatch any remaining items in the
pending batch, wait for the results to be returned, then finally return to the caller.

### Batching controls

The frequency and size of batches can be controlled via two parameters.

#### maxBatchSize

Maximum number of items in a single batch. A batch will be instantly dispatched once
this number of items is reached.

Must be a positive, non-zero, integer. Setting this to `1` effectively disables batching, but is allowed.

Default: 20

#### maxSubmissionLatency

This aims to control the maximum time a batch will be in the pending/collection state.
It must be a positive, non-infinite duration.

Default: 250 milliseconds

