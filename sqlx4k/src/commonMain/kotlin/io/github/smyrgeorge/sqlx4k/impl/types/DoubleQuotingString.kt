package io.github.smyrgeorge.sqlx4k.impl.types

import kotlin.jvm.JvmInline

/**
 * A wrapper class for representing a string value that applies double quotes.
 *
 * This class is useful for SQL identifiers (table names, column names) that need to be
 * quoted with double quotation marks. Double quotes within the value are automatically
 * escaped by doubling them (SQL standard).
 *
 * ⚠️ **Best Practice**: Use this ONLY for trusted identifiers. For user-controlled table/column
 * names, validate against a whitelist before using this class.
 *
 * Example: `DoubleQuotingString("user")` → `"user"`
 * Escaping: `DoubleQuotingString("my\"table")` → `"my""table"`
 *
 * @property value The string value to be wrapped with double quotes.
 */
@JvmInline
value class DoubleQuotingString(val value: String) {
    override fun toString(): String = value
}