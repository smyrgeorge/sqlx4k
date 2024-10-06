@file:Suppress("unused")

package io.github.smyrgeorge.sqlx4k.postgres.extensions

import io.github.smyrgeorge.sqlx4k.ResultSet

@OptIn(ExperimentalStdlibApi::class)
fun ResultSet.Row.Column.asByteArray(): ByteArray = asString().removePrefix("\\x").hexToByteArray()

@OptIn(ExperimentalStdlibApi::class)
fun ResultSet.Row.Column.asByteArrayOrNull(): ByteArray? = asStringOrNull()?.removePrefix("\\x")?.hexToByteArray()
