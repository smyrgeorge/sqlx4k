package io.github.smyrgeorge.sqlx4k.postgres.sqldelight

import app.cash.sqldelight.Query
import app.cash.sqldelight.Transacter
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlPreparedStatement
import io.github.smyrgeorge.sqlx4k.postgres.PostgreSQL

class SqldelightPostgreSQL(
    val driver: PostgreSQL
): SqlDriver {
    override fun addListener(vararg queryKeys: String, listener: Query.Listener) {
        TODO("Not yet implemented")
    }

    override fun close() {
        TODO("Not yet implemented")
    }

    override fun currentTransaction(): Transacter.Transaction? {
        TODO("Not yet implemented")
    }

    override fun execute(
        identifier: Int?,
        sql: String,
        parameters: Int,
        binders: (SqlPreparedStatement.() -> Unit)?
    ): QueryResult<Long> {
        return QueryResult.AsyncValue {
            val prepared = Sqlx4kPreparedStatement().apply { binders?.invoke(this) }
            driver.execute(sql).getOrThrow().toLong()
        }
    }

    override fun <R> executeQuery(
        identifier: Int?,
        sql: String,
        mapper: (SqlCursor) -> QueryResult<R>,
        parameters: Int,
        binders: (SqlPreparedStatement.() -> Unit)?
    ): QueryResult<R> {
        TODO("Not yet implemented")
    }

    override fun newTransaction(): QueryResult<Transacter.Transaction> {
        TODO("Not yet implemented")
    }

    override fun notifyListeners(vararg queryKeys: String) {
        TODO("Not yet implemented")
    }

    override fun removeListener(vararg queryKeys: String, listener: Query.Listener) {
        TODO("Not yet implemented")
    }

   class Sqlx4kPreparedStatement : SqlPreparedStatement {
       override fun bindBoolean(index: Int, boolean: Boolean?) {
           TODO("Not yet implemented")
       }

       override fun bindBytes(index: Int, bytes: ByteArray?) {
           TODO("Not yet implemented")
       }

       override fun bindDouble(index: Int, double: Double?) {
           TODO("Not yet implemented")
       }

       override fun bindLong(index: Int, long: Long?) {
           TODO("Not yet implemented")
       }

       override fun bindString(index: Int, string: String?) {
           TODO("Not yet implemented")
       }
   }
}
