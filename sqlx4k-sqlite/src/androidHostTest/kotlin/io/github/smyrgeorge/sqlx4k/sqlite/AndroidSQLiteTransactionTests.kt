package io.github.smyrgeorge.sqlx4k.sqlite

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.smyrgeorge.sqlx4k.ConnectionPool
import java.io.File
import kotlin.test.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AndroidSQLiteTransactionTests {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private val options = ConnectionPool.Options.builder()
        .maxConnections(2)
        .build()

    private val db: SQLite

    init {
        val dbFile = File(context.cacheDir, "sqlx4k-transaction-tests.db").apply {
            if (exists()) delete()
        }
        db = SQLite(
            context = context,
            url = "sqlite:${dbFile.absolutePath}",
            options = options
        )
    }

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
}
