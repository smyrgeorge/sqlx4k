package io.github.smyrgeorge.sqlx4k.mysql

import io.github.smyrgeorge.sqlx4k.ConnectionPool
import io.github.smyrgeorge.sqlx4k.SQLError
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds

// R2DBC reports every failure while acquiring a connection as a pool error.
// There is no TLS test: the JVM driver supports TLS and so does the test server, so requiring it succeeds.
class JvmMySQLErrorTests {

    private val options = ConnectionPool.Options.builder()
        .maxConnections(1)
        .acquireTimeout(1.seconds)
        .build()

    private val runner = CommonMySQLErrorTests { url ->
        mySQL(url = url, username = "mysql", password = "mysql", options = options)
    }

    @Test
    fun `refused connection should be returned as an SQLError`() =
        runner.`refused connection should be returned as an SQLError`(SQLError.Code.Pool)

    @Test
    fun `duplicate key should expose the driver error codes`() =
        runner.`duplicate key should expose the driver error codes`()

    @Test
    fun `foreign key violation should expose the driver error codes`() =
        runner.`foreign key violation should expose the driver error codes`()

    @Test
    fun `unmapped database error should expose the codes`() =
        runner.`unmapped database error should expose the codes`()
}
