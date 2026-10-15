package io.github.smyrgeorge.sqlx4k.mysql

import io.github.smyrgeorge.sqlx4k.ConnectionPool
import io.github.smyrgeorge.sqlx4k.ResultSet
import io.github.smyrgeorge.sqlx4k.SQLError
import io.github.smyrgeorge.sqlx4k.ValueEncoder
import io.github.smyrgeorge.sqlx4k.ValueEncoderRegistry
import io.github.smyrgeorge.sqlx4k.impl.extensions.asInt

/** Connects to the MySQL server from `docker-compose.yml`. */
internal fun testMySQL(
    url: String = "mysql://localhost:13306/test",
    options: ConnectionPool.Options = ConnectionPool.Options.builder().maxConnections(2).build(),
    encoders: ValueEncoderRegistry = ValueEncoderRegistry(),
): IMySQL = mySQL(url = url, username = "mysql", password = "mysql", options = options, encoders = encoders)

/** The code a TLS failure is reported with, or `null` where requiring TLS succeeds (the JVM driver supports it). */
internal expect val tlsFailureCode: SQLError.Code?

/** The code a refused connection is reported with: sqlx (native) retries it until the acquire timeout. */
internal expect val refusedConnectionCode: SQLError.Code

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
