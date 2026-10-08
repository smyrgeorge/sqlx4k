package io.github.smyrgeorge.sqlx4k.postgres

import io.github.smyrgeorge.sqlx4k.ConnectionPool
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds

class JvmPostgreSQLStreamTests {

    // A single connection with a short acquire timeout: a stream that leaks its connection fails the next query.
    private val options = ConnectionPool.Options.builder()
        .maxConnections(1)
        .acquireTimeout(3.seconds)
        .build()

    private val db = postgreSQL(
        url = "postgresql://localhost:15432/test",
        username = "postgres",
        password = "postgres",
        options = options
    )

    private val runner = CommonPostgreSQLStreamTests(db)

    @Test
    fun `rows are streamed in order in chunks`() = runner.`rows are streamed in order in chunks`()

    @Test
    fun `rows are streamed with a prepared statement`() = runner.`rows are streamed with a prepared statement`()

    @Test
    fun `rows are mapped with a row mapper`() = runner.`rows are mapped with a row mapper`()

    @Test
    fun `an empty result completes the flow`() = runner.`an empty result completes the flow`()

    @Test
    fun `cancelling the collector releases the connection`() =
        runner.`cancelling the collector releases the connection`()

    @Test
    fun `a failing query fails the collection with an SQLError`() =
        runner.`a failing query fails the collection with an SQLError`()

    @Test
    fun `rows are streamed on a connection which stays usable`() =
        runner.`rows are streamed on a connection which stays usable`()

    @Test
    fun `rows are streamed in a transaction`() = runner.`rows are streamed in a transaction`()
}
