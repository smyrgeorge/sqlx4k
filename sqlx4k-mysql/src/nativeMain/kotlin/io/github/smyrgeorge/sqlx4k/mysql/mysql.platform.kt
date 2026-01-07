package io.github.smyrgeorge.sqlx4k.mysql

import io.github.smyrgeorge.sqlx4k.ConnectionPool
import io.github.smyrgeorge.sqlx4k.ValueEncoderRegistry

actual fun mySQL(
    url: String,
    username: String,
    password: String,
    options: ConnectionPool.Options,
    encoders: ValueEncoderRegistry,
): IMySQL = MySQL(url, username, password, options, encoders)