package io.github.smyrgeorge.sqlx4k.postgres

import io.github.smyrgeorge.sqlx4k.ConnectionPool
import io.github.smyrgeorge.sqlx4k.SQLError
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds

class NativePostgreSQLErrorTests {

    private val options = ConnectionPool.Options.builder()
        .maxConnections(1)
        .acquireTimeout(1.seconds)
        .build()

    private val runner = CommonPostgreSQLErrorTests { url ->
        postgreSQL(url = url, username = "postgres", password = "postgres", options = options)
    }

    @Test
    fun `TLS failure should be returned as an SQLError`() =
        runner.`TLS failure should be returned as an SQLError`(SQLError.Code.Database)

    // sqlx retries a refused connection until the acquire timeout.
    @Test
    fun `refused connection should be returned as an SQLError`() =
        runner.`refused connection should be returned as an SQLError`(SQLError.Code.PoolTimedOut)

    @Test
    fun `duplicate key should expose the driver error codes`() =
        runner.`duplicate key should expose the driver error codes`()

    @Test
    fun `unmapped database error should expose the codes`() =
        runner.`unmapped database error should expose the codes`()
}
