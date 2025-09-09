@file:Suppress("unused")

package io.github.smyrgeorge.sqlx4k.mysql.extensions

import io.github.smyrgeorge.sqlx4k.ResultSet

fun ResultSet.Row.Column.asBoolean(): Boolean = asString().asBoolean()
fun ResultSet.Row.Column.asBooleanOrNull(): Boolean? = asStringOrNull()?.asBoolean()
fun ResultSet.Row.Column.asByteArray(): ByteArray = asString().hexToByteArray()
fun ResultSet.Row.Column.asByteArrayOrNull(): ByteArray? = asStringOrNull()?.hexToByteArray()

private fun String.asBoolean(): Boolean {
    require(this == "1" || this == "0") { "Invalid boolean value: $this" }
    return this == "1"
}