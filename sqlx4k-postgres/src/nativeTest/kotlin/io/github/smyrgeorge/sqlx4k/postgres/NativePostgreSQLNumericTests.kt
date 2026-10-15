package io.github.smyrgeorge.sqlx4k.postgres

import io.github.smyrgeorge.sqlx4k.ConnectionPool
import kotlin.test.Test

class NativePostgreSQLNumericTests {

    private val options = ConnectionPool.Options.builder()
        .maxConnections(2)
        .build()

    private val db = postgreSQL(
        url = "postgresql://localhost:15432/test",
        username = "postgres",
        password = "postgres",
        options = options
    )

    private val runner = CommonPostgreSQLNumericTests(db)

    @Test
    fun `numeric values should decode through prepared statements`() = runner.`numeric values should decode through prepared statements`()

    @Test
    fun `numeric literals preserve scale in both protocols`() = runner.`numeric literals preserve scale in both protocols`()

    @Test
    fun `numeric special values decode in both protocols`() = runner.`numeric special values decode in both protocols`()

    @Test
    fun `numeric keeps arbitrary precision in both protocols`() = runner.`numeric keeps arbitrary precision in both protocols`()

    @Test
    fun `numeric null decodes as null in both protocols`() = runner.`numeric null decodes as null in both protocols`()

    @Test
    fun `numeric aggregates decode in both protocols`() = runner.`numeric aggregates decode in both protocols`()

    @Test
    fun `numeric converts to kotlin numbers`() = runner.`numeric converts to kotlin numbers`()

    @Test
    fun `numeric array decodes in both protocols`() = runner.`numeric array decodes in both protocols`()

    @Test
    fun `numeric column round-trips bound values`() = runner.`numeric column round-trips bound values`()
}
