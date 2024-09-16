package io.github.smyrgeorge.sqlx4k

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

    /**
     * Converts the current `ResultSet` into a Kotlin `Result` object.
     * If the result is an error, it returns a failed `Result` with the error.
     * If the result is successful, it returns a successful `Result` containing the `ResultSet` itself.
     *
     * @return A successful `Result` containing the current `ResultSet` if no error,
     *         or a failed `Result` with the appropriate `DbError`.
     */
    fun toKotlinResult(): Result<ResultSet> =
        if (isError()) {
            val error = toError()
            close()
            Result.failure(error)
        } else Result.success(this)

    /**
     * Retrieves the raw `Sqlx4kResult` associated with the `ResultSet`.
     *
     * @return The raw `Sqlx4kResult` if it has not been freed, or throws an error if it has.
     */
    fun getRaw(): Sqlx4kResult = result
        ?: error("Resulted already freed (null).")

    /**
     * Retrieves the raw pointer to the `Sqlx4kResult` associated with the `ResultSet`.
     *
     * @return The raw `CPointer<Sqlx4kResult>` if it has not been freed, or throws an error if it has.
     */
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

        /**
         * The number of columns in the SQL row.
         */
        val size: Int get() = row.size

        /**
         * Retrieves a column from the row by its name.
         *
         * @param name The name of the column.
         * @return The column corresponding to the given name.
         */
        fun get(name: String): Column = columns[name]!!

        /**
         * Retrieves a column from the row by its ordinal index.
         *
         * @param ordinal The ordinal position of the column in the row.
         * @return The column corresponding to the provided ordinal index.
         * @throws IllegalArgumentException if the ordinal is out of bounds.
         */
        fun get(ordinal: Int): Column {
            if (ordinal < 0 || ordinal >= columns.size) error("Columns :: Out of bounds (index $ordinal)")
            val raw = row.columns!![ordinal]
            return Column(raw.name!!.toKString(), raw)
        }

        /**
         * Provides a debug representation of the current row.
         *
         * @return A debug string detailing the row's structure and content.
         */
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
            /**
             * Retrieves the ordinal position of this column within the database table.
             *
             * The ordinal position is a zero-based index that indicates the column's
             * position among other columns in the table schema.
             */
            val ordinal: Int get() = column.ordinal

            /**
             * Retrieves the type of the column as a String.
             *
             * This property fetches the `kind` of the internal column representation
             * and converts it to a Kotlin String.
             *
             * @return The type of the column in String format.
             */
            val type: String get() = column.kind!!.toKString()

            /**
             * Retrieves the value of the column as a nullable String.
             *
             * This property accesses the internal `value` of the SQL column
             * provided by the `Sqlx4k` library and converts it to a Kotlin string.
             *
             * @return The value of the column, or null if the column's value is null.
             */
            val value: String? get() = column.value?.toKString()

            /**
             * Converts the column value to a ByteArray by interpreting it as a hexadecimal string.
             *
             * This function assumes that the column value (if present) is prefixed with "\\x", which is removed before conversion.
             * The remaining string is then processed as a hexadecimal representation and converted into a ByteArray.
             *
             * @return The byte array representation of the column value, or null if the value is absent.
             */
            @OptIn(ExperimentalStdlibApi::class)
            fun valueAsByteArray(): ByteArray? = column.value?.toKString()
                ?.removePrefix("\\x")
                ?.hexToByteArray()
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
