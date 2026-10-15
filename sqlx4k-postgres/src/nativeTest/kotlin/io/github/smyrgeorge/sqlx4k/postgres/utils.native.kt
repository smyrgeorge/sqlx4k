package io.github.smyrgeorge.sqlx4k.postgres

import io.github.smyrgeorge.sqlx4k.SQLError

internal actual val tlsFailureCode: SQLError.Code = SQLError.Code.Database

// sqlx retries a refused connection until the acquire timeout.
internal actual val refusedConnectionCode: SQLError.Code = SQLError.Code.PoolTimedOut
