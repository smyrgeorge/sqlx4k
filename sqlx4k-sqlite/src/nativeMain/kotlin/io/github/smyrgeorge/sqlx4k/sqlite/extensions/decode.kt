@file:Suppress("unused")

package io.github.smyrgeorge.sqlx4k.sqlite.extensions

import io.github.smyrgeorge.sqlx4k.ResultSet

fun ResultSet.Row.Column.asBoolean(): Boolean = asString().toBoolean()
fun ResultSet.Row.Column.asBooleanOrNull(): Boolean? = asStringOrNull()?.toBoolean()
