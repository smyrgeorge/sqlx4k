package io.github.smyrgeorge.sqlx4k.sqlite

import io.github.smyrgeorge.sqlx4k.ResultSet
import io.github.smyrgeorge.sqlx4k.ValueEncoder
import io.github.smyrgeorge.sqlx4k.impl.extensions.asByteArray
import io.github.smyrgeorge.sqlx4k.impl.types.SqlRawLiteral

/**
 * SQLite-specific [ValueEncoder] for [ByteArray].
 *
 * Encodes the byte array as a SQLite hexadecimal BLOB literal (`X'HH..'`), wrapped
 * in [SqlRawLiteral] so the statement renderer emits it verbatim without re-quoting.
 * SQLite treats `X'HH..'` as a BLOB, so it works as a parameter for `BLOB` columns,
 * `randomblob`/`zeroblob` comparisons, and anywhere a BLOB is expected.
 *
 * Decodes a SQLite BLOB/hex column into a [ByteArray] by parsing the hex text.
 */
object ByteArrayEncoder : ValueEncoder<ByteArray> {
    override fun encode(value: ByteArray): Any = SqlRawLiteral("X'${value.toHexString()}'")
    override fun decode(value: ResultSet.Row.Column): ByteArray = value.asByteArray()
}
