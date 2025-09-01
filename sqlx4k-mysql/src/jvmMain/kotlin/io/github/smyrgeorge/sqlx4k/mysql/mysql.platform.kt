package io.github.smyrgeorge.sqlx4k.mysql

import io.github.smyrgeorge.sqlx4k.QueryExecutor

actual fun mySQL(
    url: String,
    username: String,
    password: String,
    options: QueryExecutor.Pool.Options,
): IMySQL = MySQL(url, username, password, options)