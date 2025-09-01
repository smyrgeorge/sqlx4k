package io.github.smyrgeorge.sqlx4k.mysql

import io.github.smyrgeorge.sqlx4k.QueryExecutor

expect fun mySQL(
    url: String,
    username: String,
    password: String,
    options: QueryExecutor.Pool.Options = QueryExecutor.Pool.Options(),
): IMySQL