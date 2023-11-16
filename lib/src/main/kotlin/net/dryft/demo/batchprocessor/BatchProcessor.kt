package net.dryft.demo.batchprocessor

// This package provides interface definitions for the BatchProcessor for the sake of this tech challenge.
// In the real world, these would probably be defined in the BatchProcessor dependency instead.

typealias BatchProcessor<Job, JobResult> = (List<Job>) -> List<JobResult>

// Normally I'd wrap the whole result, like this, to discriminate total failure and enable handling of it..
// typealias BatchProcessor<Job, JobResult> = (List<Job>) -> Result<List<JobResult>>
