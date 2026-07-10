package io.github.smyrgeorge.sqlx4k

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import assertk.assertions.isSameInstanceAs
import kotlin.test.Test
import kotlin.test.assertFailsWith

class SQLErrorTests {

    // ========================================================================================
    // message formatting
    // ========================================================================================

    @Test
    fun `message renders code and message`() {
        val error = SQLError(SQLError.Code.Database, "boom")
        // Rendered by RuntimeException("[$code] :: $message"); Code.toString() == its name.
        assertThat(error.message).isEqualTo("[Database] :: boom")
    }

    @Test
    fun `null message renders without a trailing separator`() {
        val error = SQLError(SQLError.Code.PoolTimedOut)
        // With no message, the "` :: `" separator/tail is omitted.
        assertThat(error.message).isEqualTo("[PoolTimedOut]")
    }

    // ========================================================================================
    // code / cause retention
    // ========================================================================================

    @Test
    fun `code is retained`() {
        val error = SQLError(SQLError.Code.ConnectionIsClosed, "closed")
        assertThat(error.code).isEqualTo(SQLError.Code.ConnectionIsClosed)
    }

    @Test
    fun `cause is retained when supplied`() {
        val cause = IllegalStateException("root cause")
        val error = SQLError(SQLError.Code.Database, "boom", cause)
        assertThat(error.cause).isSameInstanceAs(cause)
    }

    @Test
    fun `cause is null when not supplied`() {
        val error = SQLError(SQLError.Code.Database, "boom")
        assertThat(error.cause).isNull()
    }

    // ========================================================================================
    // raise()
    // ========================================================================================

    @Test
    fun `raise throws the same instance`() {
        val error = SQLError(SQLError.Code.UnknownError, "nope")
        val thrown = assertFailsWith<SQLError> { error.raise() }
        assertThat(thrown).isSameInstanceAs(error)
        assertThat(thrown.code).isEqualTo(SQLError.Code.UnknownError)
    }

    // ========================================================================================
    // ordinal contract guard
    //
    // The enum carries the comment "IMPORTANT: Do not change the order of the errors."
    // because the underlying FFI layer maps codes by their ordinal. These assertions pin the
    // order so an accidental reorder / insertion is caught by the test suite.
    // ========================================================================================

    @Test
    fun `first code has ordinal zero`() {
        assertThat(SQLError.Code.Database.ordinal).isEqualTo(0)
    }

    @Test
    fun `a handful of key ordinals are pinned`() {
        assertThat(SQLError.Code.Database.ordinal).isEqualTo(0)
        assertThat(SQLError.Code.Migrate.ordinal).isEqualTo(4)
        assertThat(SQLError.Code.Pool.ordinal).isEqualTo(5)
        assertThat(SQLError.Code.CannotDecode.ordinal).isEqualTo(10)
        assertThat(SQLError.Code.UnknownError.ordinal).isEqualTo(SQLError.Code.entries.size - 1)
    }

    @Test
    fun `full code entries order is pinned`() {
        assertThat(SQLError.Code.entries.map { it.name }).containsExactly(
            "Database",
            "PoolTimedOut",
            "PoolClosed",
            "WorkerCrashed",
            "Migrate",
            "Pool",
            "ConnectionIsClosed",
            "TransactionIsClosed",
            "TransactionCommitFailed",
            "TransactionRollbackFailed",
            "CannotDecode",
            "CannotDecodeEnumValue",
            "PositionalParameterOutOfBounds",
            "NamedParameterNotFound",
            "EmptyResultSet",
            "MultipleRowsReturned",
            "RowMismatch",
            "MissingValueConverter",
            "PositionalParameterValueNotSupplied",
            "NamedParameterValueNotSupplied",
            "InvalidIdentifier",
            "UnsafeStringContent",
            "EmptyCollection",
            "UnknownError",
        )
    }
}
