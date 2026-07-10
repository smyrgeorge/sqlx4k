package io.github.smyrgeorge.sqlx4k.postgres

import io.github.smyrgeorge.sqlx4k.ConnectionPool
import kotlin.test.Test

class JvmPostgreSQLJsonTests {

    private val options = ConnectionPool.Options.builder()
        .maxConnections(2)
        .build()

    private val db = postgreSQL(
        url = "postgresql://localhost:15432/test",
        username = "postgres",
        password = "postgres",
        options = options
    )

    private val runner = CommonPostgreSQLJsonTests(db)

    @Test
    fun `jsonb bound param renders canonical object`() {
        runner.`jsonb bound param renders canonical object`()
    }

    @Test
    fun `jsonb bound param normalizes whitespace and key order`() {
        runner.`jsonb bound param normalizes whitespace and key order`()
    }

    @Test
    fun `jsonb bound param renders canonical array`() {
        runner.`jsonb bound param renders canonical array`()
    }

    @Test
    fun `jsonb bound param scalar string`() {
        runner.`jsonb bound param scalar string`()
    }

    @Test
    fun `jsonb bound param scalar number preserves scale`() {
        runner.`jsonb bound param scalar number preserves scale`()
    }

    @Test
    fun `jsonb bound param boolean`() {
        runner.`jsonb bound param boolean`()
    }

    @Test
    fun `jsonb bound param json null is not sql null`() {
        runner.`jsonb bound param json null is not sql null`()
    }

    @Test
    fun `jsonb bound param duplicate keys keep last`() {
        runner.`jsonb bound param duplicate keys keep last`()
    }

    @Test
    fun `jsonb bound param nested structure round-trips`() {
        runner.`jsonb bound param nested structure round-trips`()
    }

    @Test
    fun `jsonb bound param preserves nested null values`() {
        runner.`jsonb bound param preserves nested null values`()
    }

    @Test
    fun `jsonb bound param preserves escaped quotes`() {
        runner.`jsonb bound param preserves escaped quotes`()
    }

    @Test
    fun `jsonb bound param preserves unicode`() {
        runner.`jsonb bound param preserves unicode`()
    }

    @Test
    fun `jsonb named bound param round-trips`() {
        runner.`jsonb named bound param round-trips`()
    }

    @Test
    fun `json bound param preserves exact text and key order`() {
        runner.`json bound param preserves exact text and key order`()
    }

    @Test
    fun `json bound param preserves whitespace`() {
        runner.`json bound param preserves whitespace`()
    }

    @Test
    fun `jsonb column round-trips through a table`() {
        runner.`jsonb column round-trips through a table`()
    }

    @Test
    fun `json column round-trips through a table preserving text`() {
        runner.`json column round-trips through a table preserving text`()
    }

    @Test
    fun `null jsonb column reads as null`() {
        runner.`null jsonb column reads as null`()
    }

    @Test
    fun `null json column reads as null`() {
        runner.`null json column reads as null`()
    }

    @Test
    fun `jsonb_build_object renders canonical`() {
        runner.`jsonb_build_object renders canonical`()
    }

    @Test
    fun `jsonb delete-key operator round-trips`() {
        runner.`jsonb delete-key operator round-trips`()
    }

    @Test
    fun `jsonb path extraction returns scalar text`() {
        runner.`jsonb path extraction returns scalar text`()
    }

    @Test
    fun `jsonb literal via simple query reads canonical`() {
        runner.`jsonb literal via simple query reads canonical`()
    }

    @Test
    fun `json literal via simple query preserves text`() {
        runner.`json literal via simple query preserves text`()
    }

}
