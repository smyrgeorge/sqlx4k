package io.github.smyrgeorge.sqlx4k.postgres

import io.github.smyrgeorge.sqlx4k.ConnectionPool

actual fun postgreSQL(
    url: String,
    username: String,
    password: String,
    options: ConnectionPool.Options,
): IPostgresSQL = PostgreSQL(url, username, password, options)