package io.github.smyrgeorge.sqlx4k.impl.types

import kotlin.reflect.KClass

/**
 * Wraps a null value with type information for use with [io.github.smyrgeorge.sqlx4k.Statement.bindNull].
 *
 * When binding a null parameter to a prepared statement, the database driver
 * typically needs the target SQL type to determine the correct OID or wire format.
 * This wrapper preserves that type alongside the null so it can be forwarded to the
 * driver's `bindNull` call.
 *
 * @property type The Kotlin class representing the intended parameter type.
 */
data class TypedNull(val type: KClass<*>)
