package io.github.smyrgeorge.sqlx4k.sqlite

import io.github.smyrgeorge.sqlx4k.ConnectionPool
import kotlin.test.Test

class NativeSQLiteTransactionTests {

    private val options = ConnectionPool.Options.builder()
        .maxConnections(2)
        .build()

    private val db = SQLite(
        url = "sqlite://test.db",
        options = options
    )

    private val runner = CommonSQLiteTransactionTests(db)

    @Test
    fun `begin-commit should persist data`() {
        runner.`begin-commit should persist data`()
    }

    @Test
    fun `begin-rollback should revert data`() {
        runner.`begin-rollback should revert data`()
    }

    @Test
    fun `using closed transaction should fail`() {
        runner.`using closed transaction should fail`()
    }

    @Test
    fun `transaction helper should commit on success and rollback on failure`() {
        runner.`transaction helper should commit on success and rollback on failure`()
    }

    @Test
    fun `TransactionContext new should set current and manage commit and rollback`() {
        runner.`TransactionContext new should set current and manage commit and rollback`()
    }

    @Test
    fun `TransactionContext withCurrent should reuse current context`() {
        runner.`TransactionContext withCurrent should reuse current context`()
    }

    @Test
    fun `TransactionContext withCurrent should create new when none exists`() {
        runner.`TransactionContext withCurrent should create new when none exists`()
    }

    @Test
    fun `transaction should rollback when returning Result failure`() {
        runner.`transaction should rollback when returning Result failure`()
    }

    @Test
    fun `transaction should rollback when returning Either Left`() {
        runner.`transaction should rollback when returning Either Left`()
    }

    @Test
    fun `transaction should rollback when returning Either Left with non-Throwable`() {
        runner.`transaction should rollback when returning Either Left with non-Throwable`()
    }

    @Test
    fun `transaction should commit when returning Either Right`() {
        runner.`transaction should commit when returning Either Right`()
    }

    @Test
    fun `transaction should commit when returning successful Result`() {
        runner.`transaction should commit when returning successful Result`()
    }
}
