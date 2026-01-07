package io.github.smyrgeorge.sqlx4k.postgres.pgmq

import kotlin.time.Instant

/**
 * Represents the metrics of a message queue at a specific point in time.
 *
 * @property queueName The name of the queue.
 * @property queueLength The total number of messages in the queue.
 * @property newestMsgAgeSec The age of the newest message in the queue, measured in seconds.
 * @property oldestMsgAgeSec The age of the oldest message in the queue, measured in seconds.
 * @property totalMessages The cumulative number of messages processed by the queue.
 * @property scrapeTime The timestamp indicating when these metrics were recorded.
 * @property queueVisibleLength The number of messages in the queue that are currently visible and ready for processing.
 */
data class Metrics(
    val queueName: String,
    val queueLength: Long,
    val newestMsgAgeSec: Int,
    val oldestMsgAgeSec: Int,
    val totalMessages: Long,
    val scrapeTime: Instant,
    val queueVisibleLength: Long
)
