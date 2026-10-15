package io.github.smyrgeorge.sqlx4k.mysql

import io.github.smyrgeorge.sqlx4k.ConnectionPool
import kotlin.test.Test

class JvmMySQLNumericTests {

    private val options = ConnectionPool.Options.builder()
        .maxConnections(2)
        .build()

    private val db = mySQL(
        url = "mysql://localhost:13306/test",
        username = "mysql",
        password = "mysql",
        options = options
    )

    private val runner = CommonMySQLNumericTests(db)

    @Test
    fun `decimal values should decode through prepared statements`() = runner.`decimal values should decode through prepared statements`()

    @Test
    fun `decimal literals preserve scale in both protocols`() = runner.`decimal literals preserve scale in both protocols`()

    @Test
    fun `decimal keeps full precision in both protocols`() = runner.`decimal keeps full precision in both protocols`()

    @Test
    fun `decimal null decodes as null in both protocols`() = runner.`decimal null decodes as null in both protocols`()

    @Test
    fun `decimal aggregates decode in both protocols`() = runner.`decimal aggregates decode in both protocols`()

    @Test
    fun `decimal converts to kotlin numbers`() = runner.`decimal converts to kotlin numbers`()

    @Test
    fun `decimal column round-trips bound values`() = runner.`decimal column round-trips bound values`()
}
