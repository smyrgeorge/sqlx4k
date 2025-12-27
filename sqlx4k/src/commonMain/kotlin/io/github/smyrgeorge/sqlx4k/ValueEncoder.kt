package io.github.smyrgeorge.sqlx4k

/**
 * An interface for encoding values of type `T` into a format suitable for
 * usage in database statements. Implementations of this interface will define
 * how to convert a value of type `T` into a type that can be safely and
 * correctly used within a SQL statement.
 *
 * @param T The type of the value to be encoded.
 */
interface ValueEncoder<T> {
    /**
     * Encodes the given value into a format suitable for database statements.
     *
     * @param value The value to encode.
     * @return The encoded value as a database-compatible type.
     */
    fun encode(value: T): Any

    /**
     * Decodes a database column value into a value of type `T`.
     *
     * @param value The column value retrieved from a database row.
     * @return The decoded value of type `T`.
     */
    fun decode(value: ResultSet.Row.Column): T
}
