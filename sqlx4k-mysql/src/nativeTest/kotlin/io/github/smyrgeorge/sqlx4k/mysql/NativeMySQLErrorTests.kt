package io.github.smyrgeorge.sqlx4k.mysql

import io.github.smyrgeorge.sqlx4k.ConnectionPool
import io.github.smyrgeorge.sqlx4k.SQLError
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds

class NativeMySQLErrorTests {

    private val options = ConnectionPool.Options.builder()
        .maxConnections(1)
        .acquireTimeout(1.seconds)
        .build()

    private val runner = CommonMySQLErrorTests { url ->
        mySQL(url = url, username = "mysql", password = "mysql", options = options)
    }

    @Test
    fun `TLS failure should be returned as an SQLError`() =
        runner.`TLS failure should be returned as an SQLError`(SQLError.Code.Database)

    // sqlx retries a refused connection until the acquire timeout.
    @Test
    fun `refused connection should be returned as an SQLError`() =
        runner.`refused connection should be returned as an SQLError`(SQLError.Code.PoolTimedOut)
}
