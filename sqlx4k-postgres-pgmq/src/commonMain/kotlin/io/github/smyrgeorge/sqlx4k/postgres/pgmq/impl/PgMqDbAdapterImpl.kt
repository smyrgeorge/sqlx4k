package io.github.smyrgeorge.sqlx4k.postgres.pgmq.impl

import io.github.smyrgeorge.sqlx4k.RowMapper
import io.github.smyrgeorge.sqlx4k.Statement
import io.github.smyrgeorge.sqlx4k.Transaction
import io.github.smyrgeorge.sqlx4k.postgres.IPostgresSQL
import io.github.smyrgeorge.sqlx4k.postgres.pgmq.PgMqDbAdapter

class PgMqDbAdapterImpl(private val pg: IPostgresSQL) : PgMqDbAdapter {
    override suspend fun begin(): Result<Transaction> = pg.begin()
    override suspend fun execute(sql: String): Result<Long> = pg.execute(sql)
    override suspend fun <T> fetchAll(statement: Statement, rowMapper: RowMapper<T>): Result<List<T>> =
        pg.fetchAll(statement, rowMapper)
}