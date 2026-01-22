package io.github.smyrgeorge.sqlx4k.annotation

import io.github.smyrgeorge.sqlx4k.ValueEncoder
import kotlin.reflect.KClass

/**
 * Specifies a custom [ValueEncoder] to use for encoding and decoding a property value.
 *
 * Use this annotation on properties of `@Table`-annotated data classes when you need to
 * convert a custom type to/from its database representation. The specified encoder is used
 * directly in the generated code, providing compile-time type safety and avoiding runtime
 * registry lookups.
 *
 * ## Requirements
 *
 * - The encoder must be declared as a Kotlin `object` (singleton), not a `class`.
 * - The encoder's type parameter must match the property type.
 * - The property type must not be a built-in type (String, Int, Long, etc.) - use this only for custom types.
 *
 * ## Example
 *
 * ```kotlin
 * // Define a custom type
 * data class Money(val amount: Double, val currency: String) {
 *     override fun toString(): String = "$amount:$currency"
 *     companion object {
 *         fun parse(value: String): Money {
 *             val parts = value.split(":")
 *             return Money(parts[0].toDouble(), parts[1])
 *         }
 *     }
 * }
 *
 * // Create a ValueEncoder as an object (singleton)
 * object MoneyEncoder : ValueEncoder<Money> {
 *     override fun encode(value: Money): Any = value.toString()
 *     override fun decode(value: ResultSet.Row.Column): Money = Money.parse(value.asString())
 * }
 *
 * // Use @Converter to specify the encoder for the property
 * @Table("invoices")
 * data class Invoice(
 *     @Id
 *     val id: Long,
 *     val description: String,
 *     @Converter(MoneyEncoder::class)
 *     val totalAmount: Money
 * )
 * ```
 *
 * ## Generated Code
 *
 * When `@Converter` is present, the generated code references the encoder object directly:
 * - **INSERT/UPDATE**: Uses `MoneyEncoder.encode(totalAmount)` when binding the value.
 * - **RowMapper**: Uses `MoneyEncoder.decode(col)` when mapping the column value.
 *
 * This eliminates the need to register the encoder in a `ValueEncoderRegistry` for this property
 * and avoids creating new encoder instances on every operation.
 *
 * @property value The [KClass] of the [ValueEncoder] object to use for this property.
 *                 The encoder must be declared as a Kotlin `object` (singleton).
 *
 * @see ValueEncoder For implementing custom type conversions.
 * @see Table For marking data classes as database entities.
 */
@Target(AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.SOURCE)
annotation class Converter(val value: KClass<out ValueEncoder<*>>)
