package io.github.smyrgeorge.sqlx4k.sqlite

import io.github.smyrgeorge.sqlx4k.ConnectionPool
import io.github.smyrgeorge.sqlx4k.ValueEncoderRegistry

actual fun sqlite(
    url: String,
    options: ConnectionPool.Options,
    encoders: ValueEncoderRegistry,
): ISQLite = SQLite(url, options, encoders)
