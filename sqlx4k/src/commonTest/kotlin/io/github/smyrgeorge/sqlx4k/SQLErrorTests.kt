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
    // driver-reported details (sqlState / nativeCode / kind)
    // ========================================================================================

    @Test
    fun `driver details are absent by default`() {
        val error = SQLError(SQLError.Code.Database, "boom")
        assertThat(error.sqlState).isNull()
        assertThat(error.nativeCode).isNull()
        assertThat(error.kind).isEqualTo(SQLError.Kind.Other)
    }

    @Test
    fun `driver details are retained when supplied`() {
        val error = SQLError(
            code = SQLError.Code.Database,
            message = "Duplicate entry '1' for key 'PRIMARY'",
            sqlState = "23000",
            nativeCode = 1062,
            kind = SQLError.Kind.UniqueViolation,
        )
        assertThat(error.sqlState).isEqualTo("23000")
        assertThat(error.nativeCode).isEqualTo(1062)
        assertThat(error.kind).isEqualTo(SQLError.Kind.UniqueViolation)
        // The rendered message is not affected by the extra details.
        assertThat(error.message).isEqualTo("[Database] :: Duplicate entry '1' for key 'PRIMARY'")
    }

    @Test
    fun `positional construction is still supported`() {
        // Existing call sites pass (code, message, cause) positionally; the new properties are trailing defaults.
        val cause = IllegalStateException("root cause")
        val error = SQLError(SQLError.Code.Database, "boom", cause)
        assertThat(error.cause).isSameInstanceAs(cause)
        assertThat(error.kind).isEqualTo(SQLError.Kind.Other)
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
    fun `full kind entries order is pinned`() {
        // `Kind` crosses the FFI boundary by ordinal as well.
        assertThat(SQLError.Kind.entries.map { it.name }).containsExactly(
            "UniqueViolation",
            "ForeignKeyViolation",
            "NotNullViolation",
            "CheckViolation",
            "ExclusionViolation",
            "Deadlock",
            "SerializationFailure",
            "LockTimeout",
            "Other",
        )
        assertThat(SQLError.Kind.Other.ordinal).isEqualTo(SQLError.Kind.entries.size - 1)
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
            "OptimisticLockFailed",
            "UnknownError",
        )
    }
}
