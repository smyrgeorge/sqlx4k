package io.github.smyrgeorge.sqlx4k.impl

import io.github.smyrgeorge.sqlx4k.SQLError
import io.github.smyrgeorge.sqlx4k.Statement

/**
 * Represents a single SQL statement that supports both positional and named parameters.
 *
 * @property sql The SQL statement as a string.
 */
@Suppress("unused")
open class SimpleStatement(
    private val sql: String
) : Statement {

    private val namedParameters: Set<String> by lazy {
        extractNamedParameters(sql)
    }

    private val positionalParameters: List<Int> by lazy {
        extractPositionalParameters(sql)
    }

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
     * Renders the SQL statement by replacing placeholders for positional and named parameters
     * with their respective bound values.
     *
     * This function first processes positional parameters, replacing each positional marker
     * with its corresponding value. It subsequently processes named parameters, replacing each
     * named marker (e.g., `:name`) with its corresponding value.
     *
     * @return A string representing the rendered SQL statement with all positional and named
     * parameters substituted by their bound values.
     */
    override fun render(): String = sql
        .renderPositionalParameters()
        .renderNamedParameters()

    /**
     * Replaces placeholders for positional parameters in the input string with their corresponding bound values.
     *
     * This function iterates through all the positional parameters and replaces their placeholders in the
     * string with the provided values. If a required value for a positional parameter is not supplied,
     * an error is thrown.
     *
     * @return A string with all positional parameters substituted by their bound values.
     * @throws SQLError if a value for a positional parameter is not supplied.
     */
    private fun String.renderPositionalParameters(): String {
        var res: String = this
        positionalParameters.forEach { index ->
            if (!positionalParametersValues.containsKey(index)) {
                SQLError(
                    code = SQLError.Code.PositionalParameterValueNotSupplied,
                    message = "Value for positional parameter index '$index' was not supplied."
                ).ex()
            }
            val value = positionalParametersValues[index].renderValue()
            val range = positionalParametersRegex.find(res)?.range ?: SQLError(
                code = SQLError.Code.PositionalParameterValueNotSupplied,
                message = "Value for positional parameter index '$index' was not supplied."
            ).ex()
            res = res.replaceRange(range, value)
        }
        return res
    }

    /**
     * Replaces named parameter placeholders in the string with the corresponding values.
     *
     * This function iterates through all the named parameters and substitutes their
     * placeholders in the string with the bound values. If a required value for a
     * named parameter is not supplied, an error is thrown.
     *
     * @return A string with all named parameters substituted by their bound values.
     * @throws SQLError if a value for a named parameter is not supplied.
     */
    private fun String.renderNamedParameters(): String {
        var res: String = this
        namedParameters.forEach { name ->
            if (!namedParametersValues.containsKey(name)) {
                SQLError(
                    code = SQLError.Code.NamedParameterValueNotSupplied,
                    message = "Value for named parameter '$name' was not supplied."
                ).ex()
            }
            val value = namedParametersValues[name].renderValue()
            res = res.replace(":$name", value)
        }
        return res
    }

    /**
     * Regular expression pattern used for validating and extracting named parameters from a string.
     *
     * The pattern is used to match named parameters in the format ":parameterName",
     * where "parameterName" starts with a letter and is followed by alphanumeric characters.
     */
    private val nameParameterRegex = """(?<!:):(?!:)[a-zA-Z]\w+""".toRegex()
    private fun extractNamedParameters(sql: String): Set<String> =
        nameParameterRegex.findAll(sql).map { it.value.substring(1) }.toHashSet()

    /**
     * A regular expression used to match positional parameters in SQL queries.
     *
     * The positional parameter is represented by a question mark ("?").
     * This regex is utilized to locate all instances of positional parameters
     * within a given SQL query string.
     */
    private val positionalParametersRegex = "\\?".toRegex()
    private fun extractPositionalParameters(sql: String): List<Int> =
        positionalParametersRegex.findAll(sql).mapIndexed { idx, _ -> idx }.toList()
}
