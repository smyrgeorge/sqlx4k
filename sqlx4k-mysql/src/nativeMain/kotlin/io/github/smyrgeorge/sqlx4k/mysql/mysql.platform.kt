package io.github.smyrgeorge.sqlx4k.mysql

import io.github.smyrgeorge.sqlx4k.Driver

actual fun mySQL(
    url: String,
    username: String,
    password: String,
    options: Driver.Pool.Options,
): IMySQL = MySQL(url, username, password, options)