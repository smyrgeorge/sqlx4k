package io.github.smyrgeorge.sqlx4k

/**
 * An interface for encoding values of type `T` into a format suitable for
 * usage in database statements. Implementations of this interface will define
 * how to convert a value of type `T` into a type that can be safely and
 * correctly used within a SQL statement.
 *
 * @param T The type of the value to be rendered.
 */
interface ValueEncoder<T> {
    fun encode(value: T): Any
}
