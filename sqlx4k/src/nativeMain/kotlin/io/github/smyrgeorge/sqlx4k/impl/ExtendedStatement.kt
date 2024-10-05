package io.github.smyrgeorge.sqlx4k.impl

import io.github.smyrgeorge.sqlx4k.SQLError

/**
 * A subclass of `SimpleStatement` that extends its capabilities to handle PostgreSQL specific
 * positional parameters using the dollar-sign notation (e.g., `$1`, `$2`).
 *
 * @property sql The SQL query that may contain PostgreSQL specific positional parameters.
 */
@Suppress("unused")
class ExtendedStatement(
    private val sql: String
) : SimpleStatement(sql) {

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
     * Renders the SQL statement by processing positional parameters.
     *
     * This method overrides the base class implementation to process
     * positional parameters specific to the PgStatement class. It first
     * delegates the rendering to the base class implementation, then
     * further processes positional parameters.
     *
     * @return A string representing the rendered SQL statement with all
     *         positional parameters substituted by their bound values.
     */
    override fun render(): String =
        super
            .render()
            .renderPositionalParameters()

    /**
     * Replaces positional parameters in the SQL string with their corresponding values.
     *
     * This function scans the SQL string for positional parameters indicated by
     * placeholders such as `$1`, `$2`, etc., and replaces them with their corresponding
     * bound values from the `pgParametersValues` map. If a required value is not supplied,
     * an error is thrown.
     *
     * @return A string where all positional parameters are replaced by their bound values.
     * @throws SQLError if a positional parameter value is not supplied.
     */
    private fun String.renderPositionalParameters(): String {
        var res: String = this
        pgParameters.forEach { index ->
            if (!pgParametersValues.containsKey(index)) {
                SQLError(
                    code = SQLError.Code.PositionalParameterValueNotSupplied,
                    message = "Value for positional parameter index '$index' was not supplied."
                ).ex()
            }
            val value = pgParametersValues[index].renderValue()
            res = res.replace("\$${index + 1}", value)
        }
        return res
    }

    private val pgParametersRegex = "\\\$\\d+".toRegex()
    private fun extractPgParameters(sql: String): List<Int> =
        pgParametersRegex.findAll(sql).mapIndexed { idx, _ -> idx }.toList()
}
