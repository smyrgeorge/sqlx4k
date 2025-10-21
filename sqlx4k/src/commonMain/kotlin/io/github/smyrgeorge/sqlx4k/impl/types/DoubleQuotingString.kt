package io.github.smyrgeorge.sqlx4k.impl.types

import kotlin.jvm.JvmInline

/**
 * A wrapper class for representing a string value that applies double quotes.
 *
 * This class is useful in contexts where a string value needs to be explicitly quoted
 * with double quotation marks, such as when working with SQL identifiers or similar scenarios.
 *
 * @property value The string value to be wrapped with double quotes.
 */
@JvmInline
value class DoubleQuotingString(val value: String) {
    override fun toString(): String = value
}