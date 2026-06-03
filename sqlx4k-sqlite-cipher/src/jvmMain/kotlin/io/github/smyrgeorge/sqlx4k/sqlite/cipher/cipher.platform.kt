package io.github.smyrgeorge.sqlx4k.sqlite.cipher

import io.github.smyrgeorge.sqlx4k.ConnectionPool
import io.github.smyrgeorge.sqlx4k.ValueEncoderRegistry

actual fun sqliteCipher(
    url: String,
    password: String,
    options: ConnectionPool.Options,
    encoders: ValueEncoderRegistry,
): ISQLiteCipher = SQLiteCipher(url.removePrefix("jdbc:"), password, options, encoders)
