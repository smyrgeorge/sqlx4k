package io.github.smyrgeorge.sqlx4k.sqlite

import io.github.smyrgeorge.sqlx4k.QueryExecutor

actual fun sqlite(
    url: String,
    options: QueryExecutor.Pool.Options,
): ISQLite = SQLite(url, options)
