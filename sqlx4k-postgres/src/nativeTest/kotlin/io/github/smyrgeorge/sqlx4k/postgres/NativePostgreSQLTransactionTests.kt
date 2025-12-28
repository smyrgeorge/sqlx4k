package io.github.smyrgeorge.sqlx4k.postgres

import io.github.smyrgeorge.sqlx4k.ConnectionPool
import kotlin.test.Test

class NativePostgreSQLTransactionTests {

    private val options = ConnectionPool.Options.builder()
        .maxConnections(2)
        .build()

    private val db = postgreSQL(
        url = "postgresql://localhost:15432/test",
        username = "postgres",
        password = "postgres",
        options = options
    )

    private val runner = CommonPostgreSQLTransactionTests(db)

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
}
