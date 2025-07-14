package io.github.smyrgeorge.sqlx4k.postgres

import io.github.smyrgeorge.sqlx4k.ResultSet

/**
 * Represents a notification received from a PostgreSQL listen/notify channel.
 *
 * A `Notification` object contains details about a notification event that has
 * been listened to via the PostgreSQL listen/notify mechanism. It holds the
 * associated channel and the actual value of the notification payload.
 *
 * @property channel The name of the PostgreSQL channel from which the notification was received.
 * @property value The payload of the notification represented as a column of a result set.
 */
data class Notification(
    val channel: String,
    val value: ResultSet.Row.Column,
)