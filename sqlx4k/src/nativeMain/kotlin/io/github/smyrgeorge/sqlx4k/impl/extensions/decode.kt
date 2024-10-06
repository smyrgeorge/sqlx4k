@file:Suppress("unused")

package io.github.smyrgeorge.sqlx4k.impl.extensions

import io.github.smyrgeorge.sqlx4k.ResultSet
import io.github.smyrgeorge.sqlx4k.SQLError
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

fun ResultSet.Row.Column.asBoolean(): Boolean = asString().toBoolean()
fun ResultSet.Row.Column.asBooleanOrNull(): Boolean? = asStringOrNull()?.toBoolean()
fun ResultSet.Row.Column.asInt(): Int = asString().toInt()
fun ResultSet.Row.Column.asIntOrNull(): Int? = asStringOrNull()?.toInt()
fun ResultSet.Row.Column.asLong(): Long = asString().toLong()
fun ResultSet.Row.Column.asLongOrNull(): Long? = asStringOrNull()?.toLong()
fun ResultSet.Row.Column.asShortOrNull(): Short? = asStringOrNull()?.toShort()
fun ResultSet.Row.Column.asFloatOrNull(): Float? = asStringOrNull()?.toFloat()
fun ResultSet.Row.Column.asDoubleOrNull(): Double? = asStringOrNull()?.toDouble()

@OptIn(ExperimentalUuidApi::class)
fun ResultSet.Row.Column.asUuid(): Uuid = Uuid.parse(asString())

@OptIn(ExperimentalUuidApi::class)
fun ResultSet.Row.Column.asUuidOrNull(): Uuid? = asStringOrNull()?.let { Uuid.parse(it) }

inline fun <reified T : Enum<T>> ResultSet.Row.Column.asEnum(): T = asString().toEnum<T>()
inline fun <reified T : Enum<T>> ResultSet.Row.Column.asEnumOrNull(): T? = asStringOrNull()?.toEnum<T>()

inline fun <reified T : Enum<T>> String.toEnum(): T =
    try {
        enumValueOf<T>(this)
    } catch (e: Exception) {
        SQLError(SQLError.Code.CannotDecodeEnumValue, "Cannot decode enum value '$this'.").ex()
    }
