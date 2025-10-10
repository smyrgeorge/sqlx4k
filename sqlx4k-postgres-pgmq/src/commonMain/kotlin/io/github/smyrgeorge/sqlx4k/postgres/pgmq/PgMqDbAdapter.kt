package io.github.smyrgeorge.sqlx4k.postgres.pgmq

import io.github.smyrgeorge.sqlx4k.QueryExecutor
import io.github.smyrgeorge.sqlx4k.ResultSet
import io.github.smyrgeorge.sqlx4k.Statement
import io.github.smyrgeorge.sqlx4k.Transaction

interface PgMqDbAdapter : QueryExecutor {
    suspend fun begin(): Result<Transaction>
    suspend fun <T> transaction(f: suspend Transaction.() -> T): T {
        val tx: Transaction = begin().getOrThrow()
        return try {
            val res = f(tx)
            tx.commit()
            res
        } catch (e: Throwable) {
            tx.rollback()
            throw e
        }
    }

    override suspend fun execute(sql: String): Result<Long>

    override suspend fun execute(statement: Statement): Result<Long> {
        error("Not needed for PgMqDbAdapter")
    }

    override suspend fun fetchAll(sql: String): Result<ResultSet> {
        error("Not needed for PgMqDbAdapter")
    }

    override suspend fun fetchAll(statement: Statement): Result<ResultSet> {
        error("Not needed for PgMqDbAdapter")
    }
}