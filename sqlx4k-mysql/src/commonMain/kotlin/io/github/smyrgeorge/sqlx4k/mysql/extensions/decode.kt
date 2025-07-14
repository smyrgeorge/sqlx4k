@file:Suppress("unused")

package io.github.smyrgeorge.sqlx4k.mysql.extensions

import io.github.smyrgeorge.sqlx4k.ResultSet

private fun String.asBoolean(): Boolean {
    require(this == "1" || this == "0") { "Invalid boolean value: $this" }
    return this == "1"
}

fun ResultSet.Row.Column.asBoolean(): Boolean = asString().asBoolean()
fun ResultSet.Row.Column.asBooleanOrNull(): Boolean? = asStringOrNull()?.asBoolean()

@OptIn(ExperimentalStdlibApi::class)
fun ResultSet.Row.Column.asByteArray(): ByteArray = asString().hexToByteArray()

@OptIn(ExperimentalStdlibApi::class)
fun ResultSet.Row.Column.asByteArrayOrNull(): ByteArray? = asStringOrNull()?.hexToByteArray()
