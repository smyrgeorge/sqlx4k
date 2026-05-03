@file:OptIn(ExperimentalForeignApi::class, ExperimentalTime::class, ExperimentalUuidApi::class)

package io.github.smyrgeorge.sqlx4k.postgres

import io.github.smyrgeorge.sqlx4k.SQLError
import io.github.smyrgeorge.sqlx4k.impl.extensions.toTimestampString
import io.github.smyrgeorge.sqlx4k.impl.types.SqlRawLiteral
import io.github.smyrgeorge.sqlx4k.impl.types.TypedNull
import kotlin.reflect.KClass
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.MemScope
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.allocArrayOf
import kotlinx.cinterop.cstr
import kotlinx.cinterop.get
import kotlinx.cinterop.reinterpret
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import sqlx4k.postgresql.PARAM_BLOB
import sqlx4k.postgresql.PARAM_BOOL
import sqlx4k.postgresql.PARAM_DATE
import sqlx4k.postgresql.PARAM_FLOAT4
import sqlx4k.postgresql.PARAM_FLOAT8
import sqlx4k.postgresql.PARAM_INT2
import sqlx4k.postgresql.PARAM_INT4
import sqlx4k.postgresql.PARAM_INT8
import sqlx4k.postgresql.PARAM_NULL
import sqlx4k.postgresql.PARAM_TEXT
import sqlx4k.postgresql.PARAM_TIME
import sqlx4k.postgresql.PARAM_TIMESTAMP
import sqlx4k.postgresql.PARAM_TIMESTAMPTZ
import sqlx4k.postgresql.PARAM_UUID
import sqlx4k.postgresql.Sqlx4kPostgresParam

/**
 * Allocates an FFI-ready array of [Sqlx4kPostgresParam] inside the receiver [MemScope]
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
): Pair<CPointer<Sqlx4kPostgresParam>?, Int> {
    if (values.isEmpty()) return Pair(null, 0)
    val arr = allocArray<Sqlx4kPostgresParam>(values.size)
    values.forEachIndexed { index, value -> bindParam(arr[index], value) }
    return Pair(arr, values.size)
}

private fun MemScope.bindParam(p: Sqlx4kPostgresParam, value: Any?) {
    when (value) {
        null -> {
            // Untyped null: Rust falls back to a TEXT-typed null which postgres
            // implicit-casts into many target types more permissively than a
            // non-null TEXT expression would allow.
            p.kind = PARAM_NULL
            p.null_type = PARAM_NULL
        }
        is TypedNull -> {
            p.kind = PARAM_NULL
            p.null_type = nullTypeFor(value.type)
        }
        is Boolean -> {
            p.kind = PARAM_BOOL
            p.i64_val = if (value) 1L else 0L
        }

        is Byte -> {
            // Postgres has no `int1`; smallint (int2) is the smallest integer.
            p.kind = PARAM_INT2
            p.i64_val = value.toLong()
        }

        is Short -> {
            p.kind = PARAM_INT2
            p.i64_val = value.toLong()
        }

        is Int -> {
            p.kind = PARAM_INT4
            p.i64_val = value.toLong()
        }

        is Long -> {
            p.kind = PARAM_INT8
            p.i64_val = value
        }

        is Float -> {
            p.kind = PARAM_FLOAT4
            p.f64_val = value.toDouble()
        }

        is Double -> {
            p.kind = PARAM_FLOAT8
            p.f64_val = value
        }

        is String -> {
            p.kind = PARAM_TEXT
            p.text = value.cstr.ptr
        }

        is Char -> {
            p.kind = PARAM_TEXT
            p.text = value.toString().cstr.ptr
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
            // The Kotlin Instant is a UTC moment; the Rust side parses this as
            // `DateTime<Utc>` and binds as TIMESTAMPTZ.
            p.kind = PARAM_TIMESTAMPTZ
            p.text = value.toTimestampString().cstr.ptr
        }

        is LocalDate -> {
            p.kind = PARAM_DATE
            p.text = value.toString().cstr.ptr
        }

        is LocalTime -> {
            p.kind = PARAM_TIME
            p.text = value.toString().cstr.ptr
        }

        is LocalDateTime -> {
            p.kind = PARAM_TIMESTAMP
            p.text = value.toString().replace('T', ' ').cstr.ptr
        }

        is Uuid -> {
            p.kind = PARAM_UUID
            p.text = value.toString().cstr.ptr
        }

        is SqlRawLiteral -> {
            p.kind = PARAM_TEXT
            p.text = value.sql.cstr.ptr
        }

        else -> SQLError(
            code = SQLError.Code.MissingValueConverter,
            message = "Cannot bind value of type ${value::class.simpleName} as a PostgreSQL parameter"
        ).raise()
    }
}

private fun nullTypeFor(type: KClass<*>): Int = when (type) {
    Boolean::class -> PARAM_BOOL
    Byte::class, Short::class -> PARAM_INT2
    Int::class -> PARAM_INT4
    Long::class -> PARAM_INT8
    Float::class -> PARAM_FLOAT4
    Double::class -> PARAM_FLOAT8
    String::class, Char::class -> PARAM_TEXT
    ByteArray::class -> PARAM_BLOB
    LocalDate::class -> PARAM_DATE
    LocalTime::class -> PARAM_TIME
    LocalDateTime::class -> PARAM_TIMESTAMP
    Instant::class -> PARAM_TIMESTAMPTZ
    Uuid::class -> PARAM_UUID
    // Fall back to the "untyped" sentinel; Rust binds None::<String> which
    // postgres can implicit-cast into many target types.
    else -> PARAM_NULL
}
