package io.github.smyrgeorge.sqlx4k.impl.statement

import io.github.smyrgeorge.sqlx4k.SQLError
import io.github.smyrgeorge.sqlx4k.Statement
import io.github.smyrgeorge.sqlx4k.Statement.ValueEncoderRegistry

/**
 * Base class for SQL statements, providing functionality for binding parameters and rendering SQL.
 *
 * Represents an abstract SQL statement that supports both positional and named parameters.
 * Handles the extraction, management, and substitution of these parameters in the SQL string
 * to facilitate parameterized query execution.
 *
 * This class is designed for extensibility and reuse, offering methods to bind parameters,
 * render the SQL statement, and leverage encoder registries for value serialization.
 */
abstract class AbstractStatement(private val sql: String) : Statement {

    // Note: We avoid regex-based matching for SQL placeholders because it is
    // difficult to make regex reliably skip over comments and dollar-quoted strings.
    // Instead, we implement a small state machine scanner for correctness and safety.

    /**
     * A set containing the names of all named parameters extracted from the SQL statement.
     * It is populated using a parser that skips over string literals, comments and
     * PostgreSQL dollar-quoted strings.
     */
    private val namedParameters: Set<String> = extractNamedParameters()

    /**
     * A collection of positional parameter indexes extracted from the SQL query.
     * The list is simply [0, 1, 2, ...] with size equal to the count of
     * positional '?' placeholders found by the parser.
     */
    private val positionalParameters: List<Int> = extractPositionalParameters()

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
     * or executing the statement, and ensures that each positional parameter has a value
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
    override fun bind(parameter: String, value: Any?): AbstractStatement {
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
     * Replaces positional '?' placeholders with encoded values, skipping content inside
     * quotes, comments, and dollar-quoted strings.
     */
    private fun String.renderPositionalParameters(encoders: ValueEncoderRegistry): String {
        val it = positionalParameters.iterator()
        return renderWithScanner { i, c, sb ->
            if (c != '?') return@renderWithScanner null

            if (!it.hasNext()) {
                SQLError(
                    code = SQLError.Code.PositionalParameterValueNotSupplied,
                    message = "Not enough positional parameter values provided."
                ).ex()
            }
            val index = it.next()
            if (index !in positionalParametersValues) {
                SQLError(
                    code = SQLError.Code.PositionalParameterValueNotSupplied,
                    message = "Value for positional parameter index '$index' was not supplied."
                ).ex()
            }
            sb.append(positionalParametersValues[index].encodeValue(encoders))
            i + 1
        }
    }

    /**
     * Renders the SQL string by replacing named parameter placeholders with their corresponding bound values.
     * Skips content inside comments and quoted/dollar-quoted strings.
     */
    private fun String.renderNamedParameters(encoders: ValueEncoderRegistry): String {
        if (namedParameters.isEmpty()) return this
        for (name in namedParameters) {
            if (name !in namedParametersValues) {
                SQLError(
                    code = SQLError.Code.NamedParameterValueNotSupplied,
                    message = "Value for named parameter '$name' was not supplied."
                ).ex()
            }
        }

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
                if (name in namedParameters) {
                    sb.append(namedParametersValues[name].encodeValue(encoders))
                    return@renderWithScanner j
                }
            }
            null
        }
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
     * It may return a new index to control scanner processing or `null` to indicate the character
     * was not handled by the callback.
     * @return A new string resulting from the scanned and processed input, including any changes made
     * by the callback and the default handling of characters.
     */
    protected inline fun String.renderWithScanner(
        crossinline onNormalChar: String.(i: Int, c: Char, sb: StringBuilder) -> Int?
    ): String {
        val sb = StringBuilder(length)
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
                sb.append(c)
                if (c == '\n') inLine = false
                i++
                continue
            }
            if (inBlock) {
                sb.append(c)
                if (c == '*' && i + 1 < length && this[i + 1] == '/') {
                    sb.append('/')
                    i += 2
                    inBlock = false
                } else {
                    i++
                }
                continue
            }

