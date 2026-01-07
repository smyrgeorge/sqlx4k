package io.github.smyrgeorge.sqlx4k.postgres

import io.github.smyrgeorge.sqlx4k.ConnectionPool
import io.github.smyrgeorge.sqlx4k.ValueEncoderRegistry

actual fun postgreSQL(
    url: String,
    username: String,
    password: String,
    options: ConnectionPool.Options,
    encoders: ValueEncoderRegistry,
): IPostgresSQL = PostgreSQL(url, username, password, options, encoders)