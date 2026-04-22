package io.github.smyrgeorge.sqlx4k.postgres

import io.github.smyrgeorge.sqlx4k.ResultSet
import io.github.smyrgeorge.sqlx4k.ValueEncoder
import io.github.smyrgeorge.sqlx4k.postgres.extensions.asByteArray

/**
 * PostgreSQL-specific [ValueEncoder] for [ByteArray].
 *
 * Encodes the byte array to the PostgreSQL `bytea` hex input format (`\xHH..`).
 * Once the returned string is wrapped as a SQL literal by the rendering layer,
 * PostgreSQL implicitly casts it to `bytea` in any `bytea`-typed context
 * (column assignment, comparison, explicit `::bytea` cast).
 *
 * Decodes a `bytea` column into a [ByteArray] by stripping the `\x` prefix
 * and parsing the remaining hex.
 */
object ByteArrayEncoder : ValueEncoder<ByteArray> {
    override fun encode(value: ByteArray): Any = "\\x${value.toHexString()}"
    override fun decode(value: ResultSet.Row.Column): ByteArray = value.asByteArray()
}
