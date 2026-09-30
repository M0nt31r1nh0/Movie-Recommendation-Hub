/**
 * The Kafka messages exchanged with recommendation-worker, as JSON: jobs on recommendation.requests,
 * results on recommendation.results, and "a result is stored" on recommendation.ready (read only by this
 * service). recommendation-worker has its own copy of the job and result records; keep the two in step.
 */
package psia.g17.events;
