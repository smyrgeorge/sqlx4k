package io.github.smyrgeorge.sqlx4k.postgres

import io.github.smyrgeorge.sqlx4k.Driver
import kotlin.test.Test

class JvmPostgreSQLTests {

    private val options = Driver.Pool.Options.builder()
        .maxConnections(10)
        .build()

    private val db = postgreSQL(
        url = "postgresql://localhost:15432/test",
        username = "postgres",
        password = "postgres",
        options = options
    )

    private val runner = CommonPostgreSQLTests(db)

    @Test
    fun `Test basic type mappings`() = runner.`Test basic type mappings`()
}