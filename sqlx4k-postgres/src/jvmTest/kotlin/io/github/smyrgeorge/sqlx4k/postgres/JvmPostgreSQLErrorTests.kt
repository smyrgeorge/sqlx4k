package io.github.smyrgeorge.sqlx4k.postgres

import io.github.smyrgeorge.sqlx4k.ConnectionPool
import io.github.smyrgeorge.sqlx4k.SQLError
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds

// R2DBC reports every failure while acquiring a connection as a pool error.
class JvmPostgreSQLErrorTests {

    private val options = ConnectionPool.Options.builder()
        .maxConnections(1)
        .acquireTimeout(1.seconds)
        .build()

    private val runner = CommonPostgreSQLErrorTests { url ->
        postgreSQL(url = url, username = "postgres", password = "postgres", options = options)
    }

    @Test
    fun `TLS failure should be returned as an SQLError`() =
        runner.`TLS failure should be returned as an SQLError`(SQLError.Code.Pool)

    @Test
    fun `refused connection should be returned as an SQLError`() =
        runner.`refused connection should be returned as an SQLError`(SQLError.Code.Pool)
}
