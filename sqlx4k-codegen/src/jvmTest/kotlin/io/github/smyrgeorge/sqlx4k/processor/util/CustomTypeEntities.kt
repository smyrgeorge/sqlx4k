package io.github.smyrgeorge.sqlx4k.processor.util

import io.github.smyrgeorge.sqlx4k.annotation.Column
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
