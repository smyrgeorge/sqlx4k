package io.github.smyrgeorge.sqlx4k.postgres

import io.github.smyrgeorge.sqlx4k.ConnectionPool
import kotlin.test.Test

class NativePostgreSQLByteArrayTests {

    private val options = ConnectionPool.Options.builder()
        .maxConnections(2)
        .build()

    private val db = postgreSQL(
        url = "postgresql://localhost:15432/test",
        username = "postgres",
        password = "postgres",
        options = options
    )

    private val runner = CommonPostgreSQLByteArrayTests(db)

    @Test
    fun `bytea positional param`() = runner.`bytea positional param`()

    @Test
    fun `bytea named param`() = runner.`bytea named param`()

    @Test
    fun `bytea null positional binding`() = runner.`bytea null positional binding`()

    @Test
    fun `bytea null named binding`() = runner.`bytea null named binding`()

    @Test
    fun `bytea empty array binding`() = runner.`bytea empty array binding`()

    @Test
    fun `bytea full byte range binding`() = runner.`bytea full byte range binding`()

    @Test
    fun `bytea with embedded nulls`() = runner.`bytea with embedded nulls`()

    @Test
    fun `bytea insert and select roundtrip`() = runner.`bytea insert and select roundtrip`()

    @Test
    fun `bytea large payload binding`() = runner.`bytea large payload binding`()

    @Test
    fun `bytea utf8 roundtrip via param`() = runner.`bytea utf8 roundtrip via param`()

    @Test
    fun `reused named bytea param`() = runner.`reused named bytea param`()

    @Test
    fun `bytea in extended statement`() = runner.`bytea in extended statement`()

    @Test
    fun `bytea param with type cast preservation`() = runner.`bytea param with type cast preservation`()

    @Test
    fun `bytea equality filter with prepared statement`() = runner.`bytea equality filter with prepared statement`()

    @Test
    fun `batch insert bytea and filter by id`() = runner.`batch insert bytea and filter by id`()

    @Test
    fun `bytea single byte boundary values`() = runner.`bytea single byte boundary values`()

    @Test
    fun `update bytea via named param`() = runner.`update bytea via named param`()

    @Test
    fun `bytea param with octet_length`() = runner.`bytea param with octet_length`()

    @Test
    fun `bytea mixed with other params`() = runner.`bytea mixed with other params`()

    @Test
    fun `bindNull with ByteArray type`() = runner.`bindNull with ByteArray type`()

    @Test
    fun `bytea repeated byte pattern binding`() = runner.`bytea repeated byte pattern binding`()
}
