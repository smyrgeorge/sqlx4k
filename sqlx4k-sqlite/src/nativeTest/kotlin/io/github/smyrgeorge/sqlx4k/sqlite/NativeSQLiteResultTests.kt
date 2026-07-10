package io.github.smyrgeorge.sqlx4k.sqlite

import io.github.smyrgeorge.sqlx4k.ConnectionPool
import kotlin.test.Test

class NativeSQLiteResultTests {

    private val options = ConnectionPool.Options.builder()
        .maxConnections(10)
        .build()

    private val db = sqlite(
        url = "sqlite://test.db",
        options = options
    )

    private val runner = CommonSQLiteResultTests(db)

    // Note: `empty result set still reports column metadata` is intentionally NOT delegated here.
    // The empty-result metadata fix applies to the JVM (JDBC) and Android (Cursor) drivers only.
    // The Kotlin/Native driver is backed by the Rust `sqlx` core, which does not surface a column
    // schema for zero-row results, so that behavior is out of scope for this test on native.

    @Test
    fun `text value 'null' is not coerced to SQL NULL`() = runner.`text value 'null' is not coerced to SQL NULL`()
}
