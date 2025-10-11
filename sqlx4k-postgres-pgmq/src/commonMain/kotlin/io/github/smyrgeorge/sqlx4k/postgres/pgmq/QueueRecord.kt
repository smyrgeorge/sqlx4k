@file:OptIn(ExperimentalTime::class)

package io.github.smyrgeorge.sqlx4k.postgres.pgmq

import kotlin.time.ExperimentalTime
import kotlin.time.Instant

data class QueueRecord(
    val name: String,
    val partitioned: Boolean,
    val unlogged: Boolean,
    val createdAt: Instant,
)