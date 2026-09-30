/**
 * The Kafka messages exchanged with recommendation-service, as JSON: jobs arrive on
 * recommendation.requests, results go to recommendation.results. recommendation-service has its own
 * copy of these records; keep the two in step.
 */
package psia.g17.events;
