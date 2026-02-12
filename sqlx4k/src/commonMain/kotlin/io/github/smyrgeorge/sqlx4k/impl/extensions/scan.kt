package io.github.smyrgeorge.sqlx4k.impl.extensions

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
 * Determines if the given character can represent the start of an identifier.
 * Uses a constant-time lookup table for ASCII characters.
 *
 * @return True if the character is an uppercase or lowercase English letter or underscore, false otherwise.
 */
@PublishedApi
internal fun Char.isIdentStart(): Boolean = code < 128 && IDENT_START[code]

/**
 * Determines if the given character can be part of an identifier.
 * Uses a constant-time lookup table for ASCII characters.
 *
 * This includes characters that can start an identifier, the underscore character ('_'), and digits.
 *
 * @return True if the character can be part of an identifier, false otherwise.
 */
@PublishedApi
internal fun Char.isIdentPart(): Boolean = code < 128 && IDENT_PART[code]

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
@PublishedApi
internal fun CharSequence.startsWithDollarTagAt(start: Int): String? {
    if (start + 1 >= length) return null
    if (this[start] != '$') return null
    // $$ (empty tag) is valid
    if (this[start + 1] == '$') return "$$"
    // Tag content must start with a letter or underscore (PostgreSQL identifier rules).
    // This prevents $1, $2 etc. from being mistaken for dollar-quoted string delimiters.
    if (!this[start + 1].isIdentStart()) return null
    var j = start + 2
    while (j < length && this[j].isIdentPart()) j++
    return if (j < length && this[j] == '$') substring(start, j + 1) else null
}

/**
 * Scans the string and processes its content based on the provided callback. This method
 * correctly handles SQL lexical contexts: single-quoted strings, double-quoted identifiers,
 * backtick-quoted identifiers, line comments (`--`), nested block comments, and
 * PostgreSQL dollar-quoted strings.
 *
 * For each character outside these contexts the [onNormalChar] callback is invoked. It may
 * consume one or more characters (returning a new index), or return `null` to let the
 * scanner copy the character to the output buffer (when [writeOutput] is `true`).
 *
 * @param writeOutput When `true` the scanned content is collected in a [StringBuilder]
 *   and the resulting string is returned. When `false` the original string is returned
 *   unchanged (useful for extraction-only scans).
 * @param estimatedExtraSize Additional capacity added to the [StringBuilder] beyond the
 *   source string length. Useful when the callback is expected to expand placeholders.
 * @param onNormalChar Callback invoked for each character in "normal" context.
 *   Parameters: receiver is the source [String], `i` – current index, `c` – current
 *   character, `sb` – the output buffer. Return a new index to advance the scanner,
 *   or `null` to let the scanner append the character and advance by one.
 * @return The processed string when [writeOutput] is `true`, otherwise the original string.
 */
@PublishedApi
internal inline fun String.scanSql(
    writeOutput: Boolean = true,
    estimatedExtraSize: Int = 0,
    crossinline onNormalChar: String.(i: Int, c: Char, sb: StringBuilder) -> Int?
): String {
    val sb = if (writeOutput) StringBuilder(maxOf(length + estimatedExtraSize, 0)) else StringBuilder(0)

    var i = 0
    var inSQ = false
    var inDQ = false
    var inBT = false
    var inLine = false
    var blockDepth = 0
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
        if (blockDepth > 0) {
            if (writeOutput) sb.append(c)
            when (c) {
                '/' if i + 1 < length && this[i + 1] == '*' -> {
                    if (writeOutput) sb.append('*')
                    i += 2
                    blockDepth++
                }

                '*' if i + 1 < length && this[i + 1] == '/' -> {
                    if (writeOutput) sb.append('/')
                    i += 2
                    blockDepth--
                }

                else -> {
                    i++
                }
            }
            continue
        }

        // Handle dollar-quoted string
        if (dollarTag != null) {
            if (writeOutput) sb.append(c)
            if (c == '$') {
                val tag = startsWithDollarTagAt(i)
                if (tag == dollarTag) {
                    // Append remaining tag characters after the '$' already appended above
                    if (writeOutput) sb.append(this, i + 1, i + tag.length)
                    i += tag.length
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
            blockDepth = 1
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
            require(consumed > i) { "onNormalChar must advance the index (returned $consumed at position $i)" }
            i = consumed; continue
        }

        // Default: copy character
        if (writeOutput) sb.append(c)
        i++
    }
    return if (writeOutput) sb.toString() else this
}
