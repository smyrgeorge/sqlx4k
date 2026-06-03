@file:OptIn(ExperimentalTime::class, ExperimentalUuidApi::class)

package io.github.smyrgeorge.sqlx4k.sqlite.cipher

import io.github.smyrgeorge.sqlx4k.ResultSet
import io.github.smyrgeorge.sqlx4k.SQLError
import io.github.smyrgeorge.sqlx4k.impl.extensions.toTimestampString
import io.github.smyrgeorge.sqlx4k.impl.types.SqlRawLiteral
import io.github.smyrgeorge.sqlx4k.impl.types.TypedNull
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime

// Parameter discriminators — must match the PARAM_* constants in the Rust core.
private const val PARAM_NULL = 0
private const val PARAM_INT = 1
private const val PARAM_REAL = 2
private const val PARAM_TEXT = 3
private const val PARAM_BLOB = 4

/**
 * Loads the `sqlx4k_sqlite_cipher` native library exactly once. Implemented per platform:
 * the JVM extracts it from a JAR resource (or a Gradle-provided path) and `System.load`s it,
 * while Android resolves the bundled `.so` via `System.loadLibrary`.
 */
internal expect fun ensureCipherNativeLoaded()

/**
 * Encodes statement parameters into the big-endian buffer consumed by the Rust `params_from_bytes`
 * decoder: `i32 count` followed by, per value, `u8 kind` and its payload. The type switch mirrors
 * the Kotlin/Native `bindParam` so the FFI and JNI paths bind identical values.
 */
internal fun encodeParams(values: List<Any?>): ByteArray {
    val bos = ByteArrayOutputStream()
    val out = DataOutputStream(bos)
    out.writeInt(values.size)
    values.forEach { writeParam(out, it) }
    out.flush()
    return bos.toByteArray()
}

private fun writeStr(out: DataOutputStream, s: String) {
    val bytes = s.encodeToByteArray()
    out.writeInt(bytes.size)
    out.write(bytes)
}

private fun writeText(out: DataOutputStream, s: String) {
    out.writeByte(PARAM_TEXT)
    writeStr(out, s)
}

private fun writeParam(out: DataOutputStream, value: Any?) {
    when (value) {
        null, is TypedNull -> out.writeByte(PARAM_NULL)
        is Boolean -> {
            out.writeByte(PARAM_INT)
            out.writeLong(if (value) 1L else 0L)
        }

        is Byte -> {
            out.writeByte(PARAM_INT)
            out.writeLong(value.toLong())
        }

        is Short -> {
            out.writeByte(PARAM_INT)
            out.writeLong(value.toLong())
        }

        is Int -> {
            out.writeByte(PARAM_INT)
            out.writeLong(value.toLong())
        }

        is Long -> {
            out.writeByte(PARAM_INT)
            out.writeLong(value)
        }

        is Float -> {
            out.writeByte(PARAM_REAL)
            out.writeDouble(value.toDouble())
        }

        is Double -> {
            out.writeByte(PARAM_REAL)
            out.writeDouble(value)
        }

        is String -> writeText(out, value)
        is Char -> writeText(out, value.toString())

        is ByteArray -> {
            out.writeByte(PARAM_BLOB)
            out.writeInt(value.size)
            out.write(value)
        }

        is Instant -> writeText(out, value.toTimestampString())
        is LocalDate -> writeText(out, value.toString())
        is LocalTime -> writeText(out, value.toString())
        is LocalDateTime -> writeText(out, value.toString().replace('T', ' '))
        is Uuid -> writeText(out, value.toString())
        is java.util.UUID -> writeText(out, value.toString())
        is SqlRawLiteral -> writeText(out, value.sql)

        else -> SQLError(
            code = SQLError.Code.MissingValueConverter,
            message = "Cannot bind value of type ${value::class.simpleName} as a SQLite parameter"
        ).raise()
    }
}

/**
 * The decoded form of the byte buffer returned by every [CipherJni] call: the C
 * `Sqlx4kSqliteCipherResult` serialized by the Rust `serialize_result`. Mirrors the Kotlin/Native
 * `Sqlx4kSqliteCipherResult` accessors so the JNI driver code reads like the FFI driver code.
 */
internal class JniResult(
    val error: Int,
    private val errorMessage: String?,
    val rowsAffected: Long,
    val cn: Long,
    val tx: Long,
    val rt: Long,
    private val metadata: ResultSet.Metadata,
    private val rows: List<ResultSet.Row>,
) {
    private fun isError(): Boolean = error >= 0
    private fun toError(): SQLError = SQLError(SQLError.Code.entries[error], errorMessage)

    fun throwIfError() {
        if (isError()) toError().raise()
    }

    fun rtOrError(): Long {
        throwIfError()
        if (rt == 0L) SQLError(SQLError.Code.Pool, "Unexpected behaviour while creating the pool.").raise()
        return rt
    }

    fun cnOrError(): Long {
        throwIfError()
        return cn
    }

    fun txOrError(): Long {
        throwIfError()
        return tx
    }

    fun rowsAffectedOrError(): Long {
        throwIfError()
        return rowsAffected
    }

    fun toResultSet(): ResultSet {
        val error: SQLError? = if (isError()) toError() else null
        return ResultSet(rows, error, metadata)
    }
}

private fun readStr(inp: DataInputStream): String {
    val len = inp.readInt()
    val bytes = ByteArray(len)
    inp.readFully(bytes)
    return bytes.decodeToString()
}

/**
 * Decodes a [CipherJni] result buffer. Inverse of the Rust `serialize_result`; row column
 * names/types are resolved from the schema section by column index, exactly as the Kotlin/Native
 * `toResultSet` does.
 */
internal fun decodeResult(bytes: ByteArray): JniResult {
    val inp = DataInputStream(ByteArrayInputStream(bytes))

    val error = inp.readInt()
    val hasMessage = inp.readByte().toInt()
    val errorMessage = if (hasMessage == 1) readStr(inp) else null
    val rowsAffected = inp.readLong()
    val cn = inp.readLong()
    val tx = inp.readLong()
    val rt = inp.readLong()

    val hasSchema = inp.readByte().toInt()
    val schemaColumns: List<ResultSet.Metadata.Column> = if (hasSchema == 1) {
        val count = inp.readInt()
        List(count) {
            val ordinal = inp.readInt()
            val name = readStr(inp)
            val type = readStr(inp)
            ResultSet.Metadata.Column(ordinal = ordinal, name = name, type = type)
        }
    } else {
        emptyList()
    }

    val rowCount = inp.readInt()
    val rows = List(rowCount) {
        val columnCount = inp.readInt()
        val columns = List(columnCount) { colIndex ->
            val ordinal = inp.readInt()
            val hasValue = inp.readByte().toInt()
            val value = if (hasValue == 1) readStr(inp) else null
            val meta = schemaColumns.getOrNull(colIndex)
            ResultSet.Row.Column(
                ordinal = ordinal,
                name = meta?.name ?: "",
                type = meta?.type ?: "",
                value = value,
            )
        }
        ResultSet.Row(columns)
    }

    return JniResult(
        error = error,
        errorMessage = errorMessage,
        rowsAffected = rowsAffected,
        cn = cn,
        tx = tx,
        rt = rt,
        metadata = ResultSet.Metadata(schemaColumns),
        rows = rows,
    )
}
