package io.github.smyrgeorge.sqlx4k.mysql

import assertk.assertAll
import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import assertk.assertions.isTrue
import io.github.smyrgeorge.sqlx4k.Driver
import io.github.smyrgeorge.sqlx4k.ResultSet
import io.github.smyrgeorge.sqlx4k.impl.extensions.asLocalDate
import io.github.smyrgeorge.sqlx4k.impl.extensions.asLocalTime
import io.github.smyrgeorge.sqlx4k.mysql.extensions.asBoolean
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlin.test.Test

class MySQLTests {

    val options = Driver.Pool.Options.builder()
        .maxConnections(10)
        .build()

    val db = MySQL(
        url = "mysql://localhost:13306/test",
        username = "mysql",
        password = "mysql",
        options = options
    )

    @Test
    fun `Test basic type mappings`() = runBlocking {
        val types = """
            select
                   null,
                   true,
                   1, -- cast(1 as smallint),
                   1, -- cast(1 as int),
                   1, -- cast(1 as bigint),
                   1, -- cast(1 as float),
                   1, -- cast(1 as double),
                   1, -- cast(1 as numeric(10,2)),
                   1, -- cast('a' as char),
                   '',
                   'aa',
                   1, -- cast('2025-03-25 07:31:43.330068' as timestamp) as timestamp,
                   1, -- cast('2025-03-25 07:31:43.330068+00' as timestamp) as timestampz,
                   cast('2025-03-25' as date) as date,
                   cast('07:31:43' as time) as time,
                   1, -- cast('22d64ef8-f6b3-43da-8869-2ee9d31be9d5' as uuid),
                   1 -- cast('aa' as blob)
            ;
        """.trimIndent()

        db.fetchAll(types).getOrThrow().use {
            val row: ResultSet.Row = it.first()

            assertAll {
                assertThat(row.get(0).asStringOrNull()).isNull()
                assertThat(row.get(1).asBoolean()).isTrue()
//                assertThat(row.get(2).asShort()).isEqualTo(1)
//                assertThat(row.get(3).asInt()).isEqualTo(1)
//                assertThat(row.get(4).asLong()).isEqualTo(1L)
//                assertThat(row.get(5).asFloat()).isEqualTo(1.0f)
//                assertThat(row.get(6).asDouble()).isEqualTo(1.0)
//                assertThat(row.get(7).asString()).isEqualTo("1.00") // Add BigDecimal support
//                assertThat(row.get(8).asChar()).isEqualTo('a')
                assertThat(row.get(9).asString()).isEqualTo("")
                assertThat(row.get(10).asString()).isEqualTo("aa")
//                assertThat(row.get(11).asLocalDateTime()).isEqualTo(LocalDateTime.parse("2025-03-25T07:31:43.330068"))
//                assertThat(row.get(12).asInstant()).isEqualTo(Instant.parse("2025-03-25T07:31:43.330068Z"))
                assertThat(row.get(13).asLocalDate()).isEqualTo(LocalDate.parse("2025-03-25"))
                assertThat(row.get(14).asLocalTime()).isEqualTo(LocalTime.parse("07:31:43"))
//                assertThat(row.get(15).asUuid()).isEqualTo(Uuid.parse("22d64ef8-f6b3-43da-8869-2ee9d31be9d5"))
//                assertThat(row.get(16).asByteArray()).isEqualTo("aa".encodeToByteArray())
            }
        }
    }
}