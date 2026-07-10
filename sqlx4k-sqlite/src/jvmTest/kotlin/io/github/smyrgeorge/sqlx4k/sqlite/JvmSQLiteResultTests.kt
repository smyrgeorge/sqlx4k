package io.github.smyrgeorge.sqlx4k.sqlite

import io.github.smyrgeorge.sqlx4k.ConnectionPool
import kotlin.test.Test

class JvmSQLiteResultTests {

    private val options = ConnectionPool.Options.builder()
        .maxConnections(2)
        .build()

    private val db = sqlite(
        url = "test.db",
        options = options
    )

    private val runner = CommonSQLiteResultTests(db)

    @Test
    fun `empty result set still reports column metadata`() = runner.`empty result set still reports column metadata`()

    @Test
    fun `text value 'null' is not coerced to SQL NULL`() = runner.`text value 'null' is not coerced to SQL NULL`()
}
