package io.github.smyrgeorge.sqlx4k.postgres

import io.github.smyrgeorge.sqlx4k.Driver

actual fun postgreSQL(
    url: String,
    username: String,
    password: String,
    options: Driver.Pool.Options,
): IPostgresSQL = PostgreSQL(url, username, password, options)