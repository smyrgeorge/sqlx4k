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
     * A regular expression used to match positional PostgreSQL-style parameters in a SQL query string.
     *
     * The regex pattern matches placeholders in the format `$1`, `$2`, and so on, where the `$` symbol
     * is followed by one or more digits representing the positional parameter index.
     *
     * This regex is utilized to locate all PostgreSQL-style positional parameters within a given SQL
     * query string for processing or parameter replacement in the SQL statement.
     */
    private val pgParametersRegex =
        "\\$\\d++(?=(?:[^']*(?:'[^']*')?)*[^']*$)(?=(?:[^\"]*(?:\"[^\"]*\")?)*[^\"]*$)(?=(?:[^`]*(?:`[^`]*`)?)*[^`]*$)".toRegex()

    /**
     * A list of positional parameter indices extracted from the SQL statement.
     *
     * The `pgParameters` list contains the zero-based indices of the positional parameters
     * found within the `sql` statement. The indices are determined using the `pgParametersRegex`
     * regular expression within the `extractPgParameters` method.
     *
     * This property is primarily used to track the parameters in the SQL statement
     * and bind values or encode them during statement rendering.
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
        super.render(encoders).renderPositionalParameters(encoders)

    /**
     * Replaces positional parameters in the SQL statement with their corresponding encoded values.
     *
     * @param encoders The `ValueEncoderRegistry` that provides the appropriate encoders for the parameter values.
     * @return The SQL statement with all positional parameters replaced by their encoded values.
     * @throws SQLError if a value for a positional parameter index is not supplied.
     */
    private fun String.renderPositionalParameters(encoders: ValueEncoderRegistry): String {
        return pgParametersRegex.replace(this) { matchResult ->
            // Extract the number from the match, the regex matches patterns like "$1", "$2", etc.
            val indexStr = matchResult.value.substring(1)
            val index = indexStr.toIntOrNull() ?: SQLError(
                code = SQLError.Code.PositionalParameterValueNotSupplied,
                message = "Invalid positional parameter index '$indexStr'."
            ).ex()
            val adjustedIndex = index - 1
            if (!pgParametersValues.containsKey(adjustedIndex)) {
                SQLError(
                    code = SQLError.Code.PositionalParameterValueNotSupplied,
                    message = "Value for positional parameter index '$adjustedIndex' was not supplied."
                ).ex()
            }
            pgParametersValues[adjustedIndex].encodeValue(encoders)
        }
    }

    private fun extractPgParameters(): List<Int> =
        pgParametersRegex.findAll(sql).mapIndexed { idx, _ -> idx }.toList()
}
