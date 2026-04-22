package io.github.smyrgeorge.sqlx4k.mysql

import io.github.smyrgeorge.sqlx4k.ConnectionPool
import kotlin.test.Test

class JvmMySQLByteArrayTests {

    private val options = ConnectionPool.Options.builder()
        .maxConnections(2)
        .build()

    private val db = mySQL(
        url = "mysql://localhost:13306/test",
        username = "mysql",
        password = "mysql",
        options = options
    )

    private val runner = CommonMySQLByteArrayTests(db)

    @Test
    fun `binary positional param`() = runner.`binary positional param`()

    @Test
    fun `binary named param`() = runner.`binary named param`()

    @Test
    fun `binary null positional binding`() = runner.`binary null positional binding`()

    @Test
    fun `binary null named binding`() = runner.`binary null named binding`()

    @Test
    fun `binary empty array binding`() = runner.`binary empty array binding`()

    @Test
    fun `binary full byte range binding`() = runner.`binary full byte range binding`()

    @Test
    fun `binary with embedded nulls`() = runner.`binary with embedded nulls`()

    @Test
    fun `binary insert and select roundtrip`() = runner.`binary insert and select roundtrip`()

    @Test
    fun `binary large payload binding`() = runner.`binary large payload binding`()

    @Test
    fun `binary utf8 roundtrip via param`() = runner.`binary utf8 roundtrip via param`()

    @Test
    fun `reused named binary param`() = runner.`reused named binary param`()

    @Test
    fun `binary param with type cast preservation`() = runner.`binary param with type cast preservation`()

    @Test
    fun `binary equality filter with prepared statement`() = runner.`binary equality filter with prepared statement`()

    @Test
    fun `batch insert binary and filter by id`() = runner.`batch insert binary and filter by id`()

    @Test
    fun `binary single byte boundary values`() = runner.`binary single byte boundary values`()

    @Test
    fun `update binary via named param`() = runner.`update binary via named param`()

    @Test
    fun `binary param with length`() = runner.`binary param with length`()

    @Test
    fun `binary mixed with other params`() = runner.`binary mixed with other params`()

    @Test
    fun `bindNull with ByteArray type`() = runner.`bindNull with ByteArray type`()

    @Test
    fun `binary repeated byte pattern binding`() = runner.`binary repeated byte pattern binding`()
}
