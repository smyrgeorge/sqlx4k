package io.github.smyrgeorge.sqlx4k.utils

import io.github.smyrgeorge.sqlx4k.Connection
import io.github.smyrgeorge.sqlx4k.Driver
import io.github.smyrgeorge.sqlx4k.QueryExecutor
import io.github.smyrgeorge.sqlx4k.ResultSet
import io.github.smyrgeorge.sqlx4k.Statement
import io.github.smyrgeorge.sqlx4k.Transaction
import io.github.smyrgeorge.sqlx4k.ValueEncoderRegistry
import io.github.smyrgeorge.sqlx4k.impl.migrate.Migration
import io.github.smyrgeorge.sqlx4k.impl.migrate.MigrationFile
import io.github.smyrgeorge.sqlx4k.impl.migrate.Migrator
import kotlin.time.Duration

/**
 * A [Transaction] whose `commit`/`rollback` outcomes are configurable and whose calls are counted.
 * Used to unit-test the transaction control flow in [Transactional] and
 * [io.github.smyrgeorge.sqlx4k.impl.coroutines.TransactionContext] without a database.
 */
class ControllableTransaction(
    var commitResult: Result<Unit> = Result.success(Unit),
    var rollbackResult: Result<Unit> = Result.success(Unit),
) : Transaction {
    override var status: Transaction.Status = Transaction.Status.Open
    override var commited: Boolean = false
    override var rollbacked: Boolean = false
    var commitCount: Int = 0
    var rollbackCount: Int = 0

    override suspend fun commit(): Result<Unit> {
        commitCount++
        return commitResult.onSuccess {
            status = Transaction.Status.Closed
            commited = true
        }
    }

    override suspend fun rollback(): Result<Unit> {
        rollbackCount++
        return rollbackResult.onSuccess {
            status = Transaction.Status.Closed
            rollbacked = true
        }
    }

    override val encoders: ValueEncoderRegistry = ValueEncoderRegistry.EMPTY
    override suspend fun execute(sql: String): Result<Long> = Result.success(0)
    override suspend fun execute(statement: Statement): Result<Long> = Result.success(0)
    override suspend fun fetchAll(sql: String): Result<ResultSet> =
        Result.success(ResultSet(emptyList(), null, ResultSet.Metadata(emptyList())))

    override suspend fun fetchAll(statement: Statement): Result<ResultSet> = fetchAll("")
}

/** A minimal [QueryExecutor.Transactional] whose `begin()` result is supplied by [onBegin]. */
class FakeTransactional(
    private val onBegin: () -> Result<Transaction>,
) : QueryExecutor.Transactional {
    var beginCount: Int = 0
    override suspend fun begin(): Result<Transaction> {
        beginCount++
        return onBegin()
    }
}

/**
 * A minimal [Driver] for coroutine/transaction-context tests. Only `begin()` is meaningful — every
 * created [ControllableTransaction] is recorded in [transactions] and `begin()` calls are counted.
 * All other members throw, since the tests only exercise the transaction machinery.
 */
class FakeDriver(
    private val txFactory: () -> ControllableTransaction = { ControllableTransaction() },
) : Driver {
    var beginCount: Int = 0
    val transactions: MutableList<ControllableTransaction> = mutableListOf()

    override suspend fun begin(): Result<Transaction> {
        beginCount++
        val tx = txFactory()
        transactions += tx
        return Result.success(tx)
    }

    override val encoders: ValueEncoderRegistry = ValueEncoderRegistry.EMPTY
    override suspend fun execute(sql: String): Result<Long> = error("unused")
    override suspend fun execute(statement: Statement): Result<Long> = error("unused")
    override suspend fun fetchAll(sql: String): Result<ResultSet> = error("unused")
    override suspend fun fetchAll(statement: Statement): Result<ResultSet> = error("unused")

    override fun poolSize(): Int = 0
    override fun poolIdleSize(): Int = 0
    override suspend fun acquire(): Result<Connection> = error("unused")
    override suspend fun close(): Result<Unit> = error("unused")

    override suspend fun migrate(
        path: String,
        table: String,
        schema: String?,
        createSchema: Boolean,
        afterStatementExecution: suspend (Statement, Duration) -> Unit,
        afterFileMigration: suspend (Migration, Duration) -> Unit,
    ): Result<Migrator.Results> = error("unused")

    override suspend fun migrate(
        supplier: () -> List<MigrationFile>,
        table: String,
        schema: String?,
        createSchema: Boolean,
        afterStatementExecution: suspend (Statement, Duration) -> Unit,
        afterFileMigration: suspend (Migration, Duration) -> Unit,
    ): Result<Migrator.Results> = error("unused")

    override suspend fun migrate(
        files: List<MigrationFile>,
        table: String,
        schema: String?,
        createSchema: Boolean,
        afterStatementExecution: suspend (Statement, Duration) -> Unit,
        afterFileMigration: suspend (Migration, Duration) -> Unit,
    ): Result<Migrator.Results> = error("unused")
}
