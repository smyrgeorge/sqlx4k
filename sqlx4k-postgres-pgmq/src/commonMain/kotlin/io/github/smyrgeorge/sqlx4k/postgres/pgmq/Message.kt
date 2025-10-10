@file:OptIn(ExperimentalTime::class)

package io.github.smyrgeorge.sqlx4k.postgres.pgmq

import kotlin.time.ExperimentalTime
import kotlin.time.Instant

data class Message(
    val msgId: Long,
    val readCt: Int,
    val enqueuedAt: Instant,
    val vt: Instant,
    val message: String,
    val headers: Map<String, String>
)