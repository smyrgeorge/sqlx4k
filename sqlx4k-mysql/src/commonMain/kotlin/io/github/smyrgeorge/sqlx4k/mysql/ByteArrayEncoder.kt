package io.github.smyrgeorge.sqlx4k.mysql

import io.github.smyrgeorge.sqlx4k.ResultSet
import io.github.smyrgeorge.sqlx4k.ValueEncoder
import io.github.smyrgeorge.sqlx4k.impl.extensions.asByteArray
import io.github.smyrgeorge.sqlx4k.impl.types.SqlRawLiteral

/**
 * MySQL-specific [ValueEncoder] for [ByteArray].
 *
 * Encodes the byte array as a MySQL hexadecimal literal (`X'HH..'`), wrapped
 * in [SqlRawLiteral] so the statement renderer emits it verbatim without
 * re-quoting. MySQL treats `X'HH..'` as a binary string, so it works as a
 * parameter for `BINARY`, `VARBINARY`, `BLOB`, and comparisons against them.
 *
 * Decodes a MySQL binary/hex column into a [ByteArray] by parsing the hex text.
 */
object ByteArrayEncoder : ValueEncoder<ByteArray> {
    override fun encode(value: ByteArray): Any = SqlRawLiteral("X'${value.toHexString()}'")
    override fun decode(value: ResultSet.Row.Column): ByteArray = value.asByteArray()
}
