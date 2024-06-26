package io.github.smyrgeorge.sqlx4k

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.get
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.toKString
import librust_lib.Sqlx4kColumn
import librust_lib.Sqlx4kRow

@OptIn(ExperimentalForeignApi::class)
@Suppress("unused", "MemberVisibilityCanBePrivate", "SpellCheckingInspection")
class Sqlx4k {
    class Row(
        private val row: Sqlx4kRow
    ) {

        val columns: Map<String, Column> = run {
            val map = mutableMapOf<String, Column>()
            repeat(row.size) { index ->
                val raw = row.columns!![index]
                val col = Column(raw.name!!.toKString(), raw)
                map[col.name] = col
            }
            map
        }

        val size get() = row.size
        fun get(name: String): Column = columns[name]!!
        fun debug(): String = row.debug()

        @OptIn(ExperimentalForeignApi::class)
        private fun Sqlx4kRow.debug(prefix: String = ""): String = buildString {
            append("\n$prefix[Sqlx4kPgRow]")
            append("\n${prefix}size: $size")
            columns?.let {
                repeat(size) { index -> append(it[index].debug(prefix = "$prefix    ")) }
            }
        }

        @OptIn(ExperimentalForeignApi::class)
        private fun Sqlx4kColumn.debug(prefix: String = ""): String = buildString {
            append("\n$prefix[Sqlx4kPgColumn]")
            append("\n${prefix}ordinal: $ordinal")
            append("\n${prefix}name: ${name?.toKString()}")
            append("\n${prefix}kind: $kind")
            append("\n${prefix}size: $size")
            append("\n${prefix}value: ${value?.readBytes(size)?.toKString()}")
        }

        class Column(
            val name: String,
            private val column: Sqlx4kColumn
        ) {
            val ordinal: Int get() = column.ordinal
            val type: Type get() = Type.entries[column.kind]
            val value: String get() = column.value!!.readBytes(column.size).toKString()

            enum class Type {
                BOOL,
                INT2,
                INT4,
                INT8,
                FLOAT4,
                FLOAT8,
                NUMERIC,
                CHAR,
                VARCHAR,
                TEXT,
                TIMESTAMP,
                TIMESTAMPTZ,
                DATE,
                TIME,
                BYTEA,
                UUID,
                JSON,
                JSONB
            }
        }
    }

    class Error(
        val code: Int,
        val message: String? = null,
    ) {
        fun isError(): Boolean = code > 0

        fun throwIfError() {
            if (isError()) error("[$code] $message")
        }
    }
}
