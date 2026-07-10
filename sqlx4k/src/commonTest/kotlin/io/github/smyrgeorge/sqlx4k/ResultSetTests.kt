package io.github.smyrgeorge.sqlx4k

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNotEqualTo
import assertk.assertions.isNull
import assertk.assertions.isTrue
import kotlin.test.Test
import kotlin.test.assertFailsWith

class ResultSetTests {

    // ========================================================================================
    // Helpers
    // ========================================================================================

    private fun col(ordinal: Int, name: String, value: String?, type: String = "TEXT"): ResultSet.Row.Column =
        ResultSet.Row.Column(ordinal, name, type, value)

    private fun row(vararg cols: ResultSet.Row.Column): ResultSet.Row =
        ResultSet.Row(cols.toList())

    private val emptyMeta = ResultSet.Metadata(emptyList())

    private fun resultSet(rows: List<ResultSet.Row>, error: SQLError? = null): ResultSet =
        ResultSet(rows, error, emptyMeta)

    // ========================================================================================
    // Row.Column – isNull
    // ========================================================================================

    @Test
    fun `Column isNull is true when value and bytes are both null`() {
        val c = ResultSet.Row.Column(0, "c", "TEXT", null, null)
        assertThat(c.isNull()).isTrue()
    }

    @Test
    fun `Column isNull is false when value is present`() {
        val c = ResultSet.Row.Column(0, "c", "TEXT", "hello", null)
        assertThat(c.isNull()).isFalse()
    }

    @Test
    fun `Column isNull is false when only bytes are present`() {
        val c = ResultSet.Row.Column(0, "c", "BLOB", null, byteArrayOf(1, 2, 3))
        assertThat(c.isNull()).isFalse()
    }

    // ========================================================================================
    // Row.Column – asString / asStringOrNull
    // ========================================================================================

    @Test
    fun `Column asString returns the value when non-null`() {
        val c = col(0, "c", "hello")
        assertThat(c.asString()).isEqualTo("hello")
    }

    @Test
    fun `Column asString throws SQLError with CannotDecode when value is null`() {
        val c = col(0, "c", null)
        val e = assertFailsWith<SQLError> { c.asString() }
        assertThat(e.code).isEqualTo(SQLError.Code.CannotDecode)
        assertThat(e.message).isEqualTo("[CannotDecode] :: Failed to decode value (null)")
    }

    @Test
    fun `Column asString decodes bytes when only the binary form is present`() {
        // asString()/asStringOrNull() decode raw bytes as UTF-8 when value is null but bytes are
        // present, so they agree with isNull() (which is false here) instead of throwing.
        val c = ResultSet.Row.Column(0, "c", "BLOB", null, "hi".encodeToByteArray())
        assertThat(c.isNull()).isFalse()
        assertThat(c.asString()).isEqualTo("hi")
        assertThat(c.asStringOrNull()).isEqualTo("hi")
    }

    @Test
    fun `Column asStringOrNull returns value or null`() {
        assertThat(col(0, "c", "v").asStringOrNull()).isEqualTo("v")
        assertThat(col(0, "c", null).asStringOrNull()).isNull()
    }

    // ========================================================================================
    // Row.Column – equals / hashCode
    // ========================================================================================

    @Test
    fun `Column equals and hashCode consider bytes`() {
        // equals()/hashCode() compare bytes by content: different payloads are unequal, identical equal.
        val a = ResultSet.Row.Column(0, "c", "BLOB", null, byteArrayOf(1, 2, 3))
        val b = ResultSet.Row.Column(0, "c", "BLOB", null, byteArrayOf(9, 9, 9))
        val c = ResultSet.Row.Column(0, "c", "BLOB", null, byteArrayOf(1, 2, 3))
        assertThat(a).isNotEqualTo(b)
        assertThat(a).isEqualTo(c)
        assertThat(a.hashCode()).isEqualTo(c.hashCode())
    }

    @Test
    fun `Column equals distinguishes columns with different values`() {
        val a = col(0, "c", "x")
        val b = col(0, "c", "y")
        assertThat(a).isNotEqualTo(b)
    }

    // ========================================================================================
    // Row
    // ========================================================================================

    @Test
    fun `Row size reflects column count`() {
        val r = row(col(0, "id", "1"), col(1, "name", "alice"))
        assertThat(r.size).isEqualTo(2)
    }

    @Test
    fun `Row get by name returns matching column`() {
        val r = row(col(0, "id", "1"), col(1, "name", "alice"))
        assertThat(r.get("name").asString()).isEqualTo("alice")
    }

    @Test
    fun `Row get by missing name throws IllegalArgumentException`() {
        val r = row(col(0, "id", "1"))
        val e = assertFailsWith<IllegalArgumentException> { r.get("missing") }
        assertThat(e.message).isEqualTo("No column found with name: 'missing'")
    }

