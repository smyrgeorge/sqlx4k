package io.github.smyrgeorge.sqlx4k.impl.statement

import io.github.smyrgeorge.sqlx4k.SQLError
import io.github.smyrgeorge.sqlx4k.Statement.ValueEncoderRegistry

/**
 * Represents an extended SQL statement that allows binding values to positional parameters
 * specific to PostgreSQL.
 *
 * This class extends [SimpleStatement] by leveraging PostgreSQL's custom parameter syntax
 * (e.g., $1, $2) and providing mechanisms to bind values to those parameters and render
 * the final SQL with all parameters substituted.
 *
 * @property sql The SQL string containing the statement.
 */
@Suppress("unused")
class ExtendedStatement(private val sql: String) : SimpleStatement(sql) {

    private val pgParameters: List<Int> by lazy {
        extractPgParameters(sql)
    }

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
        super
            .render(encoders)
            .renderPositionalParameters(encoders)

    /**
     * Replaces positional parameters in the SQL statement with their corresponding encoded values.
     *
     * @param encoders The `ValueEncoderRegistry` that provides the appropriate encoders for the parameter values.
     * @return The SQL statement with all positional parameters replaced by their encoded values.
     * @throws SQLError if a value for a positional parameter index is not supplied.
     */
    private fun String.renderPositionalParameters(encoders: ValueEncoderRegistry): String {
        var res: String = this
        pgParameters.forEach { index ->
            if (!pgParametersValues.containsKey(index)) {
                SQLError(
                    code = SQLError.Code.PositionalParameterValueNotSupplied,
                    message = "Value for positional parameter index '$index' was not supplied."
                ).ex()
            }
            val value = pgParametersValues[index].encodeValue(encoders)
            res = res.replace("\$${index + 1}", value)
        }
        return res
    }

    private val pgParametersRegex = "\\\$\\d+".toRegex()
    private fun extractPgParameters(sql: String): List<Int> =
        pgParametersRegex.findAll(sql).mapIndexed { idx, _ -> idx }.toList()
}