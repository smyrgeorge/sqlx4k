package io.github.smyrgeorge.sqlx4k.mysql

import io.github.smyrgeorge.sqlx4k.ConnectionPool
import io.github.smyrgeorge.sqlx4k.ValueEncoderRegistry

expect fun mySQL(
    url: String,
    username: String,
    password: String,
    options: ConnectionPool.Options = ConnectionPool.Options(),
    encoders: ValueEncoderRegistry = ValueEncoderRegistry(),
): IMySQL