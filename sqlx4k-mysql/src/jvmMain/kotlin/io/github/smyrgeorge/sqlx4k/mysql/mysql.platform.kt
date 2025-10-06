package io.github.smyrgeorge.sqlx4k.mysql

import io.github.smyrgeorge.sqlx4k.ConnectionPool

actual fun mySQL(
    url: String,
    username: String,
    password: String,
    options: ConnectionPool.Options,
): IMySQL = MySQL(url, username, password, options)