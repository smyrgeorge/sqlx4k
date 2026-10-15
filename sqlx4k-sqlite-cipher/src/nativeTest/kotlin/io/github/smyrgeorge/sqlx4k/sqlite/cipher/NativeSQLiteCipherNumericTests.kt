package io.github.smyrgeorge.sqlx4k.sqlite.cipher

import io.github.smyrgeorge.sqlx4k.ConnectionPool
import kotlin.test.Test

class NativeSQLiteCipherNumericTests {

    private val options = ConnectionPool.Options.builder().maxConnections(10).build()

    private val db = sqliteCipher(
        url = "sqlite://test-cipher.db",
        password = "test-passphrase",
        options = options
    )

    private val runner = CommonSQLiteCipherNumericTests(db)

    @Test
    fun `numeric values should decode through prepared statements`() = runner.`numeric values should decode through prepared statements`()

    @Test
    fun `numeric literals render identically`() = runner.`numeric literals render identically`()

    @Test
    fun `numeric extremes render identically`() = runner.`numeric extremes render identically`()

    @Test
    fun `numeric null decodes as null`() = runner.`numeric null decodes as null`()

    @Test
    fun `numeric aggregates render identically`() = runner.`numeric aggregates render identically`()

    @Test
    fun `numeric converts to kotlin numbers`() = runner.`numeric converts to kotlin numbers`()

    @Test
    fun `numeric column round-trips bound values`() = runner.`numeric column round-trips bound values`()
}
