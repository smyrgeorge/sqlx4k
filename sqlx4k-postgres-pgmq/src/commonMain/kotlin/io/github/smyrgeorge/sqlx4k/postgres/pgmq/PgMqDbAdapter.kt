package io.github.smyrgeorge.sqlx4k.postgres.pgmq

import io.github.smyrgeorge.sqlx4k.QueryExecutor
import io.github.smyrgeorge.sqlx4k.ResultSet
import io.github.smyrgeorge.sqlx4k.Statement

interface PgMqDbAdapter : QueryExecutor, QueryExecutor.Transactional {
    override suspend fun execute(statement: Statement): Result<Long> = error("Not needed for PgMqDbAdapter")
    override suspend fun fetchAll(sql: String): Result<ResultSet> = error("Not needed for PgMqDbAdapter")
    override suspend fun fetchAll(statement: Statement): Result<ResultSet> = error("Not needed for PgMqDbAdapter")
}