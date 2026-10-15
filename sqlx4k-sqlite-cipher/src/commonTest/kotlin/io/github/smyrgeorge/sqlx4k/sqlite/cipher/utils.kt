package io.github.smyrgeorge.sqlx4k.sqlite.cipher

import io.github.smyrgeorge.sqlx4k.ConnectionPool
import io.github.smyrgeorge.sqlx4k.ResultSet
import io.github.smyrgeorge.sqlx4k.ValueEncoder
import io.github.smyrgeorge.sqlx4k.ValueEncoderRegistry
import io.github.smyrgeorge.sqlx4k.impl.extensions.asInt
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

/**
 * Base class of the database tests. On Android it runs them under Robolectric, since the driver needs a
 * Context; JUnit's `@RunWith` is inherited, so the test classes need no annotation of their own.
 */
expect abstract class SQLiteCipherTestBase()

/** An absolute directory the tests may create database files in. */
internal expect val testDirectory: String

/** Opens [url] with the platform's driver (Android needs a Context). */
internal expect fun openSQLiteCipher(
    url: String,
    options: ConnectionPool.Options,
    encoders: ValueEncoderRegistry = ValueEncoderRegistry(),
): ISQLiteCipher

internal const val TEST_PASSPHRASE = "test-passphrase"

/** The URL of the database file named after the suite. */
internal fun testDatabaseUrl(name: String): String = "sqlite://$testDirectory/$name.db"

/** Opens an empty, encrypted database file named after the suite, or an in-memory database. */
internal fun testSQLiteCipher(
    name: String,
    options: ConnectionPool.Options = ConnectionPool.Options.builder().maxConnections(2).build(),
    encoders: ValueEncoderRegistry = ValueEncoderRegistry(),
    inMemory: Boolean = false,
): ISQLiteCipher {
    if (inMemory) return openSQLiteCipher("sqlite::memory:", options, encoders)
    val path = "$testDirectory/$name.db"
    listOf("", "-wal", "-shm", "-journal").forEach { SystemFileSystem.delete(Path("$path$it"), mustExist = false) }
    return openSQLiteCipher(testDatabaseUrl(name), options, encoders)
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
