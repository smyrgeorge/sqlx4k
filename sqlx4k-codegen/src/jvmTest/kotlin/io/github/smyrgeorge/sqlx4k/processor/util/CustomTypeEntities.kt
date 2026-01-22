package io.github.smyrgeorge.sqlx4k.processor.util

import io.github.smyrgeorge.sqlx4k.ResultSet
import io.github.smyrgeorge.sqlx4k.ValueEncoder
import io.github.smyrgeorge.sqlx4k.annotation.Column
import io.github.smyrgeorge.sqlx4k.annotation.Converter
import io.github.smyrgeorge.sqlx4k.annotation.Id
import io.github.smyrgeorge.sqlx4k.annotation.Table

/**
 * Custom type representing a monetary amount with currency.
 */
data class Money(
    val amount: Double,
    val currency: String
) {
    override fun toString(): String = "$amount:$currency"

    companion object {
        fun parse(value: String): Money {
            val parts = value.split(":")
            require(parts.size == 2) { "Invalid money format: $value" }
            return Money(parts[0].toDouble(), parts[1])
        }
    }
}

/**
 * Custom type representing a geographic coordinate.
 */
data class GeoPoint(
    val latitude: Double,
    val longitude: Double
) {
    override fun toString(): String = "$latitude,$longitude"

    companion object {
        fun parse(value: String): GeoPoint {
            val parts = value.split(",")
            require(parts.size == 2) { "Invalid geo point format: $value" }
            return GeoPoint(parts[0].toDouble(), parts[1].toDouble())
        }
    }
}

/**
 * Entity with a custom Money type for price.
 */
@Table("invoices")
data class Invoice(
    @Id
    val id: Long,
    val description: String,
    val totalAmount: Money
)

/**
 * Entity with a nullable custom type.
 */
@Table("stores")
data class Store(
    @Id
    val id: Long,
    val name: String,
    val location: GeoPoint?
)

/**
 * Entity with multiple custom types.
 */
@Table("transactions")
data class Transaction(
    @Id
    val id: Long,
    val fromAmount: Money,
    val toAmount: Money,
    val exchangeLocation: GeoPoint?
)

/**
 * Entity with custom type and @Column annotations.
 * processedAmount is a custom type in RETURNING columns (tests applyInsertResult/applyUpdateResult).
 */
@Table("payments")
data class Payment(
    @Id
    val id: Long,
    val amount: Money,
    @Column(insert = false, update = false)
    val processedAmount: Money?
)

// =====================================================
// ValueEncoder implementations for @Converter tests
// =====================================================

/**
 * ValueEncoder object for Money type - used with @Converter annotation.
 * Must be declared as an object (singleton) for use with @Converter.
 */
object MoneyEncoder : ValueEncoder<Money> {
    override fun encode(value: Money): Any = value.toString()
    override fun decode(value: ResultSet.Row.Column): Money = Money.parse(value.asString())
}

/**
 * ValueEncoder object for GeoPoint type - used with @Converter annotation.
 * Must be declared as an object (singleton) for use with @Converter.
 */
object GeoPointEncoder : ValueEncoder<GeoPoint> {
    override fun encode(value: GeoPoint): Any = value.toString()
    override fun decode(value: ResultSet.Row.Column): GeoPoint = GeoPoint.parse(value.asString())
}

// =====================================================
// Entities using @Converter annotation
// =====================================================

/**
 * Entity with @Converter for a custom Money type.
 */
@Table("converter_invoices")
data class ConverterInvoice(
    @Id
    val id: Long,
    val description: String,
    @Converter(MoneyEncoder::class)
    val totalAmount: Money
)

/**
 * Entity with @Converter for a nullable custom type.
 */
@Table("converter_stores")
data class ConverterStore(
    @Id
    val id: Long,
    val name: String,
    @Converter(GeoPointEncoder::class)
    val location: GeoPoint?
)

/**
 * Entity with multiple @Converter annotations for different custom types.
 */
@Table("converter_transactions")
data class ConverterTransaction(
    @Id
    val id: Long,
    @Converter(MoneyEncoder::class)
    val fromAmount: Money,
    @Converter(MoneyEncoder::class)
    val toAmount: Money,
    @Converter(GeoPointEncoder::class)
    val exchangeLocation: GeoPoint?
)

/**
 * Entity with @Converter and @Column annotations combined.
 */
@Table("converter_payments")
data class ConverterPayment(
    @Id
    val id: Long,
    @Converter(MoneyEncoder::class)
    val amount: Money,
    @Column(insert = false, update = false)
    @Converter(MoneyEncoder::class)
    val processedAmount: Money?
)
