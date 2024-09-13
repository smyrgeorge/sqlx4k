package io.github.smyrgeorge.sqlx4k

import io.github.smyrgeorge.sqlx4k.impl.isError
import io.github.smyrgeorge.sqlx4k.impl.throwIfError
import io.github.smyrgeorge.sqlx4k.impl.toError
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.get
import kotlinx.cinterop.pointed
import kotlinx.cinterop.toKString
import sqlx4k.Sqlx4kColumn
import sqlx4k.Sqlx4kResult
import sqlx4k.Sqlx4kRow
import sqlx4k.sqlx4k_free_result

/**
 * The ResultSet class represents a set of results returned from a database query. It supports iteration and
 * provides methods for error checking and conversion.
 *
 * @property ptr The raw pointer to the native result set.
 */
@OptIn(ExperimentalForeignApi::class)
@Suppress("unused", "MemberVisibilityCanBePrivate")
class ResultSet(
    private var ptr: CPointer<Sqlx4kResult>?
) : Iterator<ResultSet.Row>, Iterable<ResultSet.Row>, AutoCloseable {

    private var current: Int = 0
    private var result: Sqlx4kResult? = ptr?.pointed
        ?: error("Could not extract the value from the raw pointer (null).")

    /**
     * Checks if the current result is an error.
     *
     * @return True if the result represents an error, false otherwise.
     */
    fun isError(): Boolean = result!!.isError()
    /**
     * Converts the current result of the `ResultSet` to a `DbError`.
     *
     * @return The `DbError` representation of the current result.
     */
    fun toError(): DbError = result!!.toError()

    fun getRaw(): Sqlx4kResult = result
        ?: error("Resulted already freed (null).")

    fun getRawPtr(): CPointer<Sqlx4kResult> = ptr
        ?: error("Resulted already freed (null).")

    /**
     * Represents a row in a SQL result set.
     *
     * @property row The internal representation of the SQL row.
     */
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

        /**
         * Represents a column in an SQL database row.
         *
         * @property name The name of the column.
         * @property column The internal column representation from the Sqlx4k library.
         */
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

    /**
     * Checks if there are more rows available in the result set.
     *
     * @return True if there are more rows, false otherwise.
     */
    override fun hasNext(): Boolean {
        if (current == 0) getRaw().throwIfError()
        val hasNext = current < getRaw().size
        if (!hasNext) close()
        return hasNext
    }

    /**
     * Retrieves the next row in the result set.
     *
     * @return The next row in the result set.
     * @throws IllegalStateException if the index is out of bounds or if an error occurs while fetching the raw data.
     */
    override fun next(): Row {
        if (current == 0) getRaw().throwIfError()
        if (current < 0 || current >= getRaw().size) error("Rows :: Out of bounds (index $current)")
        return Row(getRaw().rows!![current++])
    }

    /**
     * Closes the current `ResultSet`, freeing any resources associated with it.
     *
     * This method releases the resources allocated for the `ResultSet`. Specifically, it sets the
     * internal result and pointer to null and frees the native SQLx4k result object.
     *
     * If the result has already been closed (i.e., it's already null), calling this method has no effect.
     */
    override fun close() {
        if (result == null) return
        result = null
        sqlx4k_free_result(ptr)
        ptr = null
    }

    /**
     * Returns an iterator over elements of type `Row`.
     *
     * @return Iterator<Row> for the result set.
     */
    override fun iterator(): Iterator<Row> = this
}
