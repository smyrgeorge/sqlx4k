package io.github.smyrgeorge.sqlx4k.mysql

import assertk.assertAll
import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import assertk.assertions.isTrue
import io.github.smyrgeorge.sqlx4k.impl.extensions.*
import io.github.smyrgeorge.sqlx4k.mysql.extensions.asBoolean
import io.github.smyrgeorge.sqlx4k.mysql.extensions.asByteArray
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

class CommonMySQLTests(
    private val db: IMySQL
) {

    @OptIn(ExperimentalUuidApi::class)
    fun `Test basic type mappings`() = runBlocking {
        val types = """
            select
                   null as nil,
                   true as bool,
                   1 as smallint_v,
                   1 as int_v,
                   1 as bigint_V,
                   1 as float_v,
                   1 as double_v,
                   1 as numeric_v,
                   'a' as char_v,
                   '' as string_1,
                   'aa' as string_2,
                   cast('2025-03-25 07:31:43' as datetime) as datetime,
                   1, -- cast('2025-03-25 07:31:43.330068+00' as timestamp) as timestampz,
                   cast('2025-03-25' as date) as date,
                   cast('07:31:43' as time) as time,
                   '22d64ef8-f6b3-43da-8869-2ee9d31be9d5' as uuid,
                   hex('aa') as bytes
            ;
        """.trimIndent()

        val row = db.fetchAll(types).getOrThrow().first()

        assertAll {
            assertThat(row.get(0).asStringOrNull()).isNull()
            assertThat(row.get(1).asBoolean()).isTrue()
            assertThat(row.get(2).asShort()).isEqualTo(1)
            assertThat(row.get(3).asInt()).isEqualTo(1)
            assertThat(row.get(4).asLong()).isEqualTo(1L)
            assertThat(row.get(5).asFloat()).isEqualTo(1.0f)
            assertThat(row.get(6).asDouble()).isEqualTo(1.0)
//            assertThat(row.get(7).asString()).isEqualTo("1.00") // Add BigDecimal support
            assertThat(row.get(8).asChar()).isEqualTo('a')
            assertThat(row.get(9).asString()).isEqualTo("")
            assertThat(row.get(10).asString()).isEqualTo("aa")
            assertThat(row.get(11).asLocalDateTime()).isEqualTo(LocalDateTime.parse("2025-03-25T07:31:43"))
//            assertThat(row.get(12).asInstant()).isEqualTo(Instant.parse("2025-03-25T07:31:43.330068Z"))
            assertThat(row.get(13).asLocalDate()).isEqualTo(LocalDate.parse("2025-03-25"))
            assertThat(row.get(14).asLocalTime()).isEqualTo(LocalTime.parse("07:31:43"))
            assertThat(row.get(15).asUuid()).isEqualTo(Uuid.parse("22d64ef8-f6b3-43da-8869-2ee9d31be9d5"))
            assertThat(row.get(16).asByteArray()).isEqualTo("aa".encodeToByteArray())
        }
    }
}