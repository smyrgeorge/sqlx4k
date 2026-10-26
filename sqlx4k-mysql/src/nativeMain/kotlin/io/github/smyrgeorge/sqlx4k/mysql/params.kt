@file:OptIn(ExperimentalForeignApi::class)

package io.github.smyrgeorge.sqlx4k.mysql

import io.github.smyrgeorge.sqlx4k.SQLError
import io.github.smyrgeorge.sqlx4k.impl.extensions.toTimeString
import io.github.smyrgeorge.sqlx4k.impl.extensions.toTimestampString
import io.github.smyrgeorge.sqlx4k.impl.types.SqlRawLiteral
import io.github.smyrgeorge.sqlx4k.impl.types.TypedNull
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.MemScope
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.allocArrayOf
import kotlinx.cinterop.get
import kotlinx.cinterop.reinterpret
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import sqlx4k.mysql.PARAM_BLOB
import sqlx4k.mysql.PARAM_INT
import sqlx4k.mysql.PARAM_NULL
import sqlx4k.mysql.PARAM_REAL
import sqlx4k.mysql.PARAM_TEXT
import sqlx4k.mysql.Sqlx4kMysqlParam
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Allocates an FFI-ready array of [Sqlx4kMysqlParam] inside the receiver [MemScope]
 * for the supplied [values] (as produced by `Statement.NativeQuery.values`).
 *
 * Returns a pair of (array pointer, length). The pointer is null when [values] is empty,
 * which the Rust side treats as "no parameters". All allocations live for the lifetime
 * of the surrounding `memScoped { }` block; the Rust side copies the data into owned
 * buffers before its async task is spawned, so the scope can be released as soon as
 * the FFI call returns.
 */
internal fun MemScope.allocParams(
    values: List<Any?>
): Pair<CPointer<Sqlx4kMysqlParam>?, Int> {
    if (values.isEmpty()) return Pair(null, 0)
    val arr = allocArray<Sqlx4kMysqlParam>(values.size)
    values.forEachIndexed { index, value -> bindParam(arr[index], value) }
    return Pair(arr, values.size)
}

private fun MemScope.bindParam(p: Sqlx4kMysqlParam, value: Any?) {
    when (value) {
        null -> p.kind = PARAM_NULL
        is TypedNull -> p.kind = PARAM_NULL
        is Boolean -> {
            p.kind = PARAM_INT
            p.i64_val = if (value) 1L else 0L
        }

        is Byte -> {
            p.kind = PARAM_INT
            p.i64_val = value.toLong()
        }

        is Short -> {
            p.kind = PARAM_INT
            p.i64_val = value.toLong()
        }

        is Int -> {
            p.kind = PARAM_INT
            p.i64_val = value.toLong()
        }

        is Long -> {
            p.kind = PARAM_INT
            p.i64_val = value
        }

        is Float -> {
            p.kind = PARAM_REAL
            p.f64_val = value.toDouble()
        }

        is Double -> {
            p.kind = PARAM_REAL
            p.f64_val = value
        }

        is String -> {
            p.kind = PARAM_TEXT
            setText(p, value)
        }

        is Char -> {
            p.kind = PARAM_TEXT
            setText(p, value.toString())
        }

        is ByteArray -> {
            p.kind = PARAM_BLOB
            if (value.isEmpty()) {
                p.blob = null
                p.blob_len = 0
            } else {
                p.blob = allocArrayOf(value).reinterpret()
                p.blob_len = value.size
            }
        }

        is Instant -> {
            p.kind = PARAM_TEXT
            setText(p, value.toTimestampString())
        }

        is LocalDate -> {
            p.kind = PARAM_TEXT
            setText(p, value.toString())
        }

        is LocalTime -> {
            p.kind = PARAM_TEXT
            setText(p, value.toTimeString())
        }

        is LocalDateTime -> {
            p.kind = PARAM_TEXT
            setText(p, value.toTimestampString())
        }

        is Uuid -> {
            p.kind = PARAM_TEXT
            setText(p, value.toString())
        }

        is SqlRawLiteral -> {
            p.kind = PARAM_TEXT
            setText(p, value.sql)
        }

        else -> SQLError(
            code = SQLError.Code.MissingValueConverter,
            message = "Cannot bind value of type ${value::class.simpleName} as a MySQL parameter"
        ).raise()
    }
}

/** Passes [value] as UTF-8 with its length, not as a C string: a string may contain NUL, at which a C string is cut. */
private fun MemScope.setText(p: Sqlx4kMysqlParam, value: String) {
    val bytes = value.encodeToByteArray()
    p.text = if (bytes.isEmpty()) null else allocArrayOf(bytes)
    p.text_len = bytes.size
}
