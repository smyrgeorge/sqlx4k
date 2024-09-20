package io.github.smyrgeorge.sqlx4k.sqldelight

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import io.github.smyrgeorge.sqlx4k.ResultSet

class SqlDelightCursor(
    private val result: ResultSet
) : SqlCursor {
    private lateinit var current: ResultSet.Row

    override fun next(): QueryResult.AsyncValue<Boolean> = QueryResult.AsyncValue {
        if (!result.hasNext()) return@AsyncValue false
        current = result.next()
        true
    }

    override fun getBoolean(index: Int): Boolean? = current.get(index).value?.toBoolean()
    override fun getBytes(index: Int): ByteArray? = current.get(index).valueAsByteArray()
    override fun getDouble(index: Int): Double? = current.get(index).value?.toDouble()
    override fun getLong(index: Int): Long? = current.get(index).value?.toLong()
    override fun getString(index: Int): String? = current.get(index).value
}