    @Test
    fun `Row get by ordinal returns column at position`() {
        val r = row(col(0, "id", "1"), col(1, "name", "alice"))
        assertThat(r.get(0).name).isEqualTo("id")
        assertThat(r.get(1).name).isEqualTo("name")
    }

    @Test
    fun `Row get by negative ordinal throws IllegalArgumentException`() {
        val r = row(col(0, "id", "1"), col(1, "name", "alice"))
        val e = assertFailsWith<IllegalArgumentException> { r.get(-1) }
        assertThat(e.message).isEqualTo("Column index out of bounds: index=-1, size=2")
    }

    @Test
    fun `Row get by too-high ordinal throws IllegalArgumentException`() {
        val r = row(col(0, "id", "1"), col(1, "name", "alice"))
        val e = assertFailsWith<IllegalArgumentException> { r.get(2) }
        assertThat(e.message).isEqualTo("Column index out of bounds: index=2, size=2")
    }

    @Test
    fun `Row get by name returns last column when names duplicate`() {
        // associateBy keeps the LAST entry for duplicate keys, so get(name) resolves to the
        // second "dup" column; both remain individually reachable by ordinal.
        val r = row(col(0, "dup", "first"), col(1, "dup", "second"))
        assertThat(r.get("dup").asString()).isEqualTo("second")
        assertThat(r.get(0).asString()).isEqualTo("first")
        assertThat(r.get(1).asString()).isEqualTo("second")
        assertThat(r.size).isEqualTo(2)
    }

    @Test
    fun `Row toMetadata reflects all columns`() {
        val r = row(
            col(0, "id", "1", type = "INT"),
            col(1, "name", "alice", type = "TEXT"),
        )
        val md = r.toMetadata()
        assertThat(md.getColumnCount()).isEqualTo(2)
        assertThat(md.getColumn(0).name).isEqualTo("id")
        assertThat(md.getColumn(0).type).isEqualTo("INT")
        assertThat(md.getColumn("name").ordinal).isEqualTo(1)
        assertThat(md.getColumn("name").type).isEqualTo("TEXT")
    }

    // ========================================================================================
    // Metadata
    // ========================================================================================

    @Test
    fun `Metadata getColumnCount returns number of columns`() {
        val md = ResultSet.Metadata(
            listOf(
                ResultSet.Metadata.Column(0, "id", "INT"),
                ResultSet.Metadata.Column(1, "name", "TEXT"),
            )
        )
        assertThat(md.getColumnCount()).isEqualTo(2)
    }

    @Test
    fun `Metadata getColumn by index returns matching column`() {
        val md = ResultSet.Metadata(
            listOf(
                ResultSet.Metadata.Column(0, "id", "INT"),
                ResultSet.Metadata.Column(1, "name", "TEXT"),
            )
        )
        assertThat(md.getColumn(0).name).isEqualTo("id")
        assertThat(md.getColumn(1).name).isEqualTo("name")
    }

    @Test
    fun `Metadata getColumn by missing index throws NoSuchElementException`() {
        val md = ResultSet.Metadata(listOf(ResultSet.Metadata.Column(0, "id", "INT")))
        val e = assertFailsWith<NoSuchElementException> { md.getColumn(5) }
        assertThat(e.message).isEqualTo("Cannot extract metadata: no column with index '5'.")
    }

    @Test
    fun `Metadata getColumn by name returns matching column`() {
        val md = ResultSet.Metadata(
            listOf(
                ResultSet.Metadata.Column(0, "id", "INT"),
                ResultSet.Metadata.Column(1, "name", "TEXT"),
            )
        )
        assertThat(md.getColumn("name").ordinal).isEqualTo(1)
        assertThat(md.getColumn("name").type).isEqualTo("TEXT")
    }

    @Test
    fun `Metadata getColumn by missing name throws NoSuchElementException`() {
        val md = ResultSet.Metadata(listOf(ResultSet.Metadata.Column(0, "id", "INT")))
        val e = assertFailsWith<NoSuchElementException> { md.getColumn("nope") }
        assertThat(e.message).isEqualTo("Cannot extract metadata: no column with name 'nope'.")
    }

    @Test
    fun `Metadata getColumn by index uses list position`() {
        // getColumn(index) is a zero-based list position, independent of each column's `ordinal` field.
        val md = ResultSet.Metadata(
            listOf(
                ResultSet.Metadata.Column(10, "a", "INT"),
                ResultSet.Metadata.Column(20, "b", "TEXT"),
            )
        )
        assertThat(md.getColumn(0).name).isEqualTo("a")
        assertThat(md.getColumn(1).name).isEqualTo("b")
        val e = assertFailsWith<NoSuchElementException> { md.getColumn(2) }
        assertThat(e.message).isEqualTo("Cannot extract metadata: no column with index '2'.")
    }

