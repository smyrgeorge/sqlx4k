package io.github.smyrgeorge.sqlx4k

import io.github.smyrgeorge.sqlx4k.impl.extensions.debug
import io.github.smyrgeorge.sqlx4k.impl.extensions.isError
import io.github.smyrgeorge.sqlx4k.impl.extensions.throwIfError
import io.github.smyrgeorge.sqlx4k.impl.extensions.toError
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.get
import kotlinx.cinterop.pointed
import kotlinx.cinterop.toKString
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import sqlx4k.Sqlx4kColumn
import sqlx4k.Sqlx4kResult
import sqlx4k.Sqlx4kRow
import sqlx4k.Sqlx4kSchema
import sqlx4k.sqlx4k_free_result

/**
 * The ResultSet class represents a set of results returned from a database query. It supports iteration and
 * provides methods for error checking and conversion.
 *
 * @property ptr The raw pointer to the native result set.
 */
@Suppress("unused")
@OptIn(ExperimentalForeignApi::class)
class ResultSet(
    private var ptr: CPointer<Sqlx4kResult>?
) : Iterable<ResultSet.Row> {

    /**
     * A mutex used to ensure thread-safe operations when closing the ResultSet.
     *
     * This mutex is utilized within the `close` method to synchronize and safely
     * manage the closing of the ResultSet instance, preventing race conditions
     * and concurrent access issues.
     */
    private val closeMutex = Mutex()

    /**
     * Indicates whether the `ResultSet` has been closed.
     *
     * This variable keeps track of the resource management state for the `ResultSet`.
     * If `true`, it means the `ResultSet` has been closed and its resources have been released.
     * If `false`, the `ResultSet` is still active and its resources are available for use.
     *
     * Used internally to ensure thread-safe access and proper resource handling.
     */
    private var closed: Boolean = false
    private var result: Sqlx4kResult = ptr?.pointed
        ?: error("Could not extract the value from the raw pointer (null).")

    /**
     * Returns the size of the current result.
     *
     * This value is equivalent to the number of rows or elements
     * in the underlying raw [Sqlx4kResult] associated with this `ResultSet`.
     */
    val size: Int get() = getRaw().size

    /**
     * Provides access to the metadata of the result set associated with the `ResultSet`.
     *
     * This lazily initialized property allows users to interact with the metadata,
     * which contains information about the schema of the result set, such as column count and
     * details about each column (e.g., name, ordinal position, and data type).
     */
    val metadata: Metadata by lazy { Metadata(getRaw().schema!!.pointed) }

    /**
     * Checks if the current result is an error.
     *
     * @return True if the result represents an error, false otherwise.
     */
    fun isError(): Boolean = result.isError()

    /**
     * Converts the current result of the [ResultSet] to a [SQLError].
     *
     * @return The [SQLError] representation of the current result.
     */
    internal fun toError(): SQLError = result.toError()

    /**
     * Converts the current [ResultSet] into a Kotlin [Result] object.
     * If the result is an error, it returns a failed [Result] with the error.
     * If the result is successful, it returns a successful [Result] containing the `ResultSet` itself.
     *
     * @return A successful [Result] containing the current [ResultSet] if no error,
     *         or a failed [Result] with the appropriate [SQLError].
     */
    fun toResult(): Result<ResultSetHolder> =
        if (isError()) {
            val error = toError()
            close()
            Result.failure(error)
        } else Result.success(ResultSetHolder(this))

    /**
     * Retrieves the raw `Sqlx4kResult` associated with the `ResultSet`.
     *
     * @return The raw `Sqlx4kResult` if it has not been freed, or throws an error if it has.
     */
    fun getRaw(): Sqlx4kResult = if (closed) error("ResultSed already closed.") else result

    /**
     * Closes the `ResultSet` and releases any associated resources.
     *
     * This method ensures the `ResultSet` is properly closed and any native resources are freed.
     * It is thread-safe and can be accessed concurrently.
     * If the `ResultSet` is already closed, subsequent calls to this method will have no effect.
     */
    internal fun close() {
        val alreadyClosed: Boolean = runBlocking {
            closeMutex.withLock {
                val c = closed
                closed = true
                c
            }
        }

        if (alreadyClosed) return

        sqlx4k_free_result(ptr)
        ptr = null
    }

    /**
     * Returns an iterator over elements of type `Row`.
     *
     * @return Iterator<Row> for the result set.
     */
    override fun iterator(): Iterator<Row> = IteratorImpl(getRaw())

    /**
     * Represents a row in a SQL result set.
     *
     * @property row The internal representation of the SQL row.
     */
    class Row(
        private val row: Sqlx4kRow,
        private val metadata: Metadata
    ) {

        val columns: Map<String, Column> by lazy {
            val map = mutableMapOf<String, Column>()
            repeat(row.size) { index ->
                val raw = row.columns!![index]
                val col = Column(raw, metadata)
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
            if (ordinal < 0 || ordinal >= size) error("Columns :: Out of bounds (index $ordinal)")
            val raw = row.columns!![ordinal]
            return Column(raw, metadata)
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
         * @property column The internal column representation from the Sqlx4k library.
         */
        class Column(
            private val column: Sqlx4kColumn,
            private val metadata: Metadata
        ) {
            /**
             * Retrieves the name of the column at the specified ordinal position.
             *
             * This property provides access to the name of the column
             * as defined in the metadata, based on the column's ordinal index.
             */
            val name: String by lazy { metadata.getColumn(ordinal).name }

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
            val type: String by lazy { metadata.getColumn(ordinal).type }

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
             * Converts the column value to a String.
             *
             * If the value is null, an SQLError with code `CannotDecode` is thrown.
             *
             * @return The string representation of the column value.
             * @throws SQLError if the value is null.
             */
            fun asString(): String = value
                ?: SQLError(SQLError.Code.CannotDecode, "Failed to decode value (null)").ex()

            /**
             * Converts the value of the column to a nullable String.
             *
             * @return The string representation of the column value, or null if the value is not present.
             */
            fun asStringOrNull(): String? = value
        }
    }

    /**
     * The `Metadata` class provides an interface to extract and manage metadata from a `ResultSet`.
     *
     * @property schema The `ResultSet` from which metadata is to be extracted.
     */
    class Metadata(
        private val schema: Sqlx4kSchema
    ) {
        private val columnsByName: Map<String, ColumnMetadata> by lazy {
            // <name, ColumnMetadata>
            val map = mutableMapOf<String, ColumnMetadata>()
            repeat(schema.size) { index ->
                val raw = schema.columns!![index]
                map[raw.name!!.toKString()] =
                    ColumnMetadata(raw.ordinal, raw.name!!.toKString(), raw.kind!!.toKString())
            }
            map
        }

        private val columnsByOrdinal: Map<Int, ColumnMetadata> by lazy {
            // <ordinal, ColumnMetadata>>
            val map = mutableMapOf<Int, ColumnMetadata>()
            repeat(schema.size) { index ->
                val raw = schema.columns!![index]
                map[raw.ordinal] =
                    ColumnMetadata(raw.ordinal, raw.name!!.toKString(), raw.kind!!.toKString())
            }
            map
        }

        /**
         * Retrieves the number of columns of the result set.
         *
         * @return the number of columns in the first row.
         */
        fun getColumnCount(): Int = schema.size

        /**
         * Retrieves the metadata for the column at the specified index.
         *
         * @param index The zero-based index of the column.
         * @return `ColumnMetadata` object containing details about the column.
         * @throws NoSuchElementException If no column exists at the specified index.
         */
        fun getColumn(index: Int): ColumnMetadata =
            columnsByOrdinal[index]
                ?: throw NoSuchElementException("Cannot extract metadata: no column with index '$index'.")

        /**
         * Retrieves the metadata for the column with the specified name.
         *
         * @param name The name of the column.
         * @return `ColumnMetadata` object containing details about the column.
         * @throws NoSuchElementException If no column exists with the specified name.
         */
        fun getColumn(name: String): ColumnMetadata =
            columnsByName[name]
                ?: throw NoSuchElementException("Cannot extract metadata: no column with name '$name'.")

        /**
         * Represents metadata for a single column in a result set.
         *
         * @property ordinal The ordinal position of the column (zero-based index).
         * @property name The name of the column.
         * @property type The data type of the column.
         */
        data class ColumnMetadata(
            val ordinal: Int,
            val name: String,
            val type: String
        )
    }

    /**
     * An implementation of the [Iterator] interface for iterating over rows in a SQL result set.
     *
     * This class is responsible for providing sequential access to each row in a [Sqlx4kResult].
     * It ensures error-checking before accessing rows and enforces bounds checks to prevent invalid access.
     *
     * @property res The underlying SQL result set being iterated.
     */
    class IteratorImpl(
        private val res: Sqlx4kResult
    ) : Iterator<Row> {
        private var current: Int = 0

        /**
         * Checks if there are more rows available in the result set.
         *
         * @return True if there are more rows, false otherwise.
         */
        override fun hasNext(): Boolean {
            if (current == 0) res.throwIfError()
            val hasNext = current < res.size
            return hasNext
        }

        /**
         * Retrieves the next row in the result set.
         *
         * @return The next row in the result set.
         * @throws IllegalStateException if the index is out of bounds or if an error occurs while fetching the raw data.
         */
        override fun next(): Row {
            if (current == 0) res.throwIfError()
            if (current < 0 || current >= res.size) error("Rows :: Out of bounds (index $current)")
            return Row(res.rows!![current++], Metadata(res.schema!!.pointed))
        }
    }
}
