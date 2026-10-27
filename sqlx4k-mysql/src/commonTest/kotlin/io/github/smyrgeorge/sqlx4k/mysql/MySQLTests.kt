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
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant
import kotlin.uuid.Uuid
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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

    // How the generated MySQL update() reads its row back: in the transaction of the update, which keeps the row
    // locked, so another writer cannot bump its version before it is read back (reported as a lock failure).
    @Test
    fun `a row is read back before another writer can change it`(): Unit = runBlocking {
        val table = newTable()
        db.execute("create table $table(id bigint primary key, version int not null);").getOrThrow()
        db.execute("insert into $table(id, version) values (1, 1);").getOrThrow()
        try {
            var other: kotlinx.coroutines.Job? = null
            val readBack = db.withReadBackTransaction { cx ->
                cx.execute("update $table set version = 2 where id = 1 and version = 1;").mapCatching {
                    // Another writer, on another connection, between the update and the read back.
                    other = launch { db.execute("update $table set version = version + 1 where id = 1;").getOrThrow() }
                    delay(300.milliseconds)
                    cx.fetchAll("select id from $table where id = 1 and version = 2;").getOrThrow()
                }
            }.getOrThrow()
            other!!.join()
            assertThat(readBack.size).isEqualTo(1)
            val version = db.fetchAll("select version from $table where id = 1;").getOrThrow().first().get(0).asInt()
            assertThat(version).isEqualTo(3)
        } finally {
            db.execute("drop table if exists $table;").getOrThrow()
        }
    }

    @Test
    fun `a failed read back rolls back its write`(): Unit = runBlocking {
        val table = newTable()
        db.execute("create table $table(id bigint primary key);").getOrThrow()
        try {
            val res = db.withReadBackTransaction { cx ->
                cx.execute("insert into $table(id) values (1);").mapCatching { error("read back failed") }
            }
            assertThat(res.isFailure).isTrue()
            assertThat(db.fetchAll("select count(*) from $table;").getOrThrow().first().get(0).asInt()).isEqualTo(0)
        } finally {
            db.execute("drop table if exists $table;").getOrThrow()
        }
    }

    // Valid values chrono cannot represent (TIME past 24 hours or negative, zero dates) must come back as MySQL
    // prints them, through both the plain and the prepared protocol, instead of failing the decode (the native
    // driver used to abort the process on them).
    @Test
    fun `values beyond the decoded types come back as mysql text`(): Unit = runBlocking {
        val table = "t_zero_${Random.nextInt(1_000_000)}"
        val cn = db.acquire().getOrThrow()
        try {
            // Zero dates are only accepted without the strict sql_mode defaults.
            cn.execute("set session sql_mode = ''").getOrThrow()
            cn.execute("create table $table(d date, dt datetime)").getOrThrow()
            cn.execute("insert into $table values ('0000-00-00', '0000-00-00 00:00:00')").getOrThrow()

            val sql = "select cast('838:59:59' as time), cast('-01:30:00' as time), d, dt from $table"
            val plain = cn.fetchAll(sql).getOrThrow().first()
            val prepared = cn.fetchAll(Statement.create("$sql where 1 = :one").bind("one", 1)).getOrThrow().first()
            for ((name, row) in listOf("plain" to plain, "prepared" to prepared)) {
                // A zero date reads back as MySQL prints it, or as NULL where the driver maps zero dates to NULL
                // (sqlx does in the binary protocol, like r2dbc-mysql's and JDBC's defaults).
                val zeroDate = row.get(2).asStringOrNull()
                val zeroDateTime = row.get(3).asStringOrNull()
                assertAll {
                    assertThat(row.get(0).asString(), "$name: time past 24 hours").isEqualTo("838:59:59")
                    assertThat(row.get(1).asString(), "$name: negative time").isEqualTo("-01:30:00")
                    assertThat(zeroDate == null || zeroDate == "0000-00-00", "$name: zero date ($zeroDate)").isTrue()
                    assertThat(
                        zeroDateTime == null || zeroDateTime.startsWith("0000-00-00 00:00:00"),
                        "$name: zero datetime ($zeroDateTime)"
                    ).isTrue()
                }
            }
        } finally {
            runCatching { cn.execute("drop table if exists $table") }
            cn.close()
        }
    }

    // YEAR (a 2-byte integer in the prepared protocol) and BIT (its bytes in either protocol) read as numbers through
    // both protocols.
    @Test
    fun `year and bit values read as numbers`(): Unit = runBlocking {
        val table = "t_year_bit_${Random.nextInt(1_000_000)}"
        db.execute("create table $table(y year, b1 bit(1), b8 bit(8), b64 bit(64))").getOrThrow()
        try {
            db.execute("insert into $table values (2024, b'1', b'10000000', b'1111111111111111111111111111111111111111111111111111111111111110')")
                .getOrThrow()
            val sql = "select y, b1, b8, b64 from $table"
            val plain = db.fetchAll(sql).getOrThrow().first()
            val prepared = db.fetchAll(Statement.create("$sql where 1 = :one").bind("one", 1)).getOrThrow().first()
            for ((name, row) in listOf("plain" to plain, "prepared" to prepared)) {
                assertAll {
                    assertThat(row.get("y").asInt(), "$name: year").isEqualTo(2024)
                    assertThat(row.get("b1").asBoolean(), "$name: bit(1)").isTrue()
                    assertThat(row.get("b8").asInt(), "$name: bit(8)").isEqualTo(128)
                    assertThat(row.get("b64").asString(), "$name: bit(64)").isEqualTo("18446744073709551614")
                }
            }
        } finally {
            db.execute("drop table if exists $table").getOrThrow()
        }
    }
}
