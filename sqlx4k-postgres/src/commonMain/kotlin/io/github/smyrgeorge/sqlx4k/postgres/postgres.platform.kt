package io.github.smyrgeorge.sqlx4k.postgres

import io.github.smyrgeorge.sqlx4k.ConnectionPool
import io.github.smyrgeorge.sqlx4k.ValueEncoderRegistry

expect fun postgreSQL(
    url: String,
    username: String,
    password: String,
    options: ConnectionPool.Options = ConnectionPool.Options(),
    encoders: ValueEncoderRegistry = ValueEncoderRegistry(),
): IPostgresSQL