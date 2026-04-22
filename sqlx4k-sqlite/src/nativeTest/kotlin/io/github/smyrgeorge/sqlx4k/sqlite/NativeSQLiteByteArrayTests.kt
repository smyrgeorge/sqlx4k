package io.github.smyrgeorge.sqlx4k.sqlite

import io.github.smyrgeorge.sqlx4k.ConnectionPool
import kotlin.test.Test

class NativeSQLiteByteArrayTests {

    private val options = ConnectionPool.Options.builder()
        .maxConnections(10)
        .build()

    private val db = sqlite(
        url = "sqlite://test.db",
        options = options
    )

    private val runner = CommonSQLiteByteArrayTests(db)

    @Test
    fun `blob positional param`() = runner.`blob positional param`()

    @Test
    fun `blob named param`() = runner.`blob named param`()

    @Test
    fun `blob null positional binding`() = runner.`blob null positional binding`()

    @Test
    fun `blob null named binding`() = runner.`blob null named binding`()

    @Test
    fun `blob empty array binding`() = runner.`blob empty array binding`()

    @Test
    fun `blob full byte range binding`() = runner.`blob full byte range binding`()

    @Test
    fun `blob with embedded nulls`() = runner.`blob with embedded nulls`()

    @Test
    fun `blob insert and select roundtrip`() = runner.`blob insert and select roundtrip`()

    @Test
    fun `blob large payload binding`() = runner.`blob large payload binding`()

    @Test
    fun `blob utf8 roundtrip via param`() = runner.`blob utf8 roundtrip via param`()

    @Test
    fun `reused named blob param`() = runner.`reused named blob param`()

    @Test
    fun `blob equality filter with prepared statement`() = runner.`blob equality filter with prepared statement`()

    @Test
    fun `batch insert blob and filter by id`() = runner.`batch insert blob and filter by id`()

    @Test
    fun `blob single byte boundary values`() = runner.`blob single byte boundary values`()

    @Test
    fun `update blob via named param`() = runner.`update blob via named param`()

    @Test
    fun `blob param with length`() = runner.`blob param with length`()

    @Test
    fun `blob mixed with other params`() = runner.`blob mixed with other params`()

    @Test
    fun `bindNull with ByteArray type`() = runner.`bindNull with ByteArray type`()

    @Test
    fun `blob repeated byte pattern binding`() = runner.`blob repeated byte pattern binding`()
}
