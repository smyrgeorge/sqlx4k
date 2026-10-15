package io.github.smyrgeorge.sqlx4k.postgres

import io.github.smyrgeorge.sqlx4k.ConnectionPool
import io.github.smyrgeorge.sqlx4k.ResultSet
import io.github.smyrgeorge.sqlx4k.SQLError
import io.github.smyrgeorge.sqlx4k.ValueEncoder
import io.github.smyrgeorge.sqlx4k.ValueEncoderRegistry
import io.github.smyrgeorge.sqlx4k.impl.extensions.asInt

/** Connects to the PostgreSQL server from `docker-compose.yml`. */
internal fun testPostgreSQL(
    url: String = "postgresql://localhost:15432/test",
    options: ConnectionPool.Options = ConnectionPool.Options.builder().maxConnections(2).build(),
    encoders: ValueEncoderRegistry = ValueEncoderRegistry(),
): IPostgresSQL = postgreSQL(url = url, username = "postgres", password = "postgres", options = options, encoders = encoders)

/** The code a TLS handshake failure is reported with: R2DBC (JVM) reports it as a pool error. */
internal expect val tlsFailureCode: SQLError.Code

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
