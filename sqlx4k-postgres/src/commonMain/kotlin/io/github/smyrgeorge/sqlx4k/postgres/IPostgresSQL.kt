package io.github.smyrgeorge.sqlx4k.postgres

import io.github.smyrgeorge.sqlx4k.Driver

interface IPostgresSQL : Driver, Driver.Pool, Driver.Transactional, Driver.Migrate {
    suspend fun listen(channel: String, f: (Notification) -> Unit)
    suspend fun listen(channels: List<String>, f: (Notification) -> Unit)
    suspend fun notify(channel: String, value: String)
}