package io.github.smyrgeorge.sqlx4k.postgres

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.get
import kotlinx.cinterop.toKString
import librust_lib.Sqlx4kColumn
import librust_lib.Sqlx4kRow

@OptIn(ExperimentalForeignApi::class)
@Suppress("unused", "MemberVisibilityCanBePrivate")
interface ResultSet {
    class Row(
        private val row: Sqlx4kRow
    ) {

        val columns: Map<String, Column> by lazy {
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
        fun get(ordinal: Int): Column {
            if (ordinal < 0 || ordinal >= columns.size) error("Columns :: Out of bounds (index $ordinal)")
            val raw = row.columns!![ordinal]
            return Column(raw.name!!.toKString(), raw)
        }

        fun debug(): String = row.debug()

        class Column(
            val name: String,
            private val column: Sqlx4kColumn
        ) {
            val ordinal: Int get() = column.ordinal
            val type: String get() = column.kind!!.toKString()
            val value: String get() = column.value!!.toKString()

            @OptIn(ExperimentalStdlibApi::class)
            fun valueAsByteArray(): ByteArray = column.value!!.toKString()
                .removePrefix("\\x")
                .hexToByteArray()
        }

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
            append("\n${prefix}name: ${name?.toKString() ?: "<EMPTY>"}")
            append("\n${prefix}kind: ${kind?.toKString() ?: "<EMPTY>"}")
            append("\n${prefix}value: ${value?.toKString() ?: "<EMPTY>"}")
        }
    }

    class Error(
        val code: Code,
        message: String? = null,
    ) : RuntimeException("[$code] :: $message") {
        fun ex(): Nothing = throw this

        enum class Code {
            // Error from the underlying driver:
            Database,
            PoolTimedOut,
            PoolClosed,
            WorkerCrashed,

            // Other errors:
            NamedParameterTypeNotSupported,
            NamedParameterValueNotSupplied
        }
    }
}
