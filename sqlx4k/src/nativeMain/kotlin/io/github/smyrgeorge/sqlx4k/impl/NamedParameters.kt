package io.github.smyrgeorge.sqlx4k.impl

import io.github.smyrgeorge.sqlx4k.DbError

/**
 * The [NamedParameters] object provides utility functions for rendering SQL strings with named parameters.
 *
 * @suppress Unused
 */
@Suppress("unused")
object NamedParameters {

    /**
     * Regular expression pattern used for validating and extracting named parameters from a string.
     *
     * The pattern is used to match named parameters in the format ":parameterName",
     * where "parameterName" starts with a letter and is followed by alphanumeric characters.
     */
    private val nameParameterRegex = Regex("""(?<!:):(?!:)[a-zA-Z]\w+""")

    /**
     * Renders the given SQL string by replacing named parameters with their corresponding values
     * from the provided `params` map. Optionally, a `paramsMapper` function can be provided to perform
     * additional transformation or mapping of the parameter values.
     *
     * @param sql The SQL string with named parameters.
     * @param parameters The map of parameter names and their corresponding values.
     * @param mapper An optional function to transform or map the parameter values.
     * @return The rendered SQL string with the named parameters replaced with their values.
     * @throws Sqlx4k.Error if a named parameter value is not supplied or if the type of the named parameter
     * is not supported.
     */
    fun render(
        sql: String,
        parameters: Map<String, Any?>,
        mapper: ((v: Any?) -> String?)?
    ): String {
        fun Any?.toValueString(): String {
            if (mapper != null) mapper(this)?.let { return it }
            return when (this) {
                null -> "null"
                is String -> "\'$this\'"
                is Byte, is Boolean, is Int, is Long, is Short, is Double, is Float -> toString()
                else -> DbError(
                    code = DbError.Code.NamedParameterTypeNotSupported,
                    message = "Could not map named parameter of type ${this::class.qualifiedName}"
                ).ex()
            }
        }

        fun extractNamedParamsIndexes(sql: String): Map<String, List<IntRange>> =
            nameParameterRegex.findAll(sql)
                .mapIndexed { index, group -> Pair(group, index) }
                .groupBy({ it.first.value.substring(1) }, { it.first.range })

        var res: String = sql
        extractNamedParamsIndexes(sql).entries.forEach { (name, ranges) ->
            if (!parameters.containsKey(name)) {
                DbError(
                    code = DbError.Code.NamedParameterValueNotSupplied,
                    message = "Value for named parameter '$name' was not supplied"
                ).ex()
            }
            ranges.forEach { res = res.replaceRange(it, parameters[name].toValueString()) }
        }
        return res
    }

}
