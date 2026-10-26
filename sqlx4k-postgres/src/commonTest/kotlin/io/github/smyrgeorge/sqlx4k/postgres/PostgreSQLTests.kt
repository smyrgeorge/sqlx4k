@file:Suppress("SqlNoDataSourceInspection", "SqlDialectInspection")

package io.github.smyrgeorge.sqlx4k.postgres

import assertk.assertAll
import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import assertk.assertions.isTrue
import io.github.smyrgeorge.sqlx4k.Statement
import io.github.smyrgeorge.sqlx4k.impl.extensions.asBoolean
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
import io.github.smyrgeorge.sqlx4k.postgres.extensions.asBooleanArray
import io.github.smyrgeorge.sqlx4k.postgres.extensions.asByteArray
import io.github.smyrgeorge.sqlx4k.postgres.extensions.asDoubleArray
import io.github.smyrgeorge.sqlx4k.postgres.extensions.asFloatArray
import io.github.smyrgeorge.sqlx4k.postgres.extensions.asIntArray
import io.github.smyrgeorge.sqlx4k.postgres.extensions.asLongArray
import io.github.smyrgeorge.sqlx4k.postgres.extensions.asShortArray
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.time.Instant
import kotlin.uuid.Uuid
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime

class PostgreSQLTests {

    private val db = testPostgreSQL()

    @AfterTest
    fun tearDown(): Unit = runBlocking { db.close().getOrThrow() }

    private fun newTable(): String = "t_${Random.nextInt(1_000_000)}"

