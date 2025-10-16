package io.github.smyrgeorge.sqlx4k.postgres.pgmq

/**
 * Represents a topic binding that maps a routing pattern to a queue.
 *
 * Topic bindings use AMQP-style wildcard patterns to match routing keys:
 * - `*` (star) matches exactly ONE segment between dots
 * - `#` (hash) matches ZERO or MORE segments
 *
 * @property pattern The AMQP-style wildcard pattern for routing key matching.
 * @property queueName The name of the queue that receives messages when the pattern matches.
 * @property compiledRegex The pre-compiled regex pattern used internally for matching.
 */
data class TopicBinding(
    val pattern: String,
    val queueName: String,
    val compiledRegex: String,
)