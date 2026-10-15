package io.github.smyrgeorge.sqlx4k.sqlite

import io.github.smyrgeorge.sqlx4k.ConnectionPool
import kotlin.test.Test

class JvmSQLiteTests {

    private val options = ConnectionPool.Options.builder()
        .maxConnections(10)
        .build()

    private val db = sqlite(
        url = "test.db",
        options = options
    )

    private val runner = CommonSQLiteTests(db)

    @Test
    fun `Test basic type mappings`() = runner.`Test basic type mappings`()

    @Test
    fun `execute and fetchAll should work`() = runner.`execute and fetchAll should work`()

    @Test
    fun `execute and fetchAll with prepared statements should work`() = runner.`execute and fetchAll with prepared statements should work`()

    @Test
    fun `text with embedded NUL should decode in full`() = runner.`text with embedded NUL should decode in full`()

    @Test
    fun `text with embedded NUL should round-trip through bound parameters`() =
        runner.`text with embedded NUL should round-trip through bound parameters`()

    @Test
    fun `invalid UTF-8 text should decode with replacement characters`() =
        runner.`invalid UTF-8 text should decode with replacement characters`()
}