            // Handle dollar-quoted string
            if (dollarTag != null) {
                sb.append(c)
                if (c == '$') {
                    val tag = startsWithDollarTagAt(i)
                    if (tag == dollarTag) {
                        if (tag.length > 1) {
                            sb.append(this, i + 1, i + tag.length)
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
                sb.append(c)
                if (c == '\'') {
                    if (i + 1 < length && this[i + 1] == '\'') {
                        sb.append('\'')
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
                sb.append(c)
                if (c == '"') {
                    if (i + 1 < length && this[i + 1] == '"') {
                        sb.append('"')
                        i += 2
                    } else {
                        i++
                        inDQ = false
                    }
                } else i++
                continue
            }
            if (inBT) {
                sb.append(c)
                if (c == '`') {
                    i++
                    inBT = false
                } else i++
                continue
            }

            // Start of contexts
            if (c == '-' && i + 1 < length && this[i + 1] == '-') {
                sb.append("--")
                i += 2
                inLine = true
                continue
            }
            if (c == '/' && i + 1 < length && this[i + 1] == '*') {
                sb.append("/*")
                i += 2
                inBlock = true
                continue
            }
            if (c == '\'') {
                sb.append(c); i++; inSQ = true; continue
            }
            if (c == '"') {
                sb.append(c); i++; inDQ = true; continue
            }
            if (c == '`') {
                sb.append(c); i++; inBT = true; continue
            }
            if (c == '$') {
                val tag = startsWithDollarTagAt(i)
                if (tag != null) {
                    sb.append(tag)
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
            sb.append(c)
            i++
        }
        return sb.toString()
    }


    /**
     * Scans a string using the provided callback for normal-context characters.
     * Each character is passed to the callback along with its index, allowing custom processing.
     *
     * The scanning process leverages `renderWithScanner` internally, enabling reuse of its scanning
     * logic while discarding the rendered output. Only the side-effects and index control from the
     * callback are utilized during the scanning process.
     *
     * @param onNormalChar A callback function invoked for each character in normal context.
     * The function takes the string (`String`) it operates on, the current index (`i`) of the character,
     * and the current character (`c`). Returning a new index alters the scan position, while returning
     * `null` continues the scan from the default next index.
     */
    protected fun String.scanWithExtractor(
        onNormalChar: String.(i: Int, c: Char) -> Int?
    ) {
        // Reuse the full scanner from renderWithScanner to avoid code duplication.
        // We discard the rendered String and only use the scanning side-effects and index control.
        renderWithScanner { i, c, _ -> onNormalChar(this, i, c) }
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
        while ((j < length && this[j].isLetterOrDigit()) || (j < length && this[j] == '_')) j++
        return if (j < length && this[j] == '$') substring(start, j + 1) else null
    }

    /**
     * Determines if the given character can represent the start of an identifier.
     *
     * @param ch The character to check.
     * @return True if the character is an uppercase or lowercase English letter, false otherwise.
     */
    private fun isIdentStart(ch: Char) = ch == '_' || ch in 'a'..'z' || ch in 'A'..'Z'

    /**
     * Determines if the given character can be part of an identifier.
     *
     * This includes characters that can start an identifier, the underscore character ('_'), and digits.
     *
     * @param ch The character to check.
     * @return True if the character can be part of an identifier, false otherwise.
     */
    private fun isIdentPart(ch: Char) = isIdentStart(ch) || ch == '_' || ch.isDigit()

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
        val s = sql
        val names = linkedSetOf<String>()
        s.scanWithExtractor { i, c ->
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
     * Extracts all positional parameter placeholders from the SQL statement.
     *
     * Positional parameters are denoted by the `?` character. The method scans the SQL string,
     * skipping over content such as comments, quotes, and dollar-quoted sections,
     * to ensure accurate identification of placeholders.
     *
     * @return A list of integers representing the indices of all positional parameters
     * found in the SQL statement. Each index corresponds to the order in which the
     * parameters appear, starting from 0.
     */
    private fun extractPositionalParameters(): List<Int> {
        var count = 0
        val s = sql
        s.scanWithExtractor { i, c ->
            if (c == '?') {
                count++; return@scanWithExtractor i + 1
            }
            null
        }
        return List(count) { it }
    }
}
