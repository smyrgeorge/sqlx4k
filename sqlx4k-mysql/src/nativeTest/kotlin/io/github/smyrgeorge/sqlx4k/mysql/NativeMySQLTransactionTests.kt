package io.github.smyrgeorge.sqlx4k.mysql

import io.github.smyrgeorge.sqlx4k.ConnectionPool
import kotlin.test.Test

class NativeMySQLTransactionTests {

    private val options = ConnectionPool.Options.builder()
        .maxConnections(2)
        .build()

    private val db = mySQL(
        url = "mysql://localhost:13306/test",
        username = "mysql",
        password = "mysql",
        options = options
    )

    private val runner = CommonMySQLTransactionTests(db)

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
    fun `commit should be idempotent`() {
        runner.`commit should be idempotent`()
    }

    @Test
    fun `rollback should be idempotent`() {
        runner.`rollback should be idempotent`()
    }

    @Test
    fun `commit followed by rollback should fail`() {
        runner.`commit followed by rollback should fail`()
    }

    @Test
    fun `rollback followed by commit should fail`() {
        runner.`rollback followed by commit should fail`()
    }

    @Test
    fun `savepoint rollback should revert only changes after the savepoint`() {
        runner.`savepoint rollback should revert only changes after the savepoint`()
    }

    @Test
    fun `release savepoint should keep changes`() {
        runner.`release savepoint should keep changes`()
    }

    @Test
    fun `rollback to released savepoint should fail`() {
        runner.`rollback to released savepoint should fail`()
    }

    @Test
    fun `rollback to savepoint should recover from a failed statement`() {
        runner.`rollback to savepoint should recover from a failed statement`()
    }

    @Test
    fun `nested savepoints should roll back independently`() {
        runner.`nested savepoints should roll back independently`()
    }

    @Test
    fun `savepoint block should release on success and roll back on failure`() {
        runner.`savepoint block should release on success and roll back on failure`()
    }

    @Test
    fun `savepoint block should recover from a failed statement`() {
        runner.`savepoint block should recover from a failed statement`()
    }

    @Test
    fun `nested savepoint blocks should roll back independently`() {
        runner.`nested savepoint blocks should roll back independently`()
    }

    @Test
    fun `savepoint with unsafe name should fail`() {
        runner.`savepoint with unsafe name should fail`()
    }

    @Test
    fun `savepoint on closed transaction should fail`() {
        runner.`savepoint on closed transaction should fail`()
    }
}
