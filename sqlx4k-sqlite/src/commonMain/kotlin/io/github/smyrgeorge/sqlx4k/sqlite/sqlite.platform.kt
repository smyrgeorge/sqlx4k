package io.github.smyrgeorge.sqlx4k.sqlite

import io.github.smyrgeorge.sqlx4k.ConnectionPool
import io.github.smyrgeorge.sqlx4k.ValueEncoderRegistry

expect fun sqlite(
    url: String,
    options: ConnectionPool.Options = ConnectionPool.Options(),
    encoders: ValueEncoderRegistry = ValueEncoderRegistry(),
): ISQLite
