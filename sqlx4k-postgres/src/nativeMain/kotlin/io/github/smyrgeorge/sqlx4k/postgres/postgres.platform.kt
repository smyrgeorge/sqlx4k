package io.github.smyrgeorge.sqlx4k.postgres

import io.github.smyrgeorge.sqlx4k.QueryExecutor

actual fun postgreSQL(
    url: String,
    username: String,
    password: String,
    options: QueryExecutor.Pool.Options,
): IPostgresSQL = PostgreSQL(url, username, password, options)