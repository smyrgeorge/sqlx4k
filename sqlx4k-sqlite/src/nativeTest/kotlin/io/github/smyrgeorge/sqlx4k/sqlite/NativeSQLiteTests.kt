package io.github.smyrgeorge.sqlx4k.sqlite

import io.github.smyrgeorge.sqlx4k.QueryExecutor
import kotlin.test.Test

class NativeSQLiteTests {

    private val options = QueryExecutor.Pool.Options.builder()
        .maxConnections(10)
        .build()

    private val db = sqlite(
        url = "sqlite://test.db",
        options = options
    )

    private val runner = CommonSQLiteTests(db)

    @Test
    fun `Test basic type mappings`() = runner.`Test basic type mappings`()
}