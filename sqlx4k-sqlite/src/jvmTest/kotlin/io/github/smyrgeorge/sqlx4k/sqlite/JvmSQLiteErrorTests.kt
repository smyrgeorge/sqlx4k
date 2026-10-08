package io.github.smyrgeorge.sqlx4k.sqlite

import io.github.smyrgeorge.sqlx4k.ConnectionPool
import kotlin.test.Test

class JvmSQLiteErrorTests {

    private val options = ConnectionPool.Options.builder()
        .maxConnections(1)
        .build()

    private val db = sqlite(
        url = "test.db",
        options = options
    )

    private val runner = CommonSQLiteErrorTests(db)

    @Test
    fun `duplicate key should expose the driver error codes`() =
        runner.`duplicate key should expose the driver error codes`()

    @Test
    fun `unmapped database error should expose the codes`() =
        runner.`unmapped database error should expose the codes`()
}
