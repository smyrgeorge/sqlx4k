package io.github.smyrgeorge.sqlx4k.impl.migrate.utils

import io.github.smyrgeorge.sqlx4k.SQLError
import io.github.smyrgeorge.sqlx4k.impl.extensions.isIdentPart
import io.github.smyrgeorge.sqlx4k.impl.extensions.isIdentStart
import io.github.smyrgeorge.sqlx4k.impl.extensions.scanSql
import kotlin.jvm.JvmInline

/**
 * Splits a SQL script into its statements, at each `;` outside quotes, comments and dollar-quoted bodies.
 *
 * In the body of a routine or trigger (`CREATE TRIGGER ... BEGIN ...; END`, `CREATE PROCEDURE`, `CREATE FUNCTION
 * ... BEGIN ATOMIC`), a `;` ends an inner statement, not the outer one: there `BEGIN` / `CASE` ... `END` nesting is
 * tracked and the script only split at depth 0. (MySQL's `END IF`, `END LOOP`, `END WHILE` and `END REPEAT` close
 * constructs that are not counted, and `END CASE` closes a `CASE`.) Only those statements are tracked, as `begin` and
 * `end` are valid column names in MySQL. A statement that is only comments (e.g. a trailing `-- note;`) is left out:
 * MySQL rejects it as empty.
 */
fun splitSqlStatements(sql: String): List<String> {
    val result = mutableListOf<String>()
    var words = 0 // the words of the current statement seen so far (outside quotes and comments)
    var create = false // the current statement starts with CREATE
    var routine = false // ... and creates a routine or a trigger
    var depth = 0 // BEGIN / CASE ... END nesting in the routine's body

    fun flush(statement: CharSequence) {
        val stmt = statement.trim()
        if (!stmt.isOnlyComments()) result.add(stmt.toString())
        words = 0
        create = false
        routine = false
        depth = 0
    }

    val remainder = sql.scanSql { i, c, sb ->
        when {
            c == ';' && depth == 0 -> {
                flush(sb)
                sb.setLength(0)
                i + 1
            }

            c.isIdentStart() && (i == 0 || !this[i - 1].isIdentPart()) -> {
                var end = identEnd(i)
                val word = substring(i, end).uppercase()
                words++
                if (words == 1) create = word == "CREATE"
                else if (create && words <= ROUTINE_HEADER_WORDS && word in ROUTINE_KINDS) routine = true
                if (routine) when (word) {
                    "BEGIN", "CASE" -> depth++
                    "END" -> {
                        val (next, nextEnd) = nextWord(end)
                        when (next) {
                            // END IF / LOOP / WHILE / REPEAT: closes a construct that is not counted.
                            in UNCOUNTED_BLOCK_ENDS -> end = nextEnd
                            // END CASE: closes a CASE (whose closing CASE must not open another one).
                            "CASE" -> {
                                if (depth > 0) depth--
                                end = nextEnd
                            }

                            else -> if (depth > 0) depth--
                        }
                    }
                }
                sb.append(this, i, end)
                end
            }

            else -> null
        }
    }
    flush(remainder)
    return result
}

/** The words, after CREATE, within which the kind of object created is named (`CREATE OR REPLACE FUNCTION`, ...). */
private const val ROUTINE_HEADER_WORDS = 8
private val ROUTINE_KINDS = setOf("TRIGGER", "PROCEDURE", "FUNCTION", "EVENT")
private val UNCOUNTED_BLOCK_ENDS = setOf("IF", "LOOP", "WHILE", "REPEAT")

private fun String.identEnd(start: Int): Int {
    var end = start + 1
    while (end < length && this[end].isIdentPart()) end++
    return end
}

/** The word following [from] after whitespace, upper-cased, and the index after it (an empty word when there is none). */
private fun String.nextWord(from: Int): Pair<String, Int> {
    var i = from
    while (i < length && this[i].isWhitespace()) i++
    if (i >= length || !this[i].isIdentStart()) return "" to from
    val end = identEnd(i)
    return substring(i, end).uppercase() to end
}

/**
 * Whether this is blank or only comments (anything else, a quote included, is a statement). A MySQL / MariaDB
 * executable comment (`/*! ... */`, `/*M! ... */`, e.g. `/*!40101 SET NAMES utf8mb4 */` in a dump) is run by the server,
 * so it is a statement too.
 */
private fun CharSequence.isOnlyComments(): Boolean {
    var i = 0
    while (i < length) {
        when {
            this[i].isWhitespace() -> i++
            startsWith("/*!", i) || startsWith("/*M!", i) -> return false
            startsWith("--", i) -> {
                while (i < length && this[i] != '\n') i++
            }

            startsWith("/*", i) -> {
                var depth = 0
                do {
                    when {
                        startsWith("/*", i) -> { depth++; i += 2 }
                        startsWith("*/", i) -> { depth--; i += 2 }
                        else -> i++
                    }
                } while (depth > 0 && i < length)
            }

            else -> return false
        }
    }
    return true
}

/**
 * Represents a string used as an identifier in SQL operations.
 *
 * This type is designed to provide a layer of security by validating strings
 * used in SQL statements to ensure they don't contain unsafe characters that could
 * lead to SQL injection vulnerabilities.
 *
 * Validation rules are applied during instantiation, and an error is raised
 * if the string contains characters such as semicolons, newlines, or SQL comment markers.
 *
 * Validation is performed by the `validate` function to enforce restrictions on
 * potentially dangerous characters in SQL contexts.
 */
@JvmInline
internal value class IdentifierString(val value: String) {
    init {
        validate(value)
    }

    override fun toString(): String = value

    companion object {
        private fun validate(value: String) {
            // Security validation: reject potentially dangerous characters
            // to prevent SQL injection through unquoted strings
            if (value.contains(';') || value.contains('\n') || value.contains('\r') ||
                value.contains("--") || value.contains("/*") || value.contains("*/")
            ) {
                SQLError(
                    code = SQLError.Code.UnsafeStringContent,
                    message = "IdentifierString contains unsafe SQL characters (semicolons, newlines, or comment markers). This type should only be used for trusted SQL keywords and operators."
                ).raise()
            }
        }
    }
}
