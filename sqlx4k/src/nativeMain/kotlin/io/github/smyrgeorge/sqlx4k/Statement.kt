package io.github.smyrgeorge.sqlx4k

import kotlin.reflect.KClass

@Suppress("unused")
class Statement(
    private val sql: String
) {

    private val namedParameters: List<String> by lazy {
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
     * @return The current `Statement` instance to allow for method chaining.
     * @throws DbError if the given index is out of bounds for the available positional parameters.
     */
    fun bind(index: Int, value: Any?): Statement {
        if (index < 0 || index >= positionalParameters.size) {
            DbError(
                code = DbError.Code.PositionalParameterOutOfBounds,
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
     * @return The current `Statement` instance to allow for method chaining.
     * @throws DbError if the specified named parameter is not found.
     */
    fun bind(parameter: String, value: Any?): Statement {
        if (!namedParameters.contains(parameter)) {
            DbError(
                code = DbError.Code.NamedParameterNotFound,
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
    fun render(): String = sql
        .renderPositionalParameters()
        .renderNamedParameters()

    private fun String.renderPositionalParameters(): String {
        var res: String = this
        positionalParameters.forEach { index ->
            if (!positionalParametersValues.containsKey(index)) {
                DbError(
                    code = DbError.Code.PositionalParameterValueNotSupplied,
                    message = "Value for positional parameter index '$index' was not supplied."
                ).ex()
            }
            val value = positionalParametersValues[index].toValueString()
            val range = positionalParametersRegex.find(res)?.range ?: DbError(
                code = DbError.Code.PositionalParameterValueNotSupplied,
                message = "Value for positional parameter index '$index' was not supplied."
            ).ex()
            res = res.replaceRange(range, value)
        }
        return res
    }

    private fun String.renderNamedParameters(): String {
        var res: String = this
        namedParameters.forEach { name ->
            if (!namedParametersValues.containsKey(name)) {
                DbError(
                    code = DbError.Code.NamedParameterValueNotSupplied,
                    message = "Value for named parameter '$name' was not supplied."
                ).ex()
            }
            val value = namedParametersValues[name].toValueString()
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
    private fun extractNamedParameters(sql: String): List<String> =
        nameParameterRegex.findAll(sql).map { it.value.substring(1) }.toList()

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

    /**
     * Converts the value of the receiver to a string representation suitable for database operations.
     *
     * This method handles various types:
     * - `null` is represented as the string "null".
     * - `String` values are wrapped in single quotes and any single quotes within the string are escaped.
     * - Numeric and boolean values are converted to their string representation using `toString()`.
     * - For other types, it attempts to use a custom renderer. If no renderer is found, it throws a `DbError`.
     *
     * @return A string representation of the receiver suitable for database operations.
     * @throws DbError if the type of the receiver is unsupported and no appropriate renderer is found.
     */
    private fun Any?.toValueString(): String {
        return when (this) {
            null -> "null"
            is String -> {
                // https://stackoverflow.com/questions/12316953/insert-text-with-single-quotes-in-postgresql
                // https://stackoverflow.com/questions/9596652/how-to-escape-apostrophe-a-single-quote-in-mysql
                // https://stackoverflow.com/questions/603572/escape-single-quote-character-for-use-in-an-sqlite-query
                "'${replace("'", "''")}'"
            }

            is Byte, is Boolean, is Int, is Long, is Short, is Double, is Float -> toString()
            else -> {
                val error = DbError(
                    code = DbError.Code.NamedParameterTypeNotSupported,
                    message = "Could not map named parameter of type ${this::class.simpleName}"
                )

                val renderer = ValueRenderers.get(this::class) ?: error.ex()
                renderer.render(this).toValueString()
            }
        }
    }

    interface ValueRenderer<T> {
        fun <T> render(value: T): Any
    }

    class ValueRenderers {
        companion object {
            private val renderers: MutableMap<KClass<*>, ValueRenderer<*>> = mutableMapOf()

            fun get(type: KClass<*>): ValueRenderer<*>? = renderers[type]

            fun register(type: KClass<*>, renderer: ValueRenderer<*>) {
                renderers[type] = renderer
            }

            fun unregister(type: KClass<*>) {
                renderers.remove(type)
            }
        }
    }

    companion object {
        fun create(sql: String): Statement = Statement(sql)
    }
}
