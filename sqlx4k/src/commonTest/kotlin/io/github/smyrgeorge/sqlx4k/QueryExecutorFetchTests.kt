package io.github.smyrgeorge.sqlx4k

import assertk.assertThat
import assertk.assertions.isEqualTo
import kotlin.test.Test
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking

class QueryExecutorFetchTests {

    private fun row(id: Int): ResultSet.Row =
        ResultSet.Row(listOf(ResultSet.Row.Column(ordinal = 0, name = "id", type = "INT", value = id.toString())))

    /** A driver whose streams are fixed in advance and which records the fetch size it was asked for. */
    private class StubExecutor : QueryExecutor {
        var lastFetchSize: Int = -1
        override val encoders: ValueEncoderRegistry = ValueEncoderRegistry.EMPTY
        override suspend fun execute(sql: String): Result<Long> = Result.success(0)
        override suspend fun execute(statement: Statement): Result<Long> = Result.success(0)
        override suspend fun fetchAll(sql: String): Result<ResultSet> =
            Result.success(ResultSet(emptyList(), null, ResultSet.Metadata(emptyList())))
        override suspend fun fetchAll(statement: Statement): Result<ResultSet> = fetchAll("")
        override fun fetch(sql: String, fetchSize: Int): Flow<ResultSet.Row> {
            lastFetchSize = fetchSize
            return flowOf(row(1), row(2), row(3))
        }
        override fun fetch(statement: Statement, fetchSize: Int): Flow<ResultSet.Row> {
            lastFetchSize = fetchSize
            return flowOf(row(10), row(20))
        }
        private fun row(id: Int): ResultSet.Row =
            ResultSet.Row(listOf(ResultSet.Row.Column(ordinal = 0, name = "id", type = "INT", value = id.toString())))
    }

    private val mapper = object : RowMapper<Int> {
        override fun map(row: ResultSet.Row, converters: ValueEncoderRegistry): Int = row.get("id").asString().toInt()
    }

    @Test
    fun `the row mapper overloads map every streamed row in order`() = runBlocking {
        val executor = StubExecutor()
        assertThat(executor.fetch("select 1;", mapper).toList()).isEqualTo(listOf(1, 2, 3))
        assertThat(executor.fetch(Statement.create("select 1;"), mapper).toList()).isEqualTo(listOf(10, 20))
    }

    @Test
    fun `the row mapper overloads forward the fetch size and default it when omitted`() = runBlocking {
        val executor = StubExecutor()
        executor.fetch("select 1;", mapper).toList()
        assertThat(executor.lastFetchSize).isEqualTo(QueryExecutor.DEFAULT_FETCH_SIZE)
        executor.fetch("select 1;", mapper, fetchSize = 7).toList()
        assertThat(executor.lastFetchSize).isEqualTo(7)
        executor.fetch(Statement.create("select 1;"), mapper, fetchSize = 9).toList()
        assertThat(executor.lastFetchSize).isEqualTo(9)
    }
}
