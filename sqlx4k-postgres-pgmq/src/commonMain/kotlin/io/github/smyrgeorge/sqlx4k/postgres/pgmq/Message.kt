@file:OptIn(ExperimentalTime::class)

package io.github.smyrgeorge.sqlx4k.postgres.pgmq

import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * Represents a message in a queue system.
 *
 * @property msgId Unique identifier for the message.
 * @property readCt Indicates how many times the message has been read.
 * @property enqueuedAt The timestamp when the message was added to the queue.
 * @property vt The visibility timeout of the message, indicating when it becomes visible for processing.
 * @property message The content of the message, expected to be in valid JSON format.
 * @property headers A map containing metadata key-value pairs associated with the message,
 *                   which will be converted to JSON during processing.
 */
data class Message(
    val msgId: Long,
    val readCt: Int,
    val enqueuedAt: Instant,
    val vt: Instant,
    val message: String, // Should be a valid JSON.
    val headers: Map<String, String> // Will be converted to JSON.
)