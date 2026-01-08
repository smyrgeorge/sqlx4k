package io.github.smyrgeorge.sqlx4k.mysql

import io.github.smyrgeorge.sqlx4k.ConnectionPool
import kotlin.test.Test

class NativeMySQLMigratorTests {

    private val options = ConnectionPool.Options.builder()
        .maxConnections(2)
        .build()

    private val db = mySQL(
        url = "mysql://localhost:13306/test",
        username = "mysql",
        password = "mysql",
        options = options
    )

    private val runner = CommonMySQLMigratorTests(db)

    @Test
    fun `migrate happy path and idempotent`() = runner.`migrate happy path and idempotent`()

    @Test
    fun `duplicate version files should fail`() = runner.`duplicate version files should fail`()

    @Test
    fun `non-monotonic versions should fail`() = runner.`non-monotonic versions should fail`()

    @Test
    fun `empty migration file should fail`() = runner.`empty migration file should fail`()

    @Test
    fun `checksum mismatch should fail on re-run`() = runner.`checksum mismatch should fail on re-run`()

    @Test
    fun `file level transaction rollback on failure`() = runner.`file level transaction rollback on failure`()

//    @Test
    fun `migrate with explicit schema and createSchema`() = runner.`migrate with explicit schema and createSchema`()
}
