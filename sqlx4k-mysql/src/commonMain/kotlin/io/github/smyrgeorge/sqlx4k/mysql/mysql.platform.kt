package io.github.smyrgeorge.sqlx4k.mysql

import io.github.smyrgeorge.sqlx4k.Driver

expect fun mySQL(
    url: String,
    username: String,
    password: String,
    options: Driver.Pool.Options = Driver.Pool.Options(),
): IMySQL