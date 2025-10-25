package io.github.smyrgeorge.sqlx4k.sqlite

import io.github.smyrgeorge.sqlx4k.ConnectionPool
import kotlin.test.Test

class JvmSQLiteMigratorTests {

    private val options = ConnectionPool.Options.builder()
        .maxConnections(2)
        .build()

    private val db = SQLite(
        url = "test.db",
        options = options
    )

    private val runner = CommonSQLiteMigratorTests(db)

    @Test
    fun `migrate happy path and idempotent`() {
        runner.`migrate happy path and idempotent`()
    }

    @Test
    fun `duplicate version files should fail`() {
        runner.`duplicate version files should fail`()
    }

    @Test
    fun `non-monotonic versions should fail`() {
        runner.`non-monotonic versions should fail`()
    }

    @Test
    fun `empty migration file should fail`() {
        runner.`empty migration file should fail`()
    }

    @Test
    fun `checksum mismatch should fail on re-run`() {
        runner.`checksum mismatch should fail on re-run`()
    }
}
