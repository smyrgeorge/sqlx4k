package io.github.smyrgeorge.sqlx4k.sqldelight

import app.cash.sqldelight.db.SqlPreparedStatement
import io.github.smyrgeorge.sqlx4k.impl.ExtendedStatement
import kotlinx.datetime.DateTimePeriod
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalUuidApi::class, ExperimentalStdlibApi::class)
@Suppress("unused")
class SqlDelightPreparedStatement(sql: String) : SqlPreparedStatement {
    var statement = ExtendedStatement(sql)

    override fun bindBoolean(index: Int, boolean: Boolean?) {
        statement = statement.bind(index, boolean)
    }

    override fun bindBytes(index: Int, bytes: ByteArray?) {
        statement.bind(index, bytes?.toHexString()?.let { "\\x$it" })
    }

    override fun bindDouble(index: Int, double: Double?) {
        statement = statement.bind(index, double)
    }

    fun bindShort(index: Int, short: Short?) {
        statement = statement.bind(index, short)
    }

    fun bindInt(index: Int, int: Int?) {
        statement = statement.bind(index, int)
    }

    override fun bindLong(index: Int, long: Long?) {
        statement = statement.bind(index, long)
    }

    override fun bindString(index: Int, string: String?) {
        statement = statement.bind(index, string)
    }

    fun bindDate(index: Int, value: LocalDate?) {
        statement = statement.bind(index, value?.toString())
    }

    fun bindTime(index: Int, value: LocalTime?) {
        statement = statement.bind(index, value?.toString())
    }

    fun bindLocalTimestamp(index: Int, value: LocalDateTime?) {
        statement = statement.bind(index, value?.toString())
    }

    fun bindTimestamp(index: Int, value: Instant?) {
        statement = statement.bind(index, value?.toString())
    }

    fun bindInterval(index: Int, value: DateTimePeriod?) {
        statement = statement.bind(index, value?.toString())
    }

    fun bindUuid(index: Int, value: Uuid?) {
        statement = statement.bind(index, value?.toString())
    }
}
