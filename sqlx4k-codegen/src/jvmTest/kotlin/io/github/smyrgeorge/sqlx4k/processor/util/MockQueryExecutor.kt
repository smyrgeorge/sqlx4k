package io.github.smyrgeorge.sqlx4k.processor.util

import io.github.smyrgeorge.sqlx4k.QueryExecutor
import io.github.smyrgeorge.sqlx4k.ResultSet
import io.github.smyrgeorge.sqlx4k.Statement
import io.github.smyrgeorge.sqlx4k.ValueEncoderRegistry

/**
 * Mock implementation of QueryExecutor for testing repository implementations.
 *
 * This mock allows configuring responses and tracking method calls to verify
 * that the generated repository code interacts with the QueryExecutor correctly.
 */
class MockQueryExecutor : QueryExecutor {

    override val encoders: ValueEncoderRegistry = ValueEncoderRegistry.Companion.EMPTY

    // Track executed SQL statements
    private val _executedStatements = mutableListOf<String>()
    val executedStatements: List<String> get() = _executedStatements.toList()

    // Track fetchAll calls
    private val _fetchAllCalls = mutableListOf<String>()
    val fetchAllCalls: List<String> get() = _fetchAllCalls.toList()

    // Track execute calls
    private val _executeCalls = mutableListOf<String>()
    val executeCalls: List<String> get() = _executeCalls.toList()

    // Configurable responses
    private var executeResponse: Result<Long> = Result.success(1L)
    private var fetchAllResponse: Result<ResultSet> = Result.success(emptyResultSet())

    /**
     * Configure the response for execute() calls.
     */
    fun setExecuteResponse(response: Result<Long>) {
        executeResponse = response
    }

    /**
     * Configure the response for fetchAll() calls.
     */
    fun setFetchAllResponse(response: Result<ResultSet>) {
        fetchAllResponse = response
    }

    /**
     * Configure fetchAll to return a result set with the given rows.
     */
    fun setFetchAllRows(vararg rows: ResultSet.Row) {
        fetchAllResponse = Result.success(
            ResultSet(
                rows = rows.toList(),
                error = null,
                metadata = if (rows.isNotEmpty()) rows.first().toMetadata() else emptyMetadata()
            )
        )
    }

    /**
     * Reset all tracked calls and responses.
     */
    fun reset() {
        _executedStatements.clear()
        _fetchAllCalls.clear()
        _executeCalls.clear()
        executeResponse = Result.success(1L)
        fetchAllResponse = Result.success(emptyResultSet())
    }

    override suspend fun execute(sql: String): Result<Long> {
        _executedStatements.add(sql)
        _executeCalls.add(sql)
        return executeResponse
    }

    override suspend fun execute(statement: Statement): Result<Long> {
        val sql = statement.render(encoders)
        return execute(sql)
    }

    override suspend fun fetchAll(sql: String): Result<ResultSet> {
        _executedStatements.add(sql)
        _fetchAllCalls.add(sql)
        return fetchAllResponse
    }

    override suspend fun fetchAll(statement: Statement): Result<ResultSet> {
        val sql = statement.render(encoders)
        return fetchAll(sql)
    }

    /**
     * Check if a statement matching the pattern was executed.
     */
    fun hasExecuted(pattern: String): Boolean =
        executedStatements.any { it.contains(pattern, ignoreCase = true) }

    /**
     * Get the last executed statement.
     */
    fun lastExecutedStatement(): String? = executedStatements.lastOrNull()

    companion object {
        /**
         * Create an empty ResultSet.
         */
        fun emptyResultSet(): ResultSet = ResultSet(
            rows = emptyList(),
            error = null,
            metadata = emptyMetadata()
        )

        /**
         * Create empty metadata.
         */
        fun emptyMetadata(): ResultSet.Metadata = ResultSet.Metadata(emptyList())

        /**
         * Create a result row with the given columns.
         */
        fun row(vararg columns: Pair<String, String?>): ResultSet.Row {
            val cols = columns.mapIndexed { index, (name, value) ->
                ResultSet.Row.Column(
                    ordinal = index,
                    name = name,
                    type = "text",
                    value = value
                )
            }
            return ResultSet.Row(cols)
        }

        /**
         * Create a User result row with the given values.
         */
        fun userRow(id: Long, name: String, email: String): ResultSet.Row = row(
            "id" to id.toString(),
            "name" to name,
            "email" to email
        )

        /**
         * Create a count result row.
         */
        fun countRow(count: Long): ResultSet.Row = row("count" to count.toString())
    }
}