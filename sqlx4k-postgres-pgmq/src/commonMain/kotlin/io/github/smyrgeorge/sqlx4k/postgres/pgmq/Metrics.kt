@file:OptIn(ExperimentalTime::class)

package io.github.smyrgeorge.sqlx4k.postgres.pgmq

import kotlin.time.ExperimentalTime
import kotlin.time.Instant

data class Metrics(
    val queueName: String,
    val queueLength: Long,
    val newestMsgAgeSec: Int,
    val oldestMsgAgeSec: Int,
    val totalMessages: Long,
    val scrapeTime: Instant,
    val queueVisibleLength: Long
)