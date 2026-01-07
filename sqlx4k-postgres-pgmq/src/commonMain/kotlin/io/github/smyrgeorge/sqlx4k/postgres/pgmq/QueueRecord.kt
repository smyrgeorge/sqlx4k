package io.github.smyrgeorge.sqlx4k.postgres.pgmq

import kotlin.time.Instant

/**
 * Represents a record of a queue in the system, which holds metadata about the queue's
 * configuration and creation time.
 *
 * @property name The name of the queue.
 * @property partitioned Indicates whether the queue is partitioned.
 * @property unlogged Indicates whether the queue is unlogged.
 * @property createdAt The timestamp when the queue was created.
 */
data class QueueRecord(
    val name: String,
    val partitioned: Boolean,
    val unlogged: Boolean,
    val createdAt: Instant,
)
