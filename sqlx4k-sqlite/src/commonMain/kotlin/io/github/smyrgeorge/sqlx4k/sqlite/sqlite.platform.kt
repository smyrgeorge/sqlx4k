package io.github.smyrgeorge.sqlx4k.sqlite

import io.github.smyrgeorge.sqlx4k.QueryExecutor

expect fun sqlite(
    url: String,
    options: QueryExecutor.Pool.Options = QueryExecutor.Pool.Options(),
): ISQLite
