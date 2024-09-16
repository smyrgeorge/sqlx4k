package io.github.smyrgeorge.sqlx4k

@Suppress("unused")
class Statement(
    private val sql: String
) {

    private val namedParameterIndexes: Map<String, List<IntRange>> by lazy {
        extractNamedParametersIndexes(sql)
    }

    private val positionalParameterIndexes: List<IntRange> by lazy {
        extractPositionalParametersIndexes(sql)
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
        if (index < 0 || index >= positionalParameterIndexes.size) {
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
        if (!namedParameterIndexes.containsKey(parameter)) {
            DbError(
                code = DbError.Code.NamedParameterNotFound,
                message = "Parameter '$parameter' not found."
            ).ex()
        }
        namedParametersValues[parameter] = value
        return this
    }

    /**
     * Renders the SQL statement by either substituting positional or named parameters.
     *
     * @return The SQL statement with the appropriate parameters substituted.
     * @throws DbError if both named and positional parameters are mixed in the statement.
     */
    fun render(): String {
        if (namedParameterIndexes.isNotEmpty() && positionalParameterIndexes.isNotEmpty()) {
            DbError(
                code = DbError.Code.CannotMixPositionalWithNamedParameters,
                message = "Cannot mix named parameters (:name) with positional parameters (?)"
            ).ex()
        }

        return when {
            positionalParametersValues.isNotEmpty() -> renderPositionalParameters()
            else -> renderNamedParameters()
        }
    }

    private fun renderPositionalParameters(): String {
        var res: String = sql
        positionalParameterIndexes.forEachIndexed { index, _ ->
            if (!positionalParametersValues.containsKey(index)) {
                DbError(
                    code = DbError.Code.PositionalParameterValueNotSupplied,
                    message = "Value for positional parameter index '$index' was not supplied."
                ).ex()
            }
            val value = positionalParametersValues[index].toValueString()
            val range = extractFirstPositionalParametersIndex(res) ?: DbError(
                code = DbError.Code.PositionalParameterValueNotSupplied,
                message = "Value for positional parameter index '$index' was not supplied."
            ).ex()
            res = res.replaceRange(range, value)
        }
        return res
    }

    private fun renderNamedParameters(): String {
        var res: String = sql
        namedParameterIndexes.entries.forEach { (name, ranges) ->
            if (!namedParametersValues.containsKey(name)) {
                DbError(
                    code = DbError.Code.NamedParameterValueNotSupplied,
                    message = "Value for named parameter '$name' was not supplied."
                ).ex()
            }
            repeat(ranges.size) {
                val value = namedParametersValues[name].toValueString()
                val range = extractFirstNamedParametersIndex(res) ?: DbError(
                    code = DbError.Code.PositionalParameterValueNotSupplied,
                    message = "Value for named parameter '$name' was not supplied."
                ).ex()
                res = res.replaceRange(range, value)
            }
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
    private fun extractNamedParametersIndexes(sql: String): Map<String, List<IntRange>> =
        nameParameterRegex.findAll(sql)
            .mapIndexed { index, group -> Pair(group, index) }
            .groupBy({ it.first.value.substring(1) }, { it.first.range })

    private fun extractFirstNamedParametersIndex(sql: String): IntRange? =
        nameParameterRegex.find(sql)?.range

    /**
     * A regular expression used to match positional parameters in SQL queries.
     *
     * The positional parameter is represented by a question mark ("?").
     * This regex is utilized to locate all instances of positional parameters
     * within a given SQL query string.
     */
    private val positionalParametersRegex = "\\?".toRegex()
    private fun extractPositionalParametersIndexes(sql: String): List<IntRange> =
        positionalParametersRegex.findAll(sql).map { it.range }.toList()

    private fun extractFirstPositionalParametersIndex(sql: String): IntRange? =
        positionalParametersRegex.find(sql)?.range

    private fun Any?.toValueString(): String {
        return when (this) {
            null -> "null"
            is String -> "'${replace("'", "\'")}'"
            is Byte, is Boolean, is Int, is Long, is Short, is Double, is Float -> toString()
            else -> DbError(
                code = DbError.Code.NamedParameterTypeNotSupported,
                message = "Could not map named parameter of type ${this::class.qualifiedName}"
            ).ex()
        }
    }

    companion object {
        fun create(sql: String): Statement = Statement(sql)
    }
}
