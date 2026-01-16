package io.github.smyrgeorge.sqlx4k.impl.types

import io.github.smyrgeorge.sqlx4k.SQLError
import kotlin.jvm.JvmInline

/**
 * A wrapper class for representing a string value without applying outer quotes.
 *
 * ⚠️ **SECURITY WARNING**: This class bypasses standard SQL quoting and should ONLY be used for
 * trusted, developer-controlled values such as SQL keywords (e.g., "CURRENT_TIMESTAMP", "DEFAULT").
 * **NEVER use with user input** - it can lead to SQL injection vulnerabilities!
 *
 * While single quotes are escaped internally, the value is not wrapped in quotes, making it
 * unsafe for user-controlled data.
 *
 * Safe usage: `NoQuotingString("CURRENT_TIMESTAMP")` or `NoQuotingString("DEFAULT")`
 * Unsafe: `NoQuotingString(userInput)` ❌
 *
 * @property value The raw string value represented by this class.
 */
@JvmInline
internal value class NoQuotingString(val value: String) {
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
                    message = "NoQuotingString contains unsafe SQL characters (semicolons, newlines, " +
                            "or comment markers). This type should only be used for trusted SQL keywords and operators."
                ).raise()
            }
        }
    }
}