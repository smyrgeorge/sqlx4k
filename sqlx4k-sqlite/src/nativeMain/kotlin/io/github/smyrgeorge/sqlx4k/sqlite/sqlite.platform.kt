package io.github.smyrgeorge.sqlx4k.sqlite

import io.github.smyrgeorge.sqlx4k.ConnectionPool

actual fun sqlite(
    url: String,
    options: ConnectionPool.Options,
): ISQLite = SQLite(url, options)
