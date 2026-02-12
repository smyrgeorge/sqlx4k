package io.github.smyrgeorge.sqlx4k.postgres

import io.github.smyrgeorge.sqlx4k.SQLError

/**
 * A value class that wraps a string and represents it as a double-quoted SQL identifier.
 *
 * This class ensures that the given string is a valid and safe SQL identifier by performing
 * validation during initialization. The string is then represented as a double-quoted value
 * to prevent SQL injection or invalid identifier usage.
 *
 * @property value The underlying string value of the SQL identifier.
 * @throws SQLError if the provided string does not meet the validation rules for safe SQL identifiers.
 */
@JvmInline
internal value class DoubleQuotingString(val value: String) {

    init {
        validate(value)
    }

    override fun toString(): String = "\"$value\""

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
                            message = """SQL identifier '$segment' contains invalid character '$ch'. Only letters, digits, and underscores are allowed."""
                        ).raise()
                    }
                }
            }
        }
    }
}
