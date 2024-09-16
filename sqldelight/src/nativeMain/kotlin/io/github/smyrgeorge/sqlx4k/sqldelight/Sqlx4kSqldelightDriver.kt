package io.github.smyrgeorge.sqlx4k.sqldelight

import app.cash.sqldelight.Query
import app.cash.sqldelight.Transacter
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlPreparedStatement
import io.github.smyrgeorge.sqlx4k.Driver
import io.github.smyrgeorge.sqlx4k.ResultSet
import io.github.smyrgeorge.sqlx4k.Statement
import io.github.smyrgeorge.sqlx4k.Transaction

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

    override fun newTransaction(): QueryResult<Transacter.Transaction> = QueryResult.AsyncValue {
        val transaction = driver.begin().getOrThrow()
        SqlDelightTransaction(null, transaction)
    }

    override fun currentTransaction(): Transacter.Transaction? {
        TODO("Not yet implemented")
    }

    override fun close() {
        TODO("Not yet implemented")
    }

    override fun addListener(vararg queryKeys: String, listener: Query.Listener) = Unit
    override fun removeListener(vararg queryKeys: String, listener: Query.Listener) = Unit
    override fun notifyListeners(vararg queryKeys: String) = Unit

    private inner class SqlDelightTransaction(
        override val enclosingTransaction: Transacter.Transaction?,
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
//            transaction = enclosingTransaction
        }
    }

    private inner class SqlDelightPreparedStatement(sql: String) : SqlPreparedStatement {
        var statement = Statement(sql)

        override fun bindBoolean(index: Int, boolean: Boolean?) {
            statement = statement.bind(index, boolean)
        }

        override fun bindBytes(index: Int, bytes: ByteArray?) {
            TODO("Not yet implemented")
        }

        override fun bindDouble(index: Int, double: Double?) {
            statement = statement.bind(index, double)
        }

        override fun bindLong(index: Int, long: Long?) {
            statement = statement.bind(index, long)
        }

        override fun bindString(index: Int, string: String?) {
            statement = statement.bind(index, string)
        }
    }

    private inner class SqlDelightCursor(
        private val result: ResultSet
    ) : SqlCursor {
        private lateinit var current: ResultSet.Row

        override fun next(): QueryResult.AsyncValue<Boolean> = QueryResult.AsyncValue {
            if (!result.hasNext()) return@AsyncValue false
            current = result.next()
            true
        }

        override fun getBoolean(index: Int): Boolean? = current.get(index).value?.toBoolean()
        override fun getBytes(index: Int): ByteArray? = current.get(index).valueAsByteArray()
        override fun getDouble(index: Int): Double? = current.get(index).value?.toDouble()
        override fun getLong(index: Int): Long? = current.get(index).value?.toLong()
        override fun getString(index: Int): String? = current.get(index).value
    }
}
