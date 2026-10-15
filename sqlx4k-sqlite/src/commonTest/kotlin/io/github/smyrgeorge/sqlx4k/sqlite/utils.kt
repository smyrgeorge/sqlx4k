package io.github.smyrgeorge.sqlx4k.sqlite

import io.github.smyrgeorge.sqlx4k.ConnectionPool
import io.github.smyrgeorge.sqlx4k.ResultSet
import io.github.smyrgeorge.sqlx4k.ValueEncoder
import io.github.smyrgeorge.sqlx4k.ValueEncoderRegistry
import io.github.smyrgeorge.sqlx4k.impl.extensions.asInt
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

/** The platform the tests run on, for the few behaviours that differ between the drivers. */
internal enum class TestPlatform { Jvm, Native, Android }

internal expect val testPlatform: TestPlatform

internal expect val isWindows: Boolean

/**
 * Base class of the database tests. On Android it runs them under Robolectric, since the driver needs a
 * Context; JUnit's `@RunWith` is inherited, so the test classes need no annotation of their own.
 */
expect abstract class SQLiteTestBase()

/** An absolute directory the tests may create database files in. */
internal expect val testDirectory: String

/** Opens [url] with the platform's driver (Android needs a Context). */
internal expect fun openSQLite(
    url: String,
    options: ConnectionPool.Options,
    encoders: ValueEncoderRegistry = ValueEncoderRegistry(),
): ISQLite

/** Opens an empty database file named after the suite, or an in-memory database. */
internal fun testSQLite(
    name: String,
    options: ConnectionPool.Options = ConnectionPool.Options.builder().maxConnections(2).build(),
    encoders: ValueEncoderRegistry = ValueEncoderRegistry(),
    inMemory: Boolean = false,
): ISQLite {
    if (inMemory) return openSQLite("sqlite::memory:", options, encoders)
    val path = "$testDirectory/$name.db"
    listOf("", "-wal", "-shm", "-journal").forEach { SystemFileSystem.delete(Path("$path$it"), mustExist = false) }
    return openSQLite("sqlite://$path", options, encoders)
}

// ---- Custom types for testing encoder resolution ----

/** A simple custom type that encodes to its [amount] as an Int. */
data class Money(val amount: Int, val currency: String)

object MoneyEncoder : ValueEncoder<Money> {
    override fun encode(value: Money): Any = value.amount
    override fun decode(value: ResultSet.Row.Column): Money =
        Money(value.asInt(), "USD")
}

/** A simple custom type that encodes to its [name] as a String. */
data class Tag(val name: String)

object TagEncoder : ValueEncoder<Tag> {
    override fun encode(value: Tag): Any = value.name
    override fun decode(value: ResultSet.Row.Column): Tag =
        Tag(value.asString())
}

/** Enum for testing enum-as-parameter resolution. */
enum class TestStatus { ACTIVE, INACTIVE }
