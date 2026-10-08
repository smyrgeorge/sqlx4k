package io.github.smyrgeorge.sqlx4k.sqlite.cipher

import io.github.smyrgeorge.sqlx4k.ConnectionPool
import kotlin.test.Test

class NativeSQLiteCipherErrorTests {

    private val options = ConnectionPool.Options.builder()
        .maxConnections(1)
        .build()

    private fun open(query: String): ISQLiteCipher = sqliteCipher(
        url = "sqlite://test-cipher.db?$query",
        password = "test-passphrase",
        options = options
    )

    private val runner = CommonSQLiteCipherErrorTests(open("mode=rwc"), ::open)

    @Test
    fun `invalid URL should be returned as a Pool error`() = runner.`invalid URL should be returned as a Pool error`()

    @Test
    fun `protocol error should be returned as a Database error`() =
        runner.`protocol error should be returned as a Database error`()

    @Test
    fun `duplicate key should expose the driver error codes`() =
        runner.`duplicate key should expose the driver error codes`()

    @Test
    fun `unmapped database error should expose the codes`() =
        runner.`unmapped database error should expose the codes`()
}
