package io.github.smyrgeorge.sqlx4k.impl.statement

import io.github.smyrgeorge.sqlx4k.SQLError
import io.github.smyrgeorge.sqlx4k.Statement
import io.github.smyrgeorge.sqlx4k.Statement.ValueEncoderRegistry

/**
 * Represents a simple SQL statement that supports binding values to both positional
 * and named parameters. This class provides functionality to render the final SQL
 * string with all parameters replaced by their bound values.
 *
 * @param sql The SQL statement string that may contain positional ("?") and named
 *            (e.g., ":name") parameters to be bound.
 */
open class SimpleStatement(private val sql: String) : Statement {

    private val namedParameters: Set<String> by lazy { extractNamedParameters(sql) }
    private val positionalParameters: List<Int> by lazy { extractPositionalParameters(sql) }
    private val namedParametersValues: MutableMap<String, Any?> = mutableMapOf()
    private val positionalParametersValues: MutableMap<Int, Any?> = mutableMapOf()

    /**
     * Binds a value to a positional parameter in the statement based on the given index.
     *
     * @param index The zero-based index of the positional parameter to bind the value to.
     * @param value The value to bind to the specified positional parameter.
     * @return The current [Statement] instance to allow for method chaining.
     * @throws SQLError if the given index is out of bounds for the available positional parameters.
     */
    override fun bind(index: Int, value: Any?): SimpleStatement {
        if (index < 0 || index >= positionalParameters.size) {
            SQLError(
                code = SQLError.Code.PositionalParameterOutOfBounds,
                message = "Index '$index' out of bounds."
            ).ex()
        }
        positionalParametersValues[index] = value
        return this
    }

    /**
     * Binds a value to a named parameter in the statement.
     *
     * @param parameter The name of the parameter to bind the value to.
     * @param value The value to bind to the specified named parameter. May be null.
     * @return The current [Statement] instance to allow for method chaining.
     * @throws SQLError if the specified named parameter is not found.
     */
    override fun bind(parameter: String, value: Any?): SimpleStatement {
        if (!namedParameters.contains(parameter)) {
            SQLError(
                code = SQLError.Code.NamedParameterNotFound,
                message = "Parameter '$parameter' not found."
            ).ex()
        }
        namedParametersValues[parameter] = value
        return this
    }

    /**
     * Renders the SQL statement by replacing positional and named parameter placeholders
     * with their corresponding bound values using the provided encoder registry.
     *
     * @param encoders The `ValueEncoderRegistry` used to encode parameter values.
     * @return A string representing the rendered SQL statement with all parameters substituted by their bound values.
     */
    override fun render(encoders: ValueEncoderRegistry): String =
        sql.renderPositionalParameters(encoders).renderNamedParameters(encoders)

    /**
     * Replaces positional parameter placeholders within a string with their corresponding bound values.
     *
     * @param encoders The `ValueEncoderRegistry` used to encode the bound parameter values.
     * @return The string with all positional parameter placeholders replaced by their encoded values.
     * @throws SQLError if a value for a positional parameter is not supplied.
     */
    private fun String.renderPositionalParameters(encoders: ValueEncoderRegistry): String {
        // Create an iterator over the list of positional parameters.
        val paramsIterator = positionalParameters.iterator()
        // Use the regex's replace function to process all placeholders in one pass.
        return positionalParametersRegex.replace(this) {
            // Check for missing parameter values.
            if (!paramsIterator.hasNext()) {
                SQLError(
                    code = SQLError.Code.PositionalParameterValueNotSupplied,
                    message = "Not enough positional parameter values provided."
                ).ex()
            }
            val index = paramsIterator.next()
            if (!positionalParametersValues.containsKey(index)) {
                SQLError(
                    code = SQLError.Code.PositionalParameterValueNotSupplied,
                    message = "Value for positional parameter index '$index' was not supplied."
                ).ex()
            }
            // Encode the value and return its string representation.
            positionalParametersValues[index].encodeValue(encoders)
        }
    }

    /**
     * Renders the SQL string by replacing named parameter placeholders with their corresponding bound values.
     *
     * @param encoders The `ValueEncoderRegistry` used to encode parameter values.
     * @return A string representing the rendered SQL statement with all named parameters substituted by their bound values.
     * @throws SQLError if a value for a named parameter is not supplied.
     */
    private fun String.renderNamedParameters(encoders: ValueEncoderRegistry): String {
        // If there are no named parameters to replace, return the original string
        if (namedParameters.isEmpty()) return this

        // Pre-validate all parameters are bound to fail early
        for (name in namedParameters) {
            if (!namedParametersValues.containsKey(name)) {
                SQLError(
                    code = SQLError.Code.NamedParameterValueNotSupplied,
                    message = "Value for named parameter '$name' was not supplied."
                ).ex()
            }
        }

        // Use the regex's replace function to process all named parameters in one pass
        return nameParameterRegex.replace(this) { match ->
            val paramName = match.groupValues[1]
            // Only replace parameters that were identified during extraction
            if (paramName in namedParameters) {
                namedParametersValues[paramName].encodeValue(encoders)
            } else {
                // This should not happen if the extraction was correct
                // But keep the original text just in case
                match.value
            }
        }
    }

    /**
     * Regular expression pattern used for validating and extracting named parameters from a string.
     *
     * The pattern is used to match named parameters in the format ":parameterName",
     * where "parameterName" starts with a letter and is followed by alphanumeric characters.
     */
    private val nameParameterRegex = """(?<![:']):(?!:)([a-zA-Z][a-zA-Z0-9_]*)(?![a-zA-Z0-9_:'"])""".toRegex()
    private fun extractNamedParameters(sql: String): Set<String> =
        nameParameterRegex.findAll(sql).map { it.value.substring(1) }.toHashSet()

    /**
     * A regular expression used to match positional parameters in SQL queries.
     *
     * The positional parameter is represented by a question mark ("?").
     * This regex is utilized to locate all instances of positional parameters
     * within a given SQL query string.
     */
    private val positionalParametersRegex = "(\\?)(?=(?:[^']*(?:'[^']*')?)*[^']*$)(?=(?:[^\"]*(?:\"[^\"]*\")?)*[^\"]*$)(?=(?:[^`]*(?:`[^`]*`)?)*[^`]*$)".toRegex()
    private fun extractPositionalParameters(sql: String): List<Int> =
        positionalParametersRegex.findAll(sql).mapIndexed { idx, _ -> idx }.toList()
}
