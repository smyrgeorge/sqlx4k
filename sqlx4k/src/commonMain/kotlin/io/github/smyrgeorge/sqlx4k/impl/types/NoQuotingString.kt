package io.github.smyrgeorge.sqlx4k.impl.types

/**
 * A wrapper class for representing a string value without applying quotes.
 *
 * This class is helpful when working with raw SQL or similar contexts where quoting
 * the string is unnecessary or undesired. The value is directly returned
 * as-is when the `toString` function is invoked.
 *
 * @property value The raw string value represented by this class.
 */
class NoQuotingString(val value: String) {
    override fun toString(): String = value
}