package io.github.smyrgeorge.sqlx4k.sqldelight

import app.cash.sqldelight.Query
import app.cash.sqldelight.Transacter
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlPreparedStatement
import co.touchlab.stately.concurrency.ThreadLocalRef
import io.github.smyrgeorge.sqlx4k.Driver
import io.github.smyrgeorge.sqlx4k.Transaction
import kotlinx.coroutines.runBlocking

/**
 * A driver implementation for SQLDelight using an underlying driver that supports
 * connection pooling and transactions.
 *
 * Implementation based on:
 * https://github.com/cashapp/sqldelight/blob/master/drivers/r2dbc-driver/src/main/kotlin/app/cash/sqldelight/driver/r2dbc/R2dbcDriver.kt
 *
 * @param T The type of the underlying driver, which must implement the [Driver], [Driver.Pool], and [Driver.Transactional] interfaces.
 * @property driver The underlying driver instance used to execute SQL statements.
 */
class Sqlx4kSqldelightDriver<T>(private val driver: T) :
    SqlDriver where T : Driver, T : Driver.Pool, T : Driver.Transactional {

    override fun execute(
        identifier: Int?, sql: String, parameters: Int, binders: (SqlPreparedStatement.() -> Unit)?
    ): QueryResult<Long> = QueryResult.AsyncValue {
        val prepared = SqlDelightPreparedStatement(sql).apply { binders?.invoke(this) }
        driver.execute(prepared.statement).getOrThrow().toLong()
    }

    override fun <R> executeQuery(
        identifier: Int?,
        sql: String,
        mapper: (SqlCursor) -> QueryResult<R>,
        parameters: Int,
        binders: (SqlPreparedStatement.() -> Unit)?
    ): QueryResult<R> = QueryResult.AsyncValue {
        val prepared = SqlDelightPreparedStatement(sql).apply { binders?.invoke(this) }
        val result = driver.fetchAll(prepared.statement).getOrThrow()
        return@AsyncValue mapper(SqlDelightCursor(result)).await()
    }

    private val threadLocal = ThreadLocalRef<SqlDelightTransaction>()
    private var transaction: SqlDelightTransaction?
        get() = threadLocal.get()
        set(value) {
            threadLocal.set(value)
        }

    override fun newTransaction(): QueryResult<Transacter.Transaction> = QueryResult.AsyncValue {
        transaction
            ?: SqlDelightTransaction(null, driver.begin().getOrThrow())
                .also { transaction = it }
    }

    override fun currentTransaction(): Transacter.Transaction? = transaction

    override fun close() {
        runBlocking { driver.close().getOrThrow() }
    }

    override fun addListener(vararg queryKeys: String, listener: Query.Listener) = Unit
    override fun removeListener(vararg queryKeys: String, listener: Query.Listener) = Unit
    override fun notifyListeners(vararg queryKeys: String) = Unit

    private inner class SqlDelightTransaction(
        override val enclosingTransaction: SqlDelightTransaction?,
        private val transaction: Transaction
    ) : Transacter.Transaction() {
        override fun endTransaction(successful: Boolean): QueryResult<Unit> = QueryResult.AsyncValue {
            if (enclosingTransaction == null) {
                if (successful) {
                    transaction.commit()
                } else {
                    transaction.rollback()
                }
            }
            this@Sqlx4kSqldelightDriver.transaction = enclosingTransaction
        }
    }
}
