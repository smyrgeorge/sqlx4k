package io.github.smyrgeorge.sqlx4k.sqldelight

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import io.github.smyrgeorge.sqlx4k.ResultSet
import kotlinx.datetime.DateTimePeriod
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalUuidApi::class)
@Suppress("unused")
class SqlDelightCursor(
    private val result: ResultSet
) : SqlCursor {
    private lateinit var current: ResultSet.Row

    override fun next(): QueryResult.AsyncValue<Boolean> = QueryResult.AsyncValue {
        if (!result.hasNext()) return@AsyncValue false
        current = result.next()
        true
    }

    override fun getBoolean(index: Int): Boolean? = getString(index)?.toBoolean()
    override fun getBytes(index: Int): ByteArray? = current.get(index).valueAsByteArray()
    override fun getDouble(index: Int): Double? = getString(index)?.toDouble()
    fun getShort(index: Int): Short? = getString(index)?.toShort()
    fun getInt(index: Int): Int? = getString(index)?.toInt()
    override fun getLong(index: Int): Long? = getString(index)?.toLong()
    override fun getString(index: Int): String? = current.get(index).value
    fun getDate(index: Int): LocalDate? = getString(index)?.let { LocalDate.parse(it) }
    fun getTime(index: Int): LocalTime? = getString(index)?.let { LocalTime.parse(it) }
    fun getLocalTimestamp(index: Int): LocalDateTime? = getString(index)?.replace(" ", "T")
        ?.let { LocalDateTime.parse(it) }

    fun getTimestamp(index: Int): Instant? = getString(index)?.let {
        Instant.parse(it.replace(" ", "T"))
    }

    fun getInterval(index: Int): DateTimePeriod? = getString(index)?.let { DateTimePeriod.parse(it) }
    fun getUuid(index: Int): Uuid? = getString(index)?.let { Uuid.parse(it) }
}
