package io.github.smyrgeorge.sqlx4k.postgres

import io.github.smyrgeorge.sqlx4k.Driver
import kotlin.test.Test

class JvmPostgreSQLMigratorTests {

    private val options = Driver.Pool.Options.builder()
        .maxConnections(5)
        .build()

    private val db = postgreSQL(
        url = "postgresql://localhost:15432/test",
        username = "postgres",
        password = "postgres",
        options = options
    )

    private val runner = CommonPostgreSQLMigratorTests(db)

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
}
