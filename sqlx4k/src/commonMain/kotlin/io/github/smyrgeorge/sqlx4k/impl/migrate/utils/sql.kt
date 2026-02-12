package io.github.smyrgeorge.sqlx4k.impl.migrate.utils

import io.github.smyrgeorge.sqlx4k.SQLError
import io.github.smyrgeorge.sqlx4k.impl.extensions.scanSql
import kotlin.jvm.JvmInline

fun splitSqlStatements(sql: String): List<String> {
    val result = mutableListOf<String>()
    val remainder = sql.scanSql { i, c, sb ->
        if (c == ';') {
            val stmt = sb.toString().trim()
            if (stmt.isNotEmpty()) result.add(stmt)
            sb.setLength(0)
            i + 1
        } else null
    }
    val tail = remainder.trim()
    if (tail.isNotEmpty()) result.add(tail)
    return result
}

/**
 * Represents a string that is used as an identifier in SQL operations.
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
