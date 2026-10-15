@file:Suppress("SqlNoDataSourceInspection", "SqlDialectInspection")

package io.github.smyrgeorge.sqlx4k.mysql

import assertk.assertAll
import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import assertk.assertions.isTrue
import io.github.smyrgeorge.sqlx4k.Statement
import io.github.smyrgeorge.sqlx4k.impl.extensions.asBoolean
import io.github.smyrgeorge.sqlx4k.impl.extensions.asByteArray
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
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.time.Instant
import kotlin.uuid.Uuid
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime

class MySQLTests {

    private val db = testMySQL()

    @AfterTest
    fun tearDown(): Unit = runBlocking { db.close().getOrThrow() }

    private fun newTable(): String = "t_${Random.nextInt(1_000_000)}"

    @Test
    fun `Test basic type mappings`(): Unit = runBlocking {
        // language=MySQL
        val types = """
            select
                   null as nil,
                   true as bool,
                   1 as smallint_v,
                   1 as int_v,
                   1 as bigint_V,
                   1 as float_v,
                   1 as double_v,
                   cast(1 as decimal(10,2)) as numeric_v,
                   'a' as char_v,
                   '' as string_1,
                   'aa' as string_2,
                   cast('2025-03-25 07:31:43' as datetime) as datetime,
                   cast('2025-03-25 07:31:43.330068' as datetime(6)) as timestampz,
                   cast('2025-03-25' as date) as date,
                   cast('07:31:43' as time) as time,
                   '22d64ef8-f6b3-43da-8869-2ee9d31be9d5' as uuid,
                   hex('aa') as bytes
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
            assertThat(row.get(9).asString()).isEqualTo("")
            assertThat(row.get(10).asString()).isEqualTo("aa")
            assertThat(row.get(11).asLocalDateTime()).isEqualTo(LocalDateTime.parse("2025-03-25T07:31:43"))
            assertThat(row.get(12).asInstant()).isEqualTo(Instant.parse("2025-03-25T07:31:43.330068Z"))
            assertThat(row.get(13).asLocalDate()).isEqualTo(LocalDate.parse("2025-03-25"))
            assertThat(row.get(14).asLocalTime()).isEqualTo(LocalTime.parse("07:31:43"))
            assertThat(row.get(15).asUuid()).isEqualTo(Uuid.parse("22d64ef8-f6b3-43da-8869-2ee9d31be9d5"))
            assertThat(row.get(16).asByteArray()).isEqualTo("aa".encodeToByteArray())
        }
    }

    @Test
    fun `execute and fetchAll should work`(): Unit = runBlocking {
        val table = newTable()
        runCatching { db.execute("drop table if exists $table;") }
        db.execute("create table if not exists $table(id int auto_increment primary key, v int not null);").getOrThrow()

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
        db.execute("create table if not exists $table(id int auto_increment primary key, v int not null);").getOrThrow()

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

    @Test
    fun `text with a binary collation decodes as text`(): Unit = runBlocking {
        // MySQL flags `_bin` collated text as BINARY too; only the `binary` collation (VARBINARY, BLOB) holds bytes.
        val table = newTable()
        runCatching { db.execute("drop table if exists $table;") }
        db.execute("create table $table(id int primary key, name varchar(32) collate utf8mb4_bin, raw varbinary(8));")
            .getOrThrow()
        db.execute(
            Statement.create("insert into $table(id, name, raw) values (1, ?, ?);")
                .bind(0, "Alice")
                .bind(1, "Alice".encodeToByteArray())
        ).getOrThrow()

        val sql = "select name, raw from $table;"
        for (row in listOf(db.fetchAll(sql).getOrThrow().first(), db.fetchAll(Statement.create(sql)).getOrThrow().first())) {
            assertAll {
                assertThat(row.get(0).asString()).isEqualTo("Alice")
                assertThat(row.get(1).asByteArray()).isEqualTo("Alice".encodeToByteArray())
            }
        }

        runCatching { db.execute("drop table if exists $table;").getOrThrow() }
    }

    @Test
    fun `generated ids are read back on the connection that inserted them`(): Unit = runBlocking {
        // How the generated MySQL insert() reads its row back: LAST_INSERT_ID() is per connection, and a prepared
        // statement cannot hold the insert and the select together.
        val table = newTable()
        runCatching { db.execute("drop table if exists $table;") }
        db.execute("create table $table(id bigint primary key auto_increment, v varchar(16) not null);").getOrThrow()

        val inserted = (1..20).map { i ->
            async {
                db.withSingleConnection { cx ->
                    cx.execute(Statement.create("insert into $table(v) values (?);").bind(0, "v$i")).mapCatching {
                        cx.fetchAll(Statement.create("select id, v from $table where id = last_insert_id();")).getOrThrow()
                    }
                }.getOrThrow().first().let { it.get(0).asLong() to it.get(1).asString() }
            }
        }.awaitAll()

        assertAll {
            // Each insert reads back its own row.
            assertThat(inserted.map { it.second }).isEqualTo((1..20).map { "v$it" })
            assertThat(inserted.map { it.first }.toSet().size).isEqualTo(20)
        }

        runCatching { db.execute("drop table if exists $table;").getOrThrow() }
    }
}
