package io.github.smyrgeorge.sqlx4k.mysql

import io.github.smyrgeorge.sqlx4k.SQLError

// R2DBC reports every failure while acquiring a connection as a pool error.
internal actual val tlsFailureCode: SQLError.Code? = null
internal actual val refusedConnectionCode: SQLError.Code = SQLError.Code.Pool