    @Test
    fun `Test basic type mappings`(): Unit = runBlocking {
        // language=PostgreSQL
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
                   'aa'::bytea as bytea,
                   '{t,f}'::bool[] as bool_array,
                   '{1,2}'::int2[] as int2_array,
                   '{1,2,3}'::int4[] as int4_array,
                   '{10000000000,20000000000}'::int8[] as int8_array,
                   '{1.5,2.5}'::float4[] as float4_array,
                   '{1.5,2.5,3.5}'::float8[] as float8_array
            ;
        """.trimIndent()

        val row = db.fetchAll(types).getOrThrow().first()
        println(row)

        assertAll {
            assertThat(row.get(0).asStringOrNull()).isNull()
            assertThat(row.get(1).asBoolean()).isTrue()
            assertThat(row.get(2).asShort()).isEqualTo(1)
            assertThat(row.get(3).asInt()).isEqualTo(1)
            assertThat(row.get(4).asLong()).isEqualTo(1L)
            assertThat(row.get(5).asFloat()).isEqualTo(1.0f)
            assertThat(row.get(6).asDouble()).isEqualTo(1.0)
            assertThat(row.get(7).asString()).isEqualTo("1.00")
            assertThat(row.get(8).asChar()).isEqualTo('a')
            assertThat(row.get(9).asString()).isEqualTo("aa")
            assertThat(row.get(10).asString()).isEqualTo("aa")
            assertThat(row.get(11).asLocalDateTime()).isEqualTo(LocalDateTime.parse("2025-03-25T07:31:43.330068"))
            assertThat(row.get(12).asInstant()).isEqualTo(Instant.parse("2025-03-25T07:31:43.330068Z"))
            assertThat(row.get(13).asLocalDate()).isEqualTo(LocalDate.parse("2025-03-25"))
            assertThat(row.get(14).asLocalTime()).isEqualTo(LocalTime.parse("07:31:43.330068"))
            assertThat(row.get(15).asUuid()).isEqualTo(Uuid.parse("22d64ef8-f6b3-43da-8869-2ee9d31be9d5"))
            assertThat(row.get(16).asByteArray()).isEqualTo("aa".encodeToByteArray())
            assertThat(row.get(17).asBooleanArray().toList()).isEqualTo(listOf(true, false))
            assertThat(row.get(18).asShortArray().toList()).isEqualTo(listOf<Short>(1, 2))
            assertThat(row.get(19).asIntArray().toList()).isEqualTo(listOf(1, 2, 3))
            assertThat(row.get(20).asLongArray().toList()).isEqualTo(listOf(10000000000L, 20000000000L))
            assertThat(row.get(21).asFloatArray().toList()).isEqualTo(listOf(1.5f, 2.5f))
            assertThat(row.get(22).asDoubleArray().toList()).isEqualTo(listOf(1.5, 2.5, 3.5))
        }
    }

    @Test
    fun `execute and fetchAll should work`(): Unit = runBlocking {
        val table = newTable()
        runCatching { db.execute("drop table if exists $table;") }
        db.execute("create table if not exists $table(id serial primary key, v int not null);").getOrThrow()

        // insert a couple rows
        db.execute("insert into $table(v) values (1), (2), (3);").getOrThrow()
        val count = db.fetchAll("select count(*) from $table;").getOrThrow().first().get(0).asLong()
        assertThat(count).isEqualTo(3L)

        // update and verify
        db.execute("update $table set v = v + 1 where v >= 2;").getOrThrow()
        val sum = db.fetchAll("select sum(v) from $table;").getOrThrow().first().get(0).asLong()
        assertThat(sum).isEqualTo(1L + 3L + 4L)

        runCatching { db.execute("drop table if exists $table;").getOrThrow() }
    }

    @Test
    fun `execute and fetchAll with prepared statements should work`(): Unit = runBlocking {
        val table = newTable()
        runCatching { db.execute("drop table if exists $table;") }
        db.execute("create table if not exists $table(id serial primary key, v int not null);").getOrThrow()

        // positional parameters
        val stInsertPos: Statement = Statement.create("insert into $table(v) values (:v1), (:v2);")
            .bind("v1", 10)
            .bind("v2", 20)
        db.execute(stInsertPos).getOrThrow()

        // named parameters
        val stInsertNamed: Statement = Statement.create("insert into $table(v) values (:a), (:b), (:c);")
            .bind("a", 30)
            .bind("b", 40)
            .bind("c", 50)
        db.execute(stInsertNamed).getOrThrow()

        val stSelectAll = Statement.create("select v from $table where v >= :min order by v asc;")
            .bind("min", 20)
        val rows = db.fetchAll(stSelectAll).getOrThrow()
        val values = rows.map { it.get(0).asInt() }
        assertThat(values).isEqualTo(listOf(20, 30, 40, 50))

        // positional bind by index as well
        val stUpdate: Statement = Statement.create("update $table set v = v + ? where v = ?;")
            .bind(0, 5)
            .bind(1, 20)
        db.execute(stUpdate).getOrThrow()

        val stFetchOne = Statement.create("select v from $table where v = :v;").bind("v", 25)
        val vRow = db.fetchAll(stFetchOne).getOrThrow().first()
        assertThat(vRow.get(0).asInt()).isEqualTo(25)

        runCatching { db.execute("drop table if exists $table;").getOrThrow() }
    }

    // Valid values the driver's decoders cannot represent (an array with a NULL element, an infinite timestamp or
    // date, 24:00:00) must come back as postgres prints them, through both the plain and the prepared protocol,
    // instead of failing the decode (the native driver used to abort the process on them).
    @Test
    fun `values beyond the decoded types come back as postgres text`(): Unit = runBlocking {
        val sql = "select array[1, null, 3]::int4[], 'infinity'::timestamp, '-infinity'::timestamptz, " +
            "'infinity'::date, '24:00:00'::time"
        val expected = listOf("{1,NULL,3}", "infinity", "-infinity", "infinity", "24:00:00")

        val plain = db.fetchAll(sql).getOrThrow().first()
        val prepared = db.fetchAll(Statement.create("$sql where 1 = :one").bind("one", 1)).getOrThrow().first()
        assertAll {
            assertThat(expected.indices.map { plain.get(it).asString() }, "plain").isEqualTo(expected)
            assertThat(expected.indices.map { prepared.get(it).asString() }, "prepared").isEqualTo(expected)
        }
    }

    // A query of several statements returns the rows of the last one, with its own columns, on every driver (and
    // through a connection and a transaction too).
    @Test
    fun `fetchAll of several statements returns the last statement's rows`(): Unit = runBlocking {
        val sql = "select 1 as a; select 2 as b, 'x' as c union all select 3, 'y'"
        fun check(rs: io.github.smyrgeorge.sqlx4k.ResultSet) = assertAll {
            assertThat(List(rs.metadata.getColumnCount()) { rs.metadata.getColumn(it).name }).isEqualTo(listOf("b", "c"))
            assertThat(rs.map { listOf(it.get(0).asInt().toString(), it.get(1).asString()) })
                .isEqualTo(listOf(listOf("2", "x"), listOf("3", "y")))
        }
        check(db.fetchAll(sql).getOrThrow())
        val cn = db.acquire().getOrThrow()
        try {
            check(cn.fetchAll(sql).getOrThrow())
            val tx = cn.begin().getOrThrow()
            check(tx.fetchAll(sql).getOrThrow())
            tx.rollback().getOrThrow()
        } finally {
            cn.close().getOrThrow()
        }
    }
}
