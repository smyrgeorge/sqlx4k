package io.github.smyrgeorge.sqlx4k.postgres

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
import io.github.smyrgeorge.sqlx4k.impl.extensions.asInstant
import io.github.smyrgeorge.sqlx4k.impl.extensions.asInt
import io.github.smyrgeorge.sqlx4k.impl.extensions.asLocalDate
import io.github.smyrgeorge.sqlx4k.impl.extensions.asLocalDateTime
import io.github.smyrgeorge.sqlx4k.impl.extensions.asLocalTime
import io.github.smyrgeorge.sqlx4k.impl.extensions.asLong
import io.github.smyrgeorge.sqlx4k.impl.extensions.asShort
import io.github.smyrgeorge.sqlx4k.impl.extensions.asUuid
import io.github.smyrgeorge.sqlx4k.postgres.extensions.asBoolean
import io.github.smyrgeorge.sqlx4k.postgres.extensions.asByteArray
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlin.test.Test
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalUuidApi::class)
class PostgreSQLTests {

    val options = Driver.Pool.Options.builder()
        .maxConnections(10)
        .build()

    val db = PostgreSQL(
        url = "postgresql://localhost:15432/test",
        username = "postgres",
        password = "postgres",
        options = options
    )

    @Test
    fun `Test basic type mappings`() = runBlocking {
        val types = """
            select
                   null as nil,
                   true::bool as bool,
                   1::int2 as int2,
                   1::int4 as int4,
                   1::int8 as int8,
                   1::float4 as float4,
                   1::float8 as float8,
                   1::numeric(10,2) as numeric,
                   'a'::char as char,
                   'aa'::varchar as varchar,
                   'aa'::text as text,
                   '2025-03-25 07:31:43.330068'::timestamp as timestamp,
                   '2025-03-25 07:31:43.330068+00'::timestamptz as timestampz,
                   '2025-03-25'::date as date,
                   '07:31:43.330068'::time as time,
                   '22d64ef8-f6b3-43da-8869-2ee9d31be9d5'::uuid as uuid,
                   'aa'::bytea as bytea
            ;
        """.trimIndent()

        db.fetchAll(types).getOrThrow().use {
            val row: ResultSet.Row = it.first()

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
                assertThat(row.get(9).asString()).isEqualTo("aa")
                assertThat(row.get(10).asString()).isEqualTo("aa")
                assertThat(row.get(11).asLocalDateTime()).isEqualTo(LocalDateTime.parse("2025-03-25T07:31:43.330068"))
                assertThat(row.get(12).asInstant()).isEqualTo(Instant.parse("2025-03-25T07:31:43.330068Z"))
                assertThat(row.get(13).asLocalDate()).isEqualTo(LocalDate.parse("2025-03-25"))
                assertThat(row.get(14).asLocalTime()).isEqualTo(LocalTime.parse("07:31:43.330068"))
                assertThat(row.get(15).asUuid()).isEqualTo(Uuid.parse("22d64ef8-f6b3-43da-8869-2ee9d31be9d5"))
                assertThat(row.get(16).asByteArray()).isEqualTo("aa".encodeToByteArray())
            }
        }
    }
}