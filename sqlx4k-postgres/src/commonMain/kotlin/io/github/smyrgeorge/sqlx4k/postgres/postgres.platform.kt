package io.github.smyrgeorge.sqlx4k.postgres

import io.github.smyrgeorge.sqlx4k.QueryExecutor

expect fun postgreSQL(
    url: String,
    username: String,
    password: String,
    options: QueryExecutor.Pool.Options = QueryExecutor.Pool.Options(),
): IPostgresSQL