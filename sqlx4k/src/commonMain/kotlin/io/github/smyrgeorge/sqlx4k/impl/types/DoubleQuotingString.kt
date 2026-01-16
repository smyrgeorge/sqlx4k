package io.github.smyrgeorge.sqlx4k.impl.types

import io.github.smyrgeorge.sqlx4k.SQLError
import kotlin.jvm.JvmInline

/**
 * A wrapper class for representing a string value that applies double quotes for SQL identifiers.
 *
 * This class is useful for SQL identifiers (table names, column names) that need to be
 * quoted with double quotation marks. Double quotes within the value are automatically
 * escaped by doubling them (SQL standard).
 *
 * ⚠️ **SECURITY**: This class includes built-in validation to prevent SQL injection attacks.
 * The identifier must:
 * - Not be empty or exceed 128 characters
 * - Start with a letter (a-z, A-Z) or underscore (_)
 * - Contain only letters, digits, underscores, or dots (for schema.table notation)
 * - Invalid identifiers will throw an [io.github.smyrgeorge.sqlx4k.SQLError] with code [io.github.smyrgeorge.sqlx4k.SQLError.Code.InvalidIdentifier]
 *
 * Example: `DoubleQuotingString("user")` → `"user"`
 * Schema-qualified: `DoubleQuotingString("public.users")` → `"public"."users"`
 * Escaping: `DoubleQuotingString("my\"table")` → `"my""table"`
 *
 * @property value The string value to be wrapped with double quotes.
 */
@JvmInline
value class DoubleQuotingString(val value: String) {

    init {
        validate(value)
    }

    override fun toString(): String = value

    companion object {
        /**
         * Validates that a string is a safe SQL identifier to prevent injection attacks.
         *
         * A valid SQL identifier must:
         * - Not be empty
         * - Not exceed 128 characters in length
         * - Start with a letter (a-z, A-Z) or underscore (_)
         * - Contain only letters (a-z, A-Z), digits (0-9), underscores (_), or dots (.)
         * - Each segment between dots must be a valid identifier
         *
         * Dots are allowed to support schema-qualified identifiers (e.g., "schema.table").
         *
         * @param identifier The identifier string to validate.
         * @throws SQLError if the identifier is invalid or potentially unsafe.
         */
        private fun validate(identifier: String) {
            if (identifier.isEmpty()) {
                SQLError(
                    code = SQLError.Code.InvalidIdentifier,
                    message = "SQL identifier cannot be empty"
                ).raise()
            }

            if (identifier.length > 128) {
                SQLError(
                    code = SQLError.Code.InvalidIdentifier,
                    message = "SQL identifier exceeds maximum length of 128 characters"
                ).raise()
            }

            // Split by dots to handle schema.table.column notation
            val segments = identifier.split('.')

            for (segment in segments) {
                if (segment.isEmpty()) {
                    SQLError(
                        code = SQLError.Code.InvalidIdentifier,
                        message = "SQL identifier segment cannot be empty in '$identifier'"
                    ).raise()
                }

                // First character must be letter or underscore
                val first = segment[0]
                if (!first.isLetter() && first != '_') {
                    SQLError(
                        code = SQLError.Code.InvalidIdentifier,
                        message = "SQL identifier '$segment' must start with a letter or underscore"
                    ).raise()
                }

                // Remaining characters must be letters, digits, or underscores
                for (i in 1 until segment.length) {
                    val ch = segment[i]
                    if (!ch.isLetterOrDigit() && ch != '_') {
                        SQLError(
                            code = SQLError.Code.InvalidIdentifier,
                            message = "SQL identifier '$segment' contains invalid character '$ch'. Only letters, " +
                                    "digits, and underscores are allowed."
                        ).raise()
                    }
                }
            }
        }
    }
}