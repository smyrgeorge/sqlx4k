package io.github.smyrgeorge.sqlx4k.sqlite

import io.github.smyrgeorge.sqlx4k.ConnectionPool

expect fun sqlite(
    url: String,
    options: ConnectionPool.Options = ConnectionPool.Options(),
): ISQLite
