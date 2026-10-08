package io.github.smyrgeorge.sqlx4k

import assertk.assertThat
import assertk.assertions.isEqualTo
import kotlin.test.Test
import kotlin.test.assertFailsWith

class QueryExecutorFetchTests {

    /** A driver that implements only the eager API: streaming is not supported. */
    private val executor = object : QueryExecutor {
        override val encoders: ValueEncoderRegistry = ValueEncoderRegistry.EMPTY
        override suspend fun execute(sql: String): Result<Long> = Result.success(0)
        override suspend fun execute(statement: Statement): Result<Long> = Result.success(0)
        override suspend fun fetchAll(sql: String): Result<ResultSet> =
            Result.success(ResultSet(emptyList(), null, ResultSet.Metadata(emptyList())))

        override suspend fun fetchAll(statement: Statement): Result<ResultSet> = fetchAll("")
    }

    @Test
    fun `fetch is not supported by default and fails when called not when collected`() {
        val e = assertFailsWith<UnsupportedOperationException> { executor.fetch("select 1;") }
        assertThat(e.message).isEqualTo(QueryExecutor.FETCH_NOT_SUPPORTED)
        assertFailsWith<UnsupportedOperationException> { executor.fetch(Statement.create("select 1;")) }
    }

    @Test
    fun `the row mapper overloads delegate to fetch`() {
        val mapper = object : RowMapper<ResultSet.Row> {
            override fun map(row: ResultSet.Row, converters: ValueEncoderRegistry): ResultSet.Row = row
        }
        assertFailsWith<UnsupportedOperationException> { executor.fetch("select 1;", mapper) }
        assertFailsWith<UnsupportedOperationException> { executor.fetch(Statement.create("select 1;"), mapper) }
    }
}
