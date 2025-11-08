package io.github.smyrgeorge.sqlx4k.mysql

import io.github.smyrgeorge.sqlx4k.ConnectionPool
import kotlin.test.Test

class NativeMySQLConnectionTests {

    private val options = ConnectionPool.Options.builder()
        .maxConnections(2)
        .build()

    private val db = mySQL(
        url = "mysql://localhost:13306/test",
        username = "mysql",
        password = "mysql",
        options = options
    )

    private val runner = CommonMySQLConnectionTests(db)

    @Test
    fun `acquire-release should allow operations then forbid after release`() {
        runner.`acquire-release should allow operations then forbid after release`()
    }

    @Test
    fun `double release should fail with ConnectionIsReleased`() {
        runner.`double release should fail with ConnectionIsReleased`()
    }

    @Test
    fun `connection begin-commit and rollback should work`() {
        runner.`connection begin-commit and rollback should work`()
    }

    @Test
    fun `fetchAll and execute should work while acquired`() {
        runner.`fetchAll and execute should work while acquired`()
    }

    @Test
    fun `status should be Acquired then Released`() {
        runner.`status should be Acquired then Released`()
    }

    @Test
    fun `setTransactionIsolationLevel should work for all isolation levels`() {
        runner.`setTransactionIsolationLevel should work for all isolation levels`()
    }

    @Test
    fun `setTransactionIsolationLevel should update the transactionIsolationLevel property`() {
        runner.`setTransactionIsolationLevel should update the transactionIsolationLevel property`()
    }

    @Test
    fun `setTransactionIsolationLevel should verify actual database isolation level`() {
        runner.`setTransactionIsolationLevel should verify actual database isolation level`()
    }

    @Test
    fun `setTransactionIsolationLevel should fail after connection is closed`() {
        runner.`setTransactionIsolationLevel should fail after connection is closed`()
    }

    @Test
    fun `connection isolation level should be reset to default after connection is closed`() {
        val db = mySQL(
            url = "mysql://localhost:13306/test",
            username = "mysql",
            password = "mysql",
            options = ConnectionPool.Options.builder().maxConnections(1).build()
        )
        runner.`connection isolation level should be reset to default after connection is closed`(db)
    }
}
