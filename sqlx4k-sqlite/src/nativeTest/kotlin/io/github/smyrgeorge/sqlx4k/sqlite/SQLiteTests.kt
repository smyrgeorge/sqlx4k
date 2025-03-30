package io.github.smyrgeorge.sqlx4k.sqlite

import assertk.assertAll
import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import assertk.assertions.isTrue
import io.github.smyrgeorge.sqlx4k.Driver
import io.github.smyrgeorge.sqlx4k.ResultSet
import io.github.smyrgeorge.sqlx4k.impl.extensions.asChar
import io.github.smyrgeorge.sqlx4k.impl.extensions.asDouble
import io.github.smyrgeorge.sqlx4k.impl.extensions.asFloat
import io.github.smyrgeorge.sqlx4k.impl.extensions.asInt
import io.github.smyrgeorge.sqlx4k.impl.extensions.asLocalDate
import io.github.smyrgeorge.sqlx4k.impl.extensions.asLocalDateTime
import io.github.smyrgeorge.sqlx4k.impl.extensions.asLocalTime
import io.github.smyrgeorge.sqlx4k.impl.extensions.asLong
import io.github.smyrgeorge.sqlx4k.impl.extensions.asShort
import io.github.smyrgeorge.sqlx4k.impl.extensions.asUuid
import io.github.smyrgeorge.sqlx4k.sqlite.extensions.asBoolean
import io.github.smyrgeorge.sqlx4k.sqlite.extensions.asByteArray
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlin.test.Test
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalUuidApi::class)
class SQLiteTests {

    val options = Driver.Pool.Options.builder()
        .maxConnections(10)
        .build()

    val db = SQLite(
        url = "sqlite://test.db",
        options = options
    )

    @Test
    fun `Test basic type mappings`() = runBlocking {
        val types = """
            select
                   null as nil,
                   true as boolean,
                   cast(1 as smallint) as smallint,
                   cast(1 as int) as int,
                   cast(1 as bigint) as bigint,
                   cast(1 as float) as float,
                   cast(1 as double) as double,
                   cast(1 as numeric(10,2)) as numeric,
                   cast('a' as char) as char,
                   '' as string_1,
                   'aa' as string_2,
                   datetime('2025-03-25 07:31:43.330068') as datetime,
                   1, -- datetime('2025-03-25 07:31:43.330068+00') as timestampz,
                   date('2025-03-25') as date,
                   time('07:31:43') as time,
                   '22d64ef8-f6b3-43da-8869-2ee9d31be9d5' as uuid,
                   hex('aa') as blob
            ;
        """.trimIndent()

        db.fetchAll(types).getOrThrow().use {
            val row: ResultSet.Row = first()

            assertAll {
                assertThat(row.get(0).asStringOrNull()).isNull()
                assertThat(row.get(1).asBoolean()).isTrue()
                assertThat(row.get(2).asShort()).isEqualTo(1)
                assertThat(row.get(3).asInt()).isEqualTo(1)
                assertThat(row.get(4).asLong()).isEqualTo(1L)
                assertThat(row.get(5).asFloat()).isEqualTo(1.0f)
                assertThat(row.get(6).asDouble()).isEqualTo(1.0)
//                assertThat(row.get(7).asString()).isEqualTo("1.00") // Add BigDecimal support
                assertThat(row.get(8).asChar()).isEqualTo('a')
                assertThat(row.get(9).asString()).isEqualTo("")
                assertThat(row.get(10).asString()).isEqualTo("aa")
                assertThat(row.get(11).asLocalDateTime()).isEqualTo(LocalDateTime.parse("2025-03-25T07:31:43"))
//                assertThat(row.get(12).asInstant()).isEqualTo(Instant.parse("2025-03-25T07:31:43.330068Z"))
                assertThat(row.get(13).asLocalDate()).isEqualTo(LocalDate.parse("2025-03-25"))
                assertThat(row.get(14).asLocalTime()).isEqualTo(LocalTime.parse("07:31:43"))
                assertThat(row.get(15).asUuid()).isEqualTo(Uuid.parse("22d64ef8-f6b3-43da-8869-2ee9d31be9d5"))
                assertThat(row.get(16).asByteArray()).isEqualTo("aa".encodeToByteArray())
            }
        }
    }
}