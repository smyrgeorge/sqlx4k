package io.github.smyrgeorge.sqlx4k.impl.statement

import io.github.smyrgeorge.sqlx4k.Dialect
import io.github.smyrgeorge.sqlx4k.SQLError
import io.github.smyrgeorge.sqlx4k.Statement
import io.github.smyrgeorge.sqlx4k.ValueEncoderRegistry
import io.github.smyrgeorge.sqlx4k.impl.extensions.appendNativeValue
import io.github.smyrgeorge.sqlx4k.impl.extensions.encodeValue
import io.github.smyrgeorge.sqlx4k.impl.extensions.isIdentPart
import io.github.smyrgeorge.sqlx4k.impl.extensions.isIdentStart
import io.github.smyrgeorge.sqlx4k.impl.extensions.scanSql
import io.github.smyrgeorge.sqlx4k.impl.statement.AbstractStatement.Companion.NOT_SET

/**
 * Represents an abstract implementation of a SQL statement that supports binding
 * of positional and named parameters, along with rendering the statement into
 * a complete and executable SQL string.
 *
 * This class provides foundational methods for handling parameterized SQL statements,
 * including binding values to parameters, encoding values using a registry,
 * and rendering the final SQL string. It also includes utility methods for parsing
 * and processing SQL strings to extract or replace placeholders and handle specific
 * SQL constructs such as comments, quotes, and dollar-quoted strings.
 */
