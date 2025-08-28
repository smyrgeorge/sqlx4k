package io.github.smyrgeorge.sqlx4k.impl.statement

import io.github.smyrgeorge.sqlx4k.SQLError
import io.github.smyrgeorge.sqlx4k.Statement.ValueEncoderRegistry

/**
 * The `ExtendedStatement` class provides an implementation that extends the functionality
 * of an SQL statement, allowing the handling of PostgreSQL-style positional parameters.
 *
 * Positional parameters in the form `$1`, `$2`, etc., are supported and can be bound to
 * specific values, encoded, and rendered into a final SQL statement.
 *
 * @param sql The SQL query string containing positional PostgreSQL-style parameters.
 */
class ExtendedStatement(private val sql: String) : AbstractStatement(sql) {

    /**
     * A list of positional PostgreSQL-style parameter indices extracted from the SQL statement.
     * The list is [0, 1, 2, ...] sized to the number of occurrences of $n placeholders.
     * Extraction skips comments, quoted and dollar-quoted strings.
     */
    private val pgParameters: List<Int> = extractPgParameters()

    /**
     * A mutable map used for storing the values of positional parameters within
     * the prepared SQL statement of the `ExtendedStatement` class.
     *
     * The keys in the map represent the zero-based positional indices of the
     * parameters in the SQL statement, while the corresponding values represent
     * the parameter values. These values can be `null` for nullable parameters.
     *
     * This map is primarily utilized to manage, bind, and retrieve parameter values
     * during statement preparation and rendering processes.
     */
    private val pgParametersValues: MutableMap<Int, Any?> = mutableMapOf()

    /**
     * Binds a value to a positional parameter in the prepared statement.
     *
     * @param index The zero-based positional index of the parameter to bind.
     * @param value The value to bind to the specified parameter.
     * @return The current `PgStatement` instance with the bound parameter, allowing method chaining.
     * @throws SQLError if the provided index is out of bounds of the statement's parameters.
     */
    override fun bind(index: Int, value: Any?): ExtendedStatement {
        if (index < 0 || index >= pgParameters.size) {
            SQLError(
                code = SQLError.Code.PositionalParameterOutOfBounds,
                message = "Index '$index' out of bounds."
            ).ex()
        }
        pgParametersValues[index] = value
        return this
    }

    /**
     * Renders the SQL statement, including encoding all positional parameters using the specified encoder registry.
     *
     * @param encoders The `ValueEncoderRegistry` that provides the appropriate encoders for the parameter values.
     * @return A string representing the fully rendered SQL statement with all parameters encoded.
     */
    override fun render(encoders: ValueEncoderRegistry): String =
        sql.renderPgParameters(encoders)

    /**
     * Replaces positional parameters in the SQL statement with their corresponding encoded values.
     *
     * @param encoders The `ValueEncoderRegistry` that provides the appropriate encoders for the parameter values.
     * @return The SQL statement with all positional parameters replaced by their encoded values.
     * @throws SQLError if a value for a positional parameter index is not supplied.
     */
    private fun String.renderPgParameters(encoders: ValueEncoderRegistry): String =
            renderWithScanner { i, c, sb ->
                if (c != '$') return@renderWithScanner null
                // attempt $<digits> (but skip dollar-quoted start, already handled by scanner)
                var j = i + 1
                if (j < length && this[j].isDigit()) {
                    while (j < length && this[j].isDigit()) j++
                    val numStr = substring(i + 1, j)
                    val idx1 = numStr.toIntOrNull() ?: return@renderWithScanner null
                    val zeroIdx = idx1 - 1
                    if (zeroIdx !in pgParametersValues) {
                        SQLError(
                            code = SQLError.Code.PositionalParameterValueNotSupplied,
                            message = "Value for positional parameter index '$zeroIdx' was not supplied."
                        ).ex()
                    }
                    sb.append(pgParametersValues[zeroIdx].encodeValue(encoders))
                    return@renderWithScanner j
                }
                null
            }

    private fun extractPgParameters(): List<Int> {
        var maxIndex = 0
        val s = sql
        s.scanWithExtractor { i, c ->
            if (c != '$') return@scanWithExtractor null
            var j = i + 1
            if (j < length && this[j].isDigit()) {
                while (j < length && this[j].isDigit()) j++
                val idx1 = substring(i + 1, j).toIntOrNull() ?: 0
                if (idx1 > maxIndex) maxIndex = idx1
                return@scanWithExtractor j
            }
            null
        }
        return List(maxIndex) { it }
    }
}