    // ========================================================================================
    // ResultSet – size / error state
    // ========================================================================================

    @Test
    fun `ResultSet size reflects row count`() {
        val r = resultSet(listOf(row(col(0, "id", "1")), row(col(0, "id", "2"))))
        assertThat(r.size).isEqualTo(2)
    }

    @Test
    fun `ResultSet isError is false without error`() {
        assertThat(resultSet(emptyList()).isError()).isFalse()
    }

    @Test
    fun `ResultSet isError is true with error`() {
        val err = SQLError(SQLError.Code.Database, "boom")
        assertThat(resultSet(emptyList(), err).isError()).isTrue()
    }

    @Test
    fun `ResultSet throwIfError does nothing without error`() {
        // Should simply return without throwing.
        resultSet(listOf(row(col(0, "id", "1")))).throwIfError()
    }

    @Test
    fun `ResultSet throwIfError raises the error`() {
        val err = SQLError(SQLError.Code.Database, "boom")
        val r = resultSet(emptyList(), err)
        val e = assertFailsWith<SQLError> { r.throwIfError() }
        assertThat(e.code).isEqualTo(SQLError.Code.Database)
        assertThat(e.message).isEqualTo("[Database] :: boom")
        assertThat(e === err).isTrue()
    }

    // ========================================================================================
    // ResultSet – toResult
    // ========================================================================================

    @Test
    fun `ResultSet toResult returns success without error`() {
        val r = resultSet(listOf(row(col(0, "id", "1"))))
        val result = r.toResult()
        assertThat(result.isSuccess).isTrue()
        assertThat(result.getOrNull() === r).isTrue()
    }

    @Test
    fun `ResultSet toResult returns failure with error`() {
        val err = SQLError(SQLError.Code.Database, "boom")
        val r = resultSet(emptyList(), err)
        val result = r.toResult()
        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull() === err).isTrue()
    }

    // ========================================================================================
    // ResultSet – iteration
    // ========================================================================================

    @Test
    fun `ResultSet iterates rows in order`() {
        val r = resultSet(
            listOf(
                row(col(0, "v", "a")),
                row(col(0, "v", "b")),
                row(col(0, "v", "c")),
            )
        )
        val values = r.map { it.get(0).asString() }
        assertThat(values).containsExactly("a", "b", "c")
    }

    @Test
    fun `ResultSet iteration over empty result set yields nothing`() {
        val r = resultSet(emptyList())
        assertThat(r.toList()).isEmpty()
    }

    @Test
    fun `ResultSet iteration over error result set throws on first hasNext`() {
        // The error surfaces on the very first hasNext() (current == 0), even though rows exist,
        // so a plain for-loop over an error result set throws immediately.
        val err = SQLError(SQLError.Code.Database, "boom")
        val r = resultSet(listOf(row(col(0, "v", "a"))), err)

        var seen = 0
        val e = assertFailsWith<SQLError> {
            for (rowItem in r) {
                seen += rowItem.size // never reached
            }
        }
        assertThat(e.code).isEqualTo(SQLError.Code.Database)
        assertThat(seen).isEqualTo(0)

        // Same behavior when calling hasNext() directly on a fresh iterator.
        val e2 = assertFailsWith<SQLError> { r.iterator().hasNext() }
        assertThat(e2.code).isEqualTo(SQLError.Code.Database)
    }

    @Test
    fun `ResultSet iterator next on error result set throws`() {
        val err = SQLError(SQLError.Code.Database, "boom")
        val r = resultSet(listOf(row(col(0, "v", "a"))), err)
        val e = assertFailsWith<SQLError> { r.iterator().next() }
        assertThat(e.code).isEqualTo(SQLError.Code.Database)
    }

    @Test
    fun `ResultSet iterator next past end throws IllegalStateException`() {
        val r = resultSet(listOf(row(col(0, "v", "a"))))
        val it = r.iterator()
        assertThat(it.hasNext()).isTrue()
        it.next() // consume the only row
        assertThat(it.hasNext()).isFalse()
        val e = assertFailsWith<IllegalStateException> { it.next() }
        assertThat(e.message).isEqualTo("Rows :: Out of bounds (index 1)")
    }

    @Test
    fun `ResultSet iterator next on empty result set throws IllegalStateException`() {
        val r = resultSet(emptyList())
        val it = r.iterator()
        assertThat(it.hasNext()).isFalse()
        val e = assertFailsWith<IllegalStateException> { it.next() }
        assertThat(e.message).isEqualTo("Rows :: Out of bounds (index 0)")
    }
}