abstract class AbstractStatement(
    override val sql: String
) : Statement {
    /**
     * Holds the extracted parameters from the SQL statement as a pair of named parameter names
     * and the count of positional parameters. Computed once during construction via single-pass scanning.
     */
    private val extractedParameters: Pair<Set<String>, Int> = extractParameters()

    /**
     * A set containing the names of all named parameters extracted from the SQL statement.
     * It is populated using a parser that skips over string literals, comments, and
     * PostgreSQL dollar-quoted strings.
     */
    final override val extractedNamedParameters: Set<String> = extractedParameters.first

    /**
     * The count of positional parameter placeholders ('?') extracted from the SQL query.
     */
    final override val extractedPositionalParameters: Int = extractedParameters.second

    /**
     * A mutable map that holds the values bound to named parameters in an SQL statement.
     *
     * The keys in this map represent the names of the parameters as defined in the SQL statement,
     * while the corresponding values represent the bound values for those parameters. A value may be `null`,
     * indicating that a named parameter has been explicitly bound to a `null` value.
     *
     * This map is used during the rendering process of an SQL statement to replace named parameter placeholders
     * with their corresponding bound values. Any named parameter without a bound value will result
     * in an error during rendering.
     */
    override val namedParametersValues: MutableMap<String, Any?> =
        HashMap((extractedNamedParameters.size / 0.75f + 1).toInt())

    /**
     * An array storing the values bound to positional parameters in a SQL statement.
     *
     * The array index corresponds directly to the positional parameter index (zero-based).
     * Values may be `null` if explicitly bound to a `null` value. The sentinel [NOT_SET]
     * is used to distinguish between "not yet bound" and "bound to null".
     *
     * This array is used to manage parameter bindings when rendering the final SQL statement
     * and ensures that each positional parameter has a value assigned before execution.
     */
    override val positionalParametersValues: Array<Any?> =
        Array(extractedPositionalParameters) { NOT_SET }

    /**
     * Binds a value to a positional parameter in the statement based on the given index.
     *
     * @param index The zero-based index of the positional parameter to bind the value to.
     * @param value The value to bind to the specified positional parameter.
     * @return The current [Statement] instance to allow for method chaining.
     * @throws SQLError if the given index is out of bounds for the available positional parameters.
     */
    override fun bind(index: Int, value: Any?): AbstractStatement {
        if (index !in 0..<extractedPositionalParameters) {
            SQLError(
                code = SQLError.Code.PositionalParameterOutOfBounds,
                message = "Index '$index' out of bounds."
            ).raise()
        }
        positionalParametersValues[index] = value
        return this
    }

    /**
     * Binds a value to a named parameter in the statement.
     *
     * @param parameter The name of the parameter to bind the value to.
     * @param value The value to bind to the specified named parameter. Maybe null.
     * @return The current [Statement] instance to allow for method chaining.
     * @throws SQLError if the specified named parameter is not found.
     */
    override fun bind(parameter: String, value: Any?): AbstractStatement {
        if (parameter !in extractedNamedParameters) {
            SQLError(
                code = SQLError.Code.NamedParameterNotFound,
                message = "Parameter '$parameter' not found."
            ).raise()
        }
        namedParametersValues[parameter] = value ?: NULL_VALUE
        return this
    }

    /**
     * Renders the SQL statement by replacing positional and named parameter placeholders
     * with their corresponding bound values using the provided encoder registry.
     *
     * @param encoders The `ValueEncoderRegistry` used to encode parameter values.
     * @return A string representing the rendered SQL statement with all parameters substituted by their bound values.
     */
    override fun render(encoders: ValueEncoderRegistry): String {
        if (extractedPositionalParameters == 0 && extractedNamedParameters.isEmpty()) return sql
        var nextPositionalIndex = 0
        return sql.renderWithScanner { i, c, sb ->
            // Handle positional '?'
            if (c == '?' && extractedPositionalParameters > 0) {
                val value = getPositionalValue(nextPositionalIndex++)
                sb.append(value.encodeValue(encoders))
                return@renderWithScanner i + 1
            }
            // Handle named ':name'
            if (c == ':' && extractedNamedParameters.isNotEmpty()) {
                // Skip PostgreSQL type casts '::'
                if (i + 1 < length && this[i + 1] == ':') {
                    sb.append("::")
                    return@renderWithScanner i + 2
                }
                if (i + 1 < length && this[i + 1].isIdentStart()) {
                    var j = i + 2
                    while (j < length && this[j].isIdentPart()) j++
                    val name = substring(i + 1, j)
                    if (name in extractedNamedParameters) {
                        val value = getNamedValue(name)
                        sb.append(value.encodeValue(encoders))
                        return@renderWithScanner j
                    }
                }
            }
            null
        }
    }

    /**
     * Renders the SQL statement for native prepared-statement execution by replacing
     * `?` and `:name` placeholders with dialect-appropriate positional parameters
     * and collecting raw values in order.
     *
     * - [Dialect.PostgreSQL]: `$1, $2, ...`
     * - [Dialect.MySQL], [Dialect.SQLite]: `?`
     *
     * Collections ([Iterable], [Array], primitive arrays) are expanded into multiple placeholders.
     */
    override fun renderNativeQuery(dialect: Dialect, encoders: ValueEncoderRegistry): Statement.NativeQuery {
        if (extractedPositionalParameters == 0 && extractedNamedParameters.isEmpty()) {
            return Statement.NativeQuery(sql, dialect, emptyList())
        }

        val counter = intArrayOf(0)
        var nextPositionalIndex = 0
        val values = ArrayList<Any?>(extractedPositionalParameters + extractedNamedParameters.size * 2)

        val renderedSql = sql.renderWithScanner { i, c, sb ->
            // Handle positional '?'
            if (c == '?') {
                val value = getPositionalValue(nextPositionalIndex)
                appendNativeValue(value, sb, values, counter, dialect, encoders)
                nextPositionalIndex++
                return@renderWithScanner i + 1
            }

            // Handle named ':name'
            if (c == ':') {
                // Skip PostgreSQL type casts '::'
                if (i + 1 < length && this[i + 1] == ':') {
                    sb.append("::")
                    return@renderWithScanner i + 2
                }
                if (i + 1 < length && this[i + 1].isIdentStart()) {
                    var j = i + 2
                    while (j < length && this[j].isIdentPart()) j++
                    val name = substring(i + 1, j)
                    if (name in extractedNamedParameters) {
                        val value = getNamedValue(name)
                        appendNativeValue(value, sb, values, counter, dialect, encoders)
                        return@renderWithScanner j
                    }
                }
            }

            null
        }

        return Statement.NativeQuery(renderedSql, dialect, values)
    }

    /**
     * Retrieves the value bound to a positional parameter at the specified index.
     *
     * If the index is out of bounds or the value has not been supplied, an exception
     * is raised to indicate the error.
     *
     * @param index The zero-based index of the positional parameter whose bound value is to be retrieved.
     * @return The value bound to the specified positional parameter, or `null` if no value was supplied.
     * @throws SQLError If the index is out of bounds or the value is not set for the specified index.
     */
    private fun getPositionalValue(index: Int): Any? {
        if (index >= extractedPositionalParameters) {
            SQLError(
                code = SQLError.Code.PositionalParameterValueNotSupplied,
                message = "Not enough positional parameter values provided."
            ).raise()
        }
        val value = positionalParametersValues[index]
        if (value === NOT_SET) {
            SQLError(
                code = SQLError.Code.PositionalParameterValueNotSupplied,
                message = "Value for positional parameter index '$index' was not supplied."
            ).raise()
        }
        return value
    }

    /**
     * Retrieves the value associated with a named parameter in the statement.
     *
     * If the named parameter does not exist or its value is not supplied, an exception is raised
     * describing the error.
     *
     * @param name The name of the parameter whose value is to be retrieved.
     * @return The value associated with the specified named parameter, or `null` if no value was supplied.
     * @throws SQLError If the named parameter's value is not set.
     */
    private fun getNamedValue(name: String): Any? {
        return namedParametersValues.getNullableOrElse(name) {
            SQLError(
                code = SQLError.Code.NamedParameterValueNotSupplied,
                message = "Value for named parameter '$name' was not supplied."
            ).raise()
        }
    }

    /**
     * Scans the string and processes its content based on the provided callback. This method allows for
     * contextual character handling, including quoted strings, comments, and placeholders, while providing
     * the option to generate a new string containing the processed results.
     *
     * Delegates to the shared [scanSql] scanner.
     *
     * @param writeOutput Determines whether the scanned content should be written to a new string.
     * @param extractedNamedParametersSize The number of named parameter placeholders to estimate in the
     *                                     output buffer.
     * @param extractedPositionalParametersSize The number of positional parameter placeholders to estimate
     *                                          in the output buffer.
     * @param onNormalChar Callback function invoked for processing non-special characters.
     * @return The new processed string if `writeOutput` is `true`. Otherwise, the original string is returned.
     */
    protected inline fun String.scan(
        writeOutput: Boolean,
        extractedNamedParametersSize: Int = 0,
        extractedPositionalParametersSize: Int = 0,
        crossinline onNormalChar: String.(i: Int, c: Char, sb: StringBuilder) -> Int?
    ): String {
        val totalParams = extractedPositionalParametersSize + extractedNamedParametersSize
        return scanSql(
            writeOutput = writeOutput,
            estimatedExtraSize = totalParams * AVERAGE_PARAM_SIZE,
            onNormalChar = onNormalChar
        )
    }

    /**
     * Processes the string using a SQL scanner that skips comments, quotes, and dollar-quoted strings,
     * delegating the handling of normal-context characters to a provided callback function.
     *
     * The callback function is invoked for each unprocessed character and may decide to process it
     * or let the scanner continue its default operation.
     *
     * @param onNormalChar A callback function called for each unprocessed character
     * in the string. The function takes the current string (`String`), the current index (`i`),
     * the current character (`c`), and the `StringBuilder` (`sb`) being constructed.
     * It may return a new index to control scanner processing or `null` to indicate the callback
     * did not handle the character.
     * @return A new string resulting from the scanned and processed input, including any changes made
     * by the callback and the default handling of characters.
     */
    protected inline fun String.renderWithScanner(
        crossinline onNormalChar: String.(i: Int, c: Char, sb: StringBuilder) -> Int?
    ): String = scan(
        writeOutput = true,
        extractedNamedParametersSize = extractedNamedParameters.size,
        extractedPositionalParametersSize = extractedPositionalParameters,
        onNormalChar = onNormalChar
    )

    /**
     * Scans a string using the provided callback for normal-context characters.
     * Each character is passed to the callback along with its index, allowing custom processing.
     *
     * The scanning process leverages the same scanner core as renderWithScanner, while discarding
     * the rendered output. Only the side effects and index control from the callback are used.
     *
     * @param onNormalChar A callback function invoked for each character in a normal context.
     * The function takes the string (`String`) it operates on, the current index (`i`) of the character,
     * and the current character (`c`). Returning a new index alters the scan position, while returning
     * `null` continues the scan from the default next index.
     */
    protected inline fun String.scanWithExtractor(
        crossinline onNormalChar: String.(i: Int, c: Char) -> Int?
    ) {
        scan(writeOutput = false) { i, c, _ -> onNormalChar(this, i, c) }
    }

    /**
     * Extracts both named and positional parameters from the SQL statement in a single pass.
     *
     * Named parameters are identified by a colon (`:`) followed by a valid identifier.
     * Positional parameters are denoted by the `?` character. The method scans the SQL string once,
     * skipping over content inside comments, quotes, or dollar-quoted sections,
     * to ensure accurate extraction of both parameter types.
     *
     * @return A pair containing the set of named parameter names and the count of positional parameters.
     */
    private fun extractParameters(): Pair<Set<String>, Int> {
        val names = hashSetOf<String>()
        var positionalCount = 0
        sql.scanWithExtractor { i, c ->
            // Check for positional parameter '?'
            if (c == '?') {
                positionalCount++
                return@scanWithExtractor i + 1
            }
            // Check for named parameter ':'
            if (c != ':') return@scanWithExtractor null
            // Skip PostgreSQL type casts '::'
            if (i + 1 < length && this[i + 1] == ':') return@scanWithExtractor i + 2
            if (i + 1 < length && this[i + 1].isIdentStart()) {
                var j = i + 2
                while (j < length && this[j].isIdentPart()) j++
                names.add(substring(i + 1, j))
                return@scanWithExtractor j
            }
            null
        }
        return Pair(names, positionalCount)
    }

    companion object {
        /**
         * Sentinel object used to distinguish between "not yet bound" and "bound to null"
         * in the positional parameters array.
         */
        private val NOT_SET = Any()

        /**
         * A placeholder value used to represent a null equivalent within the context
         * of the `AbstractStatement` class. This is particularly useful for internal
         * operations where null handling is required but cannot be directly represented
         * or stored as `null` due to potential ambiguities or Kotlin's null safety rules.
         *
         * This object serves as a unique, non-null sentinel value and is not intended
         * for external use or comparison beyond its defined purpose within the scope of
         * `AbstractStatement`'s internal logic.
         */
        private val NULL_VALUE = Any()

        /**
         * Average estimated size (in characters) of an encoded parameter value.
         * Used for pre-sizing the StringBuilder buffer during rendering.
         */
        @PublishedApi
        internal const val AVERAGE_PARAM_SIZE = 16

        /**
         * Retrieves the nullable value associated with the given key in the map or returns a default value
         * if the key is not present or its associated value is `null`.
         *
         * This function is particularly useful when the map may store `null` values or when a key is not guaranteed
         * to exist in the map, allowing a fallback default value to be provided dynamically.
         *
         * @param key The key whose associated value is to be retrieved or for which the default value will be computed.
         * @param defaultValue A lambda function that supplies the default value when the key is not present
         * or its value is `null`.
         * @return The value associated with the provided key if it exists and is not `null`, otherwise the result of
         * invoking the `defaultValue` function. Returns `null` if the `defaultValue` lambda itself returns `null`.
         */
        private inline fun <K, V> Map<K, V?>.getNullableOrElse(key: K, defaultValue: () -> V): V? {
            val value = get(key)
            return when {
                value === NULL_VALUE -> null
                value == null -> defaultValue()
                else -> value
            }
        }
    }
}
