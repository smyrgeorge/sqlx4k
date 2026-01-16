package io.github.smyrgeorge.sqlx4k.impl.statement

import io.github.smyrgeorge.sqlx4k.SQLError
import io.github.smyrgeorge.sqlx4k.Statement
import io.github.smyrgeorge.sqlx4k.ValueEncoderRegistry
import io.github.smyrgeorge.sqlx4k.impl.extensions.encodeValue

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
abstract class AbstractStatement(private val sql: String) : Statement {
    /**
     * A set containing the names of all named parameters extracted from the SQL statement.
     * It is populated using a parser that skips over string literals, comments, and
     * PostgreSQL dollar-quoted strings.
     */
    override val extractedNamedParameters: Set<String> = extractNamedParameters()

    /**
     * The count of positional parameter placeholders ('?') extracted from the SQL query.
     */
    override val extractedPositionalParameters: Int = extractPositionalParameters()

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
    private val namedParametersValues: MutableMap<String, Any?> = mutableMapOf()

    /**
     * A mutable map storing the values bound to positional parameters in a SQL statement.
     *
     * The keys represent the index of positional parameters (zero-based), and the values
     * are the objects bound to these parameters. The values may be `null` if a `null` is
     * explicitly bound to a parameter.
     *
     * This map is used to manage parameter bindings when rendering the final SQL statement
     * or executing the statement and ensures that each positional parameter has a value
     * assigned before the statement is executed.
     *
     * Modifications to this map occur primarily through method calls that bind values to
     * positional parameters.
     */
    private val positionalParametersValues: MutableMap<Int, Any?> = mutableMapOf()

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
        if (!extractedNamedParameters.contains(parameter)) {
            SQLError(
                code = SQLError.Code.NamedParameterNotFound,
                message = "Parameter '$parameter' not found."
            ).raise()
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
    override fun render(encoders: ValueEncoderRegistry): String {
        if (extractedPositionalParameters == 0 && extractedNamedParameters.isEmpty()) return sql
        if (extractedNamedParameters.isEmpty()) return sql.renderPositionalParameters(encoders)
        if (extractedPositionalParameters == 0) return sql.renderNamedParameters(encoders)
        return sql.renderPositionalParameters(encoders).renderNamedParameters(encoders)
    }

    /**
     * Replaces positional '?' placeholders with encoded values, skipping content inside
     * quotes, comments, and dollar-quoted strings.
     */
    private fun String.renderPositionalParameters(encoders: ValueEncoderRegistry): String {
        if (extractedPositionalParameters == 0) return this
        var nextIndex = 0
        return renderWithScanner { i, c, sb ->
            if (c != '?') return@renderWithScanner null

            if (nextIndex >= extractedPositionalParameters) {
                SQLError(
                    code = SQLError.Code.PositionalParameterValueNotSupplied,
                    message = "Not enough positional parameter values provided."
                ).raise()
            }
            val value = positionalParametersValues.getOrElseNullable(nextIndex) {
                SQLError(
                    code = SQLError.Code.PositionalParameterValueNotSupplied,
                    message = "Value for positional parameter index '$nextIndex' was not supplied."
                ).raise()
            }
            sb.append(value.encodeValue(encoders))
            nextIndex += 1
            i + 1
        }
    }

    /**
     * Renders the SQL string by replacing named parameter placeholders with their corresponding bound values.
     * Skips content inside comments and quoted/dollar-quoted strings.
     */
    private fun String.renderNamedParameters(encoders: ValueEncoderRegistry): String {
        if (extractedNamedParameters.isEmpty()) return this

        return renderWithScanner { i, c, sb ->
            if (c != ':') return@renderWithScanner null

            // Skip PostgreSQL type casts '::'
            if (i + 1 < length && this[i + 1] == ':') {
                sb.append("::")
                return@renderWithScanner i + 2
            }
            if (i + 1 < length && isIdentStart(this[i + 1])) {
                var j = i + 2
                while (j < length && isIdentPart(this[j])) j++
                val name = substring(i + 1, j)
                if (name in extractedNamedParameters) {
                    val value = namedParametersValues.getOrElseNullable(name) {
                        SQLError(
                            code = SQLError.Code.NamedParameterValueNotSupplied,
                            message = "Value for named parameter '$name' was not supplied."
                        ).raise()
                    }
                    sb.append(value.encodeValue(encoders))
                    return@renderWithScanner j
                }
            }
            null
        }
    }

    /**
     * Shared scanner core that can optionally write output.
     */
    protected inline fun String.scan(
        writeOutput: Boolean,
        crossinline onNormalChar: String.(i: Int, c: Char, sb: StringBuilder) -> Int?
    ): String {
        // Pre-size with 60% more space in the buffer to reduce reallocation when parameters are replaced.
        val sb = if (writeOutput) StringBuilder((length * 1.6).toInt()) else StringBuilder(0)
        var i = 0
        var inSQ = false
        var inDQ = false
        var inBT = false
        var inLine = false
        var inBlock = false
        var dollarTag: String? = null

        while (i < length) {
            val c = this[i]

            // Handle comment endings
            if (inLine) {
                if (writeOutput) sb.append(c)
                if (c == '\n') inLine = false
                i++
                continue
            }
            if (inBlock) {
                if (writeOutput) sb.append(c)
                if (c == '*' && i + 1 < length && this[i + 1] == '/') {
                    if (writeOutput) sb.append('/')
                    i += 2
                    inBlock = false
                } else {
                    i++
                }
                continue
            }

            // Handle dollar-quoted string
            if (dollarTag != null) {
                if (writeOutput) sb.append(c)
                if (c == '$') {
                    val tag = startsWithDollarTagAt(i)
                    if (tag == dollarTag) {
                        if (tag.length > 1) {
                            if (writeOutput) sb.append(this, i + 1, i + tag.length)
                            i += tag.length
                        } else {
                            i++
                        }
                        dollarTag = null
                        continue
                    }
                }
                i++
                continue
            }

            // Handle quoted strings
            @Suppress("DuplicatedCode")
            if (inSQ) {
                if (writeOutput) sb.append(c)
                if (c == '\'') {
                    if (i + 1 < length && this[i + 1] == '\'') {
                        if (writeOutput) sb.append('\'')
                        i += 2
                    } else {
                        i++
                        inSQ = false
                    }
                } else i++
                continue
            }
            @Suppress("DuplicatedCode")
            if (inDQ) {
                if (writeOutput) sb.append(c)
                if (c == '"') {
                    if (i + 1 < length && this[i + 1] == '"') {
                        if (writeOutput) sb.append('"')
                        i += 2
                    } else {
                        i++
                        inDQ = false
                    }
                } else i++
                continue
            }
            if (inBT) {
                if (writeOutput) sb.append(c)
                if (c == '`') {
                    i++
                    inBT = false
                } else i++
                continue
            }

            // Start of contexts
            if (c == '-' && i + 1 < length && this[i + 1] == '-') {
                if (writeOutput) sb.append("--")
                i += 2
                inLine = true
                continue
            }
            if (c == '/' && i + 1 < length && this[i + 1] == '*') {
                if (writeOutput) sb.append("/*")
                i += 2
                inBlock = true
                continue
            }
            if (c == '\'') {
                if (writeOutput) sb.append(c); i++; inSQ = true; continue
            }
            if (c == '"') {
                if (writeOutput) sb.append(c); i++; inDQ = true; continue
            }
            if (c == '`') {
                if (writeOutput) sb.append(c); i++; inBT = true; continue
            }
            if (c == '$') {
                val tag = startsWithDollarTagAt(i)
                if (tag != null) {
                    if (writeOutput) sb.append(tag)
                    i += tag.length
                    dollarTag = tag
                    continue
                }
            }

            // Delegate to the callback for normal characters
            val consumed = onNormalChar(this, i, c, sb)
            if (consumed != null) {
                i = consumed; continue
            }

            // Default: copy character
            if (writeOutput) sb.append(c)
            i++
        }
        return if (writeOutput) sb.toString() else this
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
    ): String = scan(writeOutput = true, onNormalChar = onNormalChar)

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
    protected fun String.scanWithExtractor(
        onNormalChar: String.(i: Int, c: Char) -> Int?
    ) {
        scan(writeOutput = false) { i, c, _ -> onNormalChar(this, i, c) }
    }

    /**
     * Checks if the character sequence starts with a "dollar tag" at the given index.
     *
     * A "dollar tag" is defined as a sequence beginning with a dollar sign ('$'),
     * followed by alphanumeric characters or underscores, and ending with another
     * dollar sign ('$'). If a valid dollar tag is found at the specified index,
     * it is extracted and returned; otherwise, null is returned.
     *
     * @param start The starting index in the character sequence to check for the dollar tag.
     * @return The extracted dollar tag if it exists at the specified index, or null if no valid dollar tag is found.
     */
    protected fun CharSequence.startsWithDollarTagAt(start: Int): String? {
        if (start + 1 >= length) return null
        if (this[start] != '$') return null
        var j = start + 1
        while (j < length && (this[j].isLetterOrDigit() || this[j] == '_')) j++
        return if (j < length && this[j] == '$') substring(start, j + 1) else null
    }

    /**
     * Determines if the given character can represent the start of an identifier.
     * Uses a constant-time lookup table for ASCII characters.
     *
     * @param ch The character to check.
     * @return True if the character is an uppercase or lowercase English letter or underscore, false otherwise.
     */
    private fun isIdentStart(ch: Char): Boolean {
        val code = ch.code
        return code < 128 && IDENT_START[code]
    }

    /**
     * Determines if the given character can be part of an identifier.
     * Uses a constant-time lookup table for ASCII characters.
     *
     * This includes characters that can start an identifier, the underscore character ('_'), and digits.
     *
     * @param ch The character to check.
     * @return True if the character can be part of an identifier, false otherwise.
     */
    private fun isIdentPart(ch: Char): Boolean {
        val code = ch.code
        return code < 128 && IDENT_PART[code]
    }

    /**
     * Extracts all named parameters from the SQL statement.
     *
     * Named parameters are identified by a colon (`:`) followed by a valid identifier.
     * The method skips over content inside comments, quotes, or PostgreSQL type casts (`::`)
     * to ensure accurate extraction of named parameters.
     *
     * @return A set containing unique named parameter names found in the SQL statement.
     */
    private fun extractNamedParameters(): Set<String> {
        val names = linkedSetOf<String>()
        sql.scanWithExtractor { i, c ->
            if (c != ':') return@scanWithExtractor null
            // Skip PostgreSQL type casts '::'
            if (i + 1 < length && this[i + 1] == ':') return@scanWithExtractor i + 2
            if (i + 1 < length && isIdentStart(this[i + 1])) {
                var j = i + 2
                while (j < length && isIdentPart(this[j])) j++
                names.add(substring(i + 1, j))
                return@scanWithExtractor j
            }
            null
        }
        return names
    }

    /**
     * Extracts the count of positional parameter placeholders from the SQL statement.
     *
     * Positional parameters are denoted by the `?` character. The method scans the SQL string,
     * skipping over content such as comments, quotes, and dollar-quoted sections,
     * to ensure accurate identification of placeholders.
     *
     * @return The count of positional parameters found in the SQL statement.
     */
    private fun extractPositionalParameters(): Int {
        var count = 0
        sql.scanWithExtractor { i, c ->
            if (c == '?') {
                count++; return@scanWithExtractor i + 1
            }
            null
        }
        return count
    }

    companion object {
        /**
         * Lookup table for identifier start characters (constant-time check).
         * Covers ASCII range: a-z, A-Z, _
         */
        private val IDENT_START = BooleanArray(128) { ch ->
            ch == '_'.code || ch in 'a'.code..'z'.code || ch in 'A'.code..'Z'.code
        }

        /**
         * Lookup table for identifier part characters (constant-time check).
         * Covers ASCII range: a-z, A-Z, 0-9, _
         */
        private val IDENT_PART = BooleanArray(128) { ch ->
            ch == '_'.code || ch in 'a'.code..'z'.code || ch in 'A'.code..'Z'.code || ch in '0'.code..'9'.code
        }

        /**
         * Retrieves the value corresponding to the specified key from the map. If the key is not found
         * and does not exist in the map, the provided default value is returned. If the key exists with
         * a `null` value, `null` is returned.
         *
         * @param key The key whose associated value is to be returned.
         * @param defaultValue A lambda function that provides the default value to return if the key is not found.
         * @return The value associated with the specified key, or the result of the `defaultValue` function if the key is not found and does not exist in the map.
         */
        private inline fun <K, V> Map<K, V>.getOrElseNullable(key: K, defaultValue: () -> V): V {
            val value = get(key)
            if (value == null && !containsKey(key)) {
                return defaultValue()
            } else {
                @Suppress("UNCHECKED_CAST")
                return value as V
            }
        }
    }
}
