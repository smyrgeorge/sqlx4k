package io.github.smyrgeorge.sqlx4k

/**
 * Represents the result of a SQL query, containing rows and metadata.
 *
 * The `ResultSet` class provides an interface to traverse and interact with query results.
 *
 * @property rows The list of rows in the result set.
 * @property error An optional error associated with the result set.
 * @property metadata Metadata about the result set, including schema and column information.
 */
class ResultSet(
    val rows: List<Row>,
    val error: SQLError?,
    val metadata: Metadata
) : Iterable<ResultSet.Row> {

    /**
     * Represents the total number of rows in the result set.
     *
     * This property provides the size of the `rows` collection, indicating
     * the number of entries contained within the result set.
     */
    val size: Int = rows.size

    /**
     * Checks if the current result is an error.
     *
     * @return True if the result represents an error, false otherwise.
     */
    fun isError(): Boolean = error != null

    /**
     * Throws the current error if one exists.
     *
     * This method checks for the presence of an error within the context and
     * propagates it as an exception if present. If no error is present,
     * the method performs no operation.
     *
     * @return Unit. If an error exists, this method throws an exception and does not return normally.
     */
    fun throwIfError() {
        error?.raise()
    }

    /**
     * Converts the current `ResultSet` into a `Result` object.
     *
     * If the current result is an error, it returns a `Result.failure` with the associated error.
     * Otherwise, it returns a `Result.success` with the current `ResultSet`.
     *
     * @return A `Result<ResultSet>` that is either a success wrapping the current `ResultSet`
     *         or a failure wrapping the associated error.
     */
    fun toResult(): Result<ResultSet> =
        if (isError()) Result.failure(error!!)
        else Result.success(this)

    /**
     * Returns an iterator over elements of type `Row`.
     *
     * @return Iterator<Row> for the result set.
     */
    override fun iterator(): Iterator<Row> = IteratorImpl(this)

    /**
     * Represents a single row in a result set, composed of multiple columns.
     *
     * @property columns The list of columns in this row.
     */
    data class Row(
        private val columns: List<Column>
    ) {

        private val columnsByName: Map<String, Column> by lazy {
            columns.associateBy { it.name }
        }

        /**
         * The number of columns in the SQL row.
         */
        val size: Int get() = columns.size

        /**
         * Retrieves a column from the row by its name.
         *
         * @param name The name of the column to retrieve
         * @return The column corresponding to the given name
         * @throws IllegalArgumentException If no column is found with the specified name
         */
        fun get(name: String): Column =
            columnsByName[name] ?: throw IllegalArgumentException("No column found with name: '$name'")

        /**
         * Retrieves a column from the row by its ordinal index.
         *
         * @param ordinal The zero-based index position of the column in the row
         * @return The column at the specified index position
         * @throws IndexOutOfBoundsException If the ordinal index is negative or exceeds the number of columns
         */
        fun get(ordinal: Int): Column {
            require(ordinal in 0 until size) {
                "Column index out of bounds: index=$ordinal, size=$size"
            }
            return columns[ordinal]
        }

        /**
         * Converts the current row into a metadata representation.
         *
         * This method iterates through all columns in the row, extracts their ordinal, name, and type,
         * and constructs a `Metadata` object that contains a list of corresponding `Metadata.Column` objects.
         *
         * @return A `Metadata` instance containing the metadata of all columns in the row.
         */
        fun toMetadata(): Metadata {
            val columns = List(size) { index ->
                val column = get(index)
                Metadata.Column(ordinal = column.ordinal, name = column.name, type = column.type)
            }
            return Metadata(columns)
        }

        /**
         * Represents a column in a database table.
         *
         * This class provides metadata and access to the value of a column in a database row.
         * It includes properties to retrieve information such as the column's ordinal position,
         * name, type, and value, along with methods to manipulate or retrieve the column's value.
         *
         * @property ordinal The zero-based ordinal position of the column in the table schema.
         * @property name The name of the column.
         * @property type The type of the column as a string representation.
         * @property value The value of the column, or null if the value is not present.
         */
        data class Column(
            val ordinal: Int,
            val name: String,
            val type: String,
            private val value: String?
        ) {
            /**
             * Checks if the column's value is null.
             *
             * This method determines whether the value of the column is absent, returning true
             * if the value is null and false otherwise.
             *
             * @return True if the column value is null; false if the column has a non-null value.
             */
            fun isNull(): Boolean = value == null

            /**
             * Converts the column value to a String.
             *
             * If the value is null, an SQLError with code `CannotDecode` is thrown.
             *
             * @return The string representation of the column value.
             * @throws SQLError if the value is null.
             */
            fun asString(): String = value
                ?: SQLError(SQLError.Code.CannotDecode, "Failed to decode value (null)").raise()

            /**
             * Converts the value of the column to a nullable String.
             *
             * @return The string representation of the column value, or null if the value is not present.
             */
            fun asStringOrNull(): String? = value
        }
    }

    @Suppress("unused")
    class Metadata(
        private val columns: List<Column>
    ) {
        // <name, ColumnMetadata>
        private val columnsByName: Map<String, Column> by lazy {
            columns.associateBy { it.name }
        }

        // <ordinal, ColumnMetadata>
        private val columnsByOrdinal: Map<Int, Column> by lazy {
            columns.associateBy { it.ordinal }
        }

        /**
         * Retrieves the number of columns of the result set.
         *
         * @return the number of columns in the first row.
         */
        fun getColumnCount(): Int = columns.size

        /**
         * Retrieves the metadata for the column at the specified index.
         *
         * @param index The zero-based index of the column.
         * @return `ColumnMetadata` object containing details about the column.
         * @throws NoSuchElementException If no column exists at the specified index.
         */
        fun getColumn(index: Int): Column =
            columnsByOrdinal[index]
                ?: throw NoSuchElementException("Cannot extract metadata: no column with index '$index'.")

        /**
         * Retrieves the metadata for the column with the specified name.
         *
         * @param name The name of the column.
         * @return `ColumnMetadata` object containing details about the column.
         * @throws NoSuchElementException If no column exists with the specified name.
         */
        fun getColumn(name: String): Column =
            columnsByName[name]
                ?: throw NoSuchElementException("Cannot extract metadata: no column with name '$name'.")

        /**
         * Represents metadata for a single column in a result set.
         *
         * @property ordinal The ordinal position of the column (zero-based index).
         * @property name The name of the column.
         * @property type The data type of the column.
         */
        data class Column(
            val ordinal: Int,
            val name: String,
            val type: String
        )
    }

    /**
     * Implementation of the `Iterator` interface for iterating over rows in a `ResultSet`.
     *
     * This class provides mechanisms to traverse the rows in a result set, ensuring that
     * any errors in the result set are detected and propagated appropriately.
     *
     * @constructor Creates an instance of the iterator for a given `ResultSet`.
     * @param rs The `ResultSet` over which this iterator will operate.
     */
    class IteratorImpl(
        private val rs: ResultSet
    ) : Iterator<Row> {
        private var current: Int = 0

        /**
         * Checks if there are more rows available in the result set.
         *
         * @return True if there are more rows, false otherwise.
         */
        override fun hasNext(): Boolean {
            if (current == 0) rs.throwIfError()
            return current < rs.size
        }

        /**
         * Retrieves the next row in the result set.
         *
         * @return The next row in the result set.
         * @throws IllegalStateException if the index is out of bounds, or if an error occurs while fetching the raw data.
         */
        override fun next(): Row {
            if (current == 0) rs.throwIfError()
            if (current < 0 || current >= rs.size) error("Rows :: Out of bounds (index $current)")
            return rs.rows[current++]
        }
    }
}
