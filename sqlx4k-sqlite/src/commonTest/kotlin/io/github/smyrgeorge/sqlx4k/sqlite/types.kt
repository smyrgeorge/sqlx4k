package io.github.smyrgeorge.sqlx4k.sqlite

import io.github.smyrgeorge.sqlx4k.ResultSet
import io.github.smyrgeorge.sqlx4k.ValueEncoder
import io.github.smyrgeorge.sqlx4k.impl.extensions.asInt


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
