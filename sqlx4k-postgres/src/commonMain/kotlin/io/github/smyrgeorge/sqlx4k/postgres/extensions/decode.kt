@file:Suppress("unused")

package io.github.smyrgeorge.sqlx4k.postgres.extensions

import io.github.smyrgeorge.sqlx4k.ResultSet

private fun String.asBoolean(): Boolean {
    require(this == "t" || this == "f") { "Invalid boolean value: $this" }
    return this == "t"
}

fun ResultSet.Row.Column.asBoolean(): Boolean = asString().asBoolean()
fun ResultSet.Row.Column.asBooleanOrNull(): Boolean? = asStringOrNull()?.asBoolean()

@OptIn(ExperimentalStdlibApi::class)
fun ResultSet.Row.Column.asByteArray(): ByteArray = asString().removePrefix("\\x").hexToByteArray()

@OptIn(ExperimentalStdlibApi::class)
fun ResultSet.Row.Column.asByteArrayOrNull(): ByteArray? = asStringOrNull()?.removePrefix("\\x")?.hexToByteArray()
