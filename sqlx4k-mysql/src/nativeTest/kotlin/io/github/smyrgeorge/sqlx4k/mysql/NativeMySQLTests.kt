package io.github.smyrgeorge.sqlx4k.mysql

import io.github.smyrgeorge.sqlx4k.Driver
import kotlin.test.Test

class NativeMySQLTests {

    val options = Driver.Pool.Options.builder()
        .maxConnections(10)
        .build()

    val db = MySQL(
        url = "mysql://localhost:13306/test",
        username = "mysql",
        password = "mysql",
        options = options
    )

    private val runner = CommonMySQLTests(db)

    @Test
    fun `Test basic type mappings`() = runner.`Test basic type mappings`()
}