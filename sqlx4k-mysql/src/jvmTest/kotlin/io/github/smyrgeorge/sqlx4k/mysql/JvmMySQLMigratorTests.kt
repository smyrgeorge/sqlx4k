package io.github.smyrgeorge.sqlx4k.mysql

import io.github.smyrgeorge.sqlx4k.Driver
import kotlin.test.Test

class JvmMySQLMigratorTests {

    private val options = Driver.Pool.Options.builder()
        .maxConnections(5)
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

//    @Test
//    fun `non-monotonic versions should fail`() = runner.`non-monotonic versions should fail`()

    @Test
    fun `empty migration file should fail`() = runner.`empty migration file should fail`()

    @Test
    fun `checksum mismatch should fail on re-run`() = runner.`checksum mismatch should fail on re-run`()
}
