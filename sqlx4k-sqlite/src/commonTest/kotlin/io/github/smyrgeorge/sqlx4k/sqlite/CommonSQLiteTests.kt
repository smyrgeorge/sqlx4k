package io.github.smyrgeorge.sqlx4k.sqlite

import assertk.assertAll
import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import assertk.assertions.isTrue
import io.github.smyrgeorge.sqlx4k.Statement
import io.github.smyrgeorge.sqlx4k.impl.extensions.*
import io.github.smyrgeorge.sqlx4k.sqlite.extensions.asBoolean
import io.github.smyrgeorge.sqlx4k.sqlite.extensions.asByteArray
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlin.random.Random
import kotlin.time.ExperimentalTime
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@Suppress("SqlNoDataSourceInspection")
class CommonSQLiteTests(
    private val db: ISQLite
) {

    private fun newTable(): String = "t_${Random.nextInt(1_000_000)}"

    @OptIn(ExperimentalUuidApi::class, ExperimentalTime::class)
    fun `Test basic type mappings`() = runBlocking {
        // language=SQLite
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
                   datetime('2025-03-25 07:31:43') as datetime,
                   1, -- datetime('2025-03-25 07:31:43.330068+00') as timestampz,
                   date('2025-03-25') as date,
                   time('07:31:43') as time,
                   '22d64ef8-f6b3-43da-8869-2ee9d31be9d5' as uuid,
                   X'6161' as blob,
                   hex('aa') as blob_hex
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
            assertThat(row.get(17).asByteArray()).isEqualTo("aa".encodeToByteArray())
        }
    }

    fun `execute and fetchAll should work`(): Unit = runBlocking {
        val table = newTable()
        runCatching { db.execute("drop table if exists $table;") }
        db.execute("create table if not exists $table(id integer primary key autoincrement, v int not null);").getOrThrow()

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

    fun `execute and fetchAll with prepared statements should work`(): Unit = runBlocking {
        val table = newTable()
        runCatching { db.execute("drop table if exists $table;") }
        db.execute("create table if not exists $table(id integer primary key autoincrement, v int not null);").getOrThrow()

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
}