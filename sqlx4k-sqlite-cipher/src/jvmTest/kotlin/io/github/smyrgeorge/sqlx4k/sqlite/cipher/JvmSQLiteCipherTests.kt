package io.github.smyrgeorge.sqlx4k.sqlite.cipher

import io.github.smyrgeorge.sqlx4k.ConnectionPool
import kotlin.test.Test

class JvmSQLiteCipherTests {

    private val options = ConnectionPool.Options.builder()
        .maxConnections(10)
        .build()

    private val db = sqliteCipher(
        url = "test.db",
        password = "test-passphrase",
        options = options
    )

    private val runner = CommonSQLiteCipherTests(db)

    @Test
    fun `Test basic type mappings`() = runner.`Test basic type mappings`()

    @Test
    fun `execute and fetchAll should work`() = runner.`execute and fetchAll should work`()

    @Test
    fun `execute and fetchAll with prepared statements should work`() =
        runner.`execute and fetchAll with prepared statements should work`()
}