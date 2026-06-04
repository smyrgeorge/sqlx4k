package io.github.smyrgeorge.sqlx4k.sqlite.cipher

import io.github.smyrgeorge.sqlx4k.ConnectionPool
import kotlin.test.Test

class NativeSQLiteCipherMigratorTests {

    private val options = ConnectionPool.Options.builder()
        .maxConnections(2)
        .build()

    private val db = SQLiteCipher(
        url = "sqlite://test-cipher.db",
        password = "test-passphrase",
        options = options
    )

    private val runner = CommonSQLiteCipherMigratorTests(db)

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
