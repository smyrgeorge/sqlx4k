@file:OptIn(ExperimentalUuidApi::class)
@file:Suppress("SqlNoDataSourceInspection", "SqlDialectInspection")

package io.github.smyrgeorge.sqlx4k.sqlite

import assertk.assertAll
import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import assertk.assertions.isTrue
import io.github.smyrgeorge.sqlx4k.Statement
import io.github.smyrgeorge.sqlx4k.impl.extensions.asBoolean
import io.github.smyrgeorge.sqlx4k.impl.extensions.asDouble
import io.github.smyrgeorge.sqlx4k.impl.extensions.asFloat
import io.github.smyrgeorge.sqlx4k.impl.extensions.asInt
import io.github.smyrgeorge.sqlx4k.impl.extensions.asLocalDate
import io.github.smyrgeorge.sqlx4k.impl.extensions.asLocalTime
import io.github.smyrgeorge.sqlx4k.impl.extensions.asLong
import io.github.smyrgeorge.sqlx4k.impl.extensions.asShort
import io.github.smyrgeorge.sqlx4k.impl.extensions.asUuid
import kotlin.random.Random
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime

class CommonSQLitePreparedStatementTests(
    private val db: ISQLite
) {
    private fun newTable(): String = "t_ps_${Random.nextInt(1_000_000)}"

    // ---- Positional parameters (?) ----
    fun `positional params with basic types`() = runBlocking {
        val table = newTable()
        try {
            db.execute(
                """
                create table $table(
                    id integer primary key autoincrement,
                    v_text text,
                    v_int integer,
                    v_bigint integer,
                    v_smallint integer,
                    v_float real,
                    v_double real,
                    v_bool integer
                )
                """.trimIndent()
            ).getOrThrow()

            val insert = Statement.create(
                "insert into $table(v_text, v_int, v_bigint, v_smallint, v_float, v_double, v_bool) values (?, ?, ?, ?, ?, ?, ?)"
            )
                .bind(0, "hello")
                .bind(1, 42)
                .bind(2, 123456789L)
                .bind(3, 7.toShort())
                .bind(4, 1.5f)
                .bind(5, 2.5)
                .bind(6, true)
            db.execute(insert).getOrThrow()

            val select = Statement.create(
                "select v_text, v_int, v_bigint, v_smallint, v_float, v_double, v_bool from $table where v_int = ?"
            ).bind(0, 42)
            val row = db.fetchAll(select).getOrThrow().first()

            assertAll {
                assertThat(row.get(0).asString()).isEqualTo("hello")
                assertThat(row.get(1).asInt()).isEqualTo(42)
                assertThat(row.get(2).asLong()).isEqualTo(123456789L)
                assertThat(row.get(3).asShort()).isEqualTo(7)
                assertThat(row.get(4).asFloat()).isEqualTo(1.5f)
                assertThat(row.get(5).asDouble()).isEqualTo(2.5)
                assertThat(row.get(6).asBoolean()).isTrue()
            }
        } finally {
            runCatching { db.execute("drop table if exists $table") }
        }
    }

    // ---- Named parameters (:name) ----
    fun `named params with basic types`() = runBlocking {
        val table = newTable()
        try {
            db.execute(
                """
                create table $table(
                    id integer primary key autoincrement,
                    v_text text,
                    v_int integer,
                    v_bigint integer,
                    v_smallint integer,
                    v_float real,
                    v_double real,
                    v_bool integer
                )
                """.trimIndent()
            ).getOrThrow()

            val insert = Statement.create(
                "insert into $table(v_text, v_int, v_bigint, v_smallint, v_float, v_double, v_bool) values (:text, :int, :bigint, :smallint, :float, :double, :bool)"
            )
                .bind("text", "world")
                .bind("int", 99)
                .bind("bigint", 987654321L)
                .bind("smallint", 3.toShort())
                .bind("float", 3.14f)
                .bind("double", 2.718)
                .bind("bool", false)
            db.execute(insert).getOrThrow()

            val select = Statement.create(
                "select v_text, v_int, v_bigint, v_smallint, v_float, v_double, v_bool from $table where v_text = :text"
            ).bind("text", "world")
            val row = db.fetchAll(select).getOrThrow().first()

            assertAll {
                assertThat(row.get(0).asString()).isEqualTo("world")
                assertThat(row.get(1).asInt()).isEqualTo(99)
                assertThat(row.get(2).asLong()).isEqualTo(987654321L)
                assertThat(row.get(3).asShort()).isEqualTo(3)
                assertThat(row.get(4).asFloat()).isEqualTo(3.14f)
                assertThat(row.get(5).asDouble()).isEqualTo(2.718)
                assertThat(row.get(6).asBoolean()).isEqualTo(false)
            }
        } finally {
            runCatching { db.execute("drop table if exists $table") }
        }
    }

    // ---- Null parameter binding ----
    fun `null parameter binding`() = runBlocking {
        val table = newTable()
        try {
            db.execute(
                "create table $table(id integer primary key autoincrement, v_text text, v_int integer)"
            ).getOrThrow()

            // Positional null
            val insertPos = Statement.create("insert into $table(v_text, v_int) values (?, ?)")
                .bind(0, null)
                .bind(1, 1)
            db.execute(insertPos).getOrThrow()

            // Named null
            val insertNamed = Statement.create("insert into $table(v_text, v_int) values (:text, :int)")
                .bind("text", "present")
                .bind("int", null)
            db.execute(insertNamed).getOrThrow()

            // Verify positional null
            val row1 = db.fetchAll(
                Statement.create("select v_text, v_int from $table where v_int = ?").bind(0, 1)
            ).getOrThrow().first()
            assertThat(row1.get(0).asStringOrNull()).isNull()
            assertThat(row1.get(1).asInt()).isEqualTo(1)

            // Verify named null
            val row2 = db.fetchAll(
                Statement.create("select v_text, v_int from $table where v_text = :t").bind("t", "present")
            ).getOrThrow().first()
            assertThat(row2.get(0).asString()).isEqualTo("present")
            assertThat(row2.get(1).asStringOrNull()).isNull()
        } finally {
            runCatching { db.execute("drop table if exists $table") }
        }
    }

    // ---- Date, Time and UUID types ----
    // Note: SQLite stores dates/times as text and has no native timestamptz support.
    // LocalDateTime is excluded: encodeValue uses ISO 'T' separator but the shared
    // decoder expects space-separated format, causing a round-trip mismatch for SQLite.
    fun `date time and uuid types as parameters`() = runBlocking {
        val table = newTable()
        try {
            db.execute(
                """
                create table $table(
                    id integer primary key autoincrement,
                    v_date text,
                    v_time text,
                    v_uuid text
                )
                """.trimIndent()
            ).getOrThrow()

            val date = LocalDate(2025, 3, 25)
            val time = LocalTime(7, 31, 43)
            val uuid = Uuid.parse("22d64ef8-f6b3-43da-8869-2ee9d31be9d5")

            val insert = Statement.create(
                "insert into $table(v_date, v_time, v_uuid) values (:date, :time, :uuid)"
            )
                .bind("date", date)
                .bind("time", time)
                .bind("uuid", uuid)
            db.execute(insert).getOrThrow()

            val row = db.fetchAll(
                Statement.create("select v_date, v_time, v_uuid from $table where v_uuid = :uuid")
                    .bind("uuid", uuid)
            ).getOrThrow().first()

            assertAll {
                assertThat(row.get(0).asLocalDate()).isEqualTo(date)
                assertThat(row.get(1).asLocalTime()).isEqualTo(time)
                assertThat(row.get(2).asUuid()).isEqualTo(uuid)
            }
        } finally {
            runCatching { db.execute("drop table if exists $table") }
        }
    }

    // ---- Type cast preservation ----
    fun `type cast preservation with params`() = runBlocking {
        // SQLite uses cast() expressions; verify params work correctly inside them
        val select = Statement.create("select cast(? as text) as casted, cast(:val as integer) as named_cast")
            .bind(0, "hello")
            .bind("val", 42)
        val row = db.fetchAll(select).getOrThrow().first()

        assertAll {
            assertThat(row.get(0).asString()).isEqualTo("hello")
            assertThat(row.get(1).asInt()).isEqualTo(42)
        }
    }

    // ---- Reused named parameter ----
    fun `reused named parameter`() = runBlocking {
        val table = newTable()
        try {
            db.execute(
                "create table $table(id integer primary key autoincrement, a integer, b integer)"
            ).getOrThrow()

            db.execute(
                Statement.create("insert into $table(a, b) values (1, 2), (3, 4), (2, 2)")
            ).getOrThrow()

            // Use :v twice in the same query
            val select = Statement.create(
                "select a, b from $table where a = :v or b = :v order by a"
            ).bind("v", 2)
            val rows = db.fetchAll(select).getOrThrow()
            val results = rows.map { it.get(0).asInt() to it.get(1).asInt() }

            assertThat(results).isEqualTo(listOf(1 to 2, 2 to 2))
        } finally {
            runCatching { db.execute("drop table if exists $table") }
        }
    }

    // ---- String escaping (SQL injection prevention) ----
    fun `string values with special characters`() = runBlocking {
        val table = newTable()
        try {
            db.execute(
                "create table $table(id integer primary key autoincrement, v_text text)"
            ).getOrThrow()

            val tricky = "O'Reilly's \"book\" -- drop table; SELECT 1"
            val insert = Statement.create("insert into $table(v_text) values (:v)")
                .bind("v", tricky)
            db.execute(insert).getOrThrow()

            val select = Statement.create("select v_text from $table where v_text = :v")
                .bind("v", tricky)
            val row = db.fetchAll(select).getOrThrow().first()
            assertThat(row.get(0).asString()).isEqualTo(tricky)
        } finally {
            runCatching { db.execute("drop table if exists $table") }
        }
    }

    // ---- Collection expansion: List with IN ----
    fun `list expansion with IN clause`() = runBlocking {
        val table = newTable()
        try {
            db.execute(
                "create table $table(id integer primary key autoincrement, name text not null)"
            ).getOrThrow()

            for (name in listOf("alice", "bob", "carol", "dave")) {
                db.execute(
                    Statement.create("insert into $table(name) values (?)").bind(0, name)
                ).getOrThrow()
            }

            val select = Statement.create(
                "select name from $table where name in ? order by name"
            ).bind(0, listOf("alice", "carol", "dave"))
            val rows = db.fetchAll(select).getOrThrow()
            val names = rows.map { it.get(0).asString() }
            assertThat(names).isEqualTo(listOf("alice", "carol", "dave"))
        } finally {
            runCatching { db.execute("drop table if exists $table") }
        }
    }

    // ---- Collection expansion: Set with IN ----
    fun `set expansion with IN clause`() = runBlocking {
        val table = newTable()
        try {
            db.execute(
                "create table $table(id integer primary key autoincrement, v integer not null)"
            ).getOrThrow()

            for (v in listOf(10, 20, 30, 40, 50)) {
                db.execute(
                    Statement.create("insert into $table(v) values (?)").bind(0, v)
                ).getOrThrow()
            }

            val select = Statement.create(
                "select v from $table where v in :ids order by v"
            ).bind("ids", setOf(20, 40))
            val rows = db.fetchAll(select).getOrThrow()
            val values = rows.map { it.get(0).asInt() }
            assertThat(values).isEqualTo(listOf(20, 40))
        } finally {
            runCatching { db.execute("drop table if exists $table") }
        }
    }

    // ---- Collection expansion: IntArray ----
    fun `intArray expansion with IN clause`() = runBlocking {
        val table = newTable()
        try {
            db.execute(
                "create table $table(id integer primary key autoincrement, v integer not null)"
            ).getOrThrow()

            for (v in listOf(1, 2, 3, 4, 5)) {
                db.execute(
                    Statement.create("insert into $table(v) values (?)").bind(0, v)
                ).getOrThrow()
            }

            val select = Statement.create(
                "select v from $table where v in ? order by v"
            ).bind(0, intArrayOf(2, 4))
            val rows = db.fetchAll(select).getOrThrow()
            val values = rows.map { it.get(0).asInt() }
            assertThat(values).isEqualTo(listOf(2, 4))
        } finally {
            runCatching { db.execute("drop table if exists $table") }
        }
    }

    // ---- Collection expansion: LongArray ----
    fun `longArray expansion with IN clause`() = runBlocking {
        val table = newTable()
        try {
            db.execute(
                "create table $table(id integer primary key autoincrement, v integer not null)"
            ).getOrThrow()

            for (v in listOf(10L, 20L, 30L, 40L, 50L)) {
                db.execute(
                    Statement.create("insert into $table(v) values (?)").bind(0, v)
                ).getOrThrow()
            }

            val select = Statement.create(
                "select v from $table where v in ? order by v"
            ).bind(0, longArrayOf(20L, 40L))
            val rows = db.fetchAll(select).getOrThrow()
            val values = rows.map { it.get(0).asLong() }
            assertThat(values).isEqualTo(listOf(20L, 40L))
        } finally {
            runCatching { db.execute("drop table if exists $table") }
        }
    }

    // ---- Collection expansion: Set with custom types ----
    fun `set expansion with custom types`() = runBlocking {
        val table = newTable()
        try {
            db.execute(
                "create table $table(id integer primary key autoincrement, label text not null)"
            ).getOrThrow()

            for (label in listOf("alpha", "beta", "gamma", "delta")) {
                db.execute(
                    Statement.create("insert into $table(label) values (?)").bind(0, label)
                ).getOrThrow()
            }

            // Named :tags with Set<Tag> — each Tag resolved through TagEncoder
            val select = Statement.create(
                "select label from $table where label in :tags order by label"
            ).bind("tags", setOf(Tag("alpha"), Tag("gamma")))
            val rows = db.fetchAll(select).getOrThrow()
            val labels = rows.map { it.get(0).asString() }
            assertThat(labels).isEqualTo(listOf("alpha", "gamma"))
        } finally {
            runCatching { db.execute("drop table if exists $table") }
        }
    }

    // ---- Collection expansion mixed with scalar params ----
    fun `collection expansion mixed with scalar params`() = runBlocking {
        val table = newTable()
        try {
            db.execute(
                "create table $table(id integer primary key autoincrement, name text not null, score integer not null)"
            ).getOrThrow()

            for ((name, score) in listOf("alice" to 90, "bob" to 75, "carol" to 85, "dave" to 60)) {
                db.execute(
                    Statement.create("insert into $table(name, score) values (?, ?)")
                        .bind(0, name)
                        .bind(1, score)
                ).getOrThrow()
            }

            val select = Statement.create(
                "select name, score from $table where score >= :min and name in :names order by name"
            )
                .bind("min", 70)
                .bind("names", listOf("alice", "bob", "dave"))
            val rows = db.fetchAll(select).getOrThrow()
            val results = rows.map { it.get(0).asString() to it.get(1).asInt() }
            assertThat(results).isEqualTo(listOf("alice" to 90, "bob" to 75))
        } finally {
            runCatching { db.execute("drop table if exists $table") }
        }
    }

    // ---- Custom type via ValueEncoder ----
    fun `custom type with encoder as positional param`() = runBlocking {
        val table = newTable()
        try {
            db.execute(
                "create table $table(id integer primary key autoincrement, amount integer not null, currency text not null)"
            ).getOrThrow()

            val insert = Statement.create(
                "insert into $table(amount, currency) values (?, ?)"
            )
                .bind(0, Money(42, "USD"))
                .bind(1, "ignored") // currency comes from Money encoder
            db.execute(insert).getOrThrow()

            val select = Statement.create(
                "select amount from $table where amount = ?"
            ).bind(0, Money(42, "USD"))
            val row = db.fetchAll(select).getOrThrow().first()
            assertThat(row.get(0).asInt()).isEqualTo(42)
        } finally {
            runCatching { db.execute("drop table if exists $table") }
        }
    }

    fun `custom type with encoder as named param`() = runBlocking {
        val table = newTable()
        try {
            db.execute(
                "create table $table(id integer primary key autoincrement, label text not null)"
            ).getOrThrow()

            val insert = Statement.create(
                "insert into $table(label) values (:label)"
            ).bind("label", Tag("important"))
            db.execute(insert).getOrThrow()

            val select = Statement.create(
                "select label from $table where label = :label"
            ).bind("label", Tag("important"))
            val row = db.fetchAll(select).getOrThrow().first()
            assertThat(row.get(0).asString()).isEqualTo("important")
        } finally {
            runCatching { db.execute("drop table if exists $table") }
        }
    }

    fun `custom type in collection expansion`() = runBlocking {
        val table = newTable()
        try {
            db.execute(
                "create table $table(id integer primary key autoincrement, label text not null)"
            ).getOrThrow()

            for (tag in listOf("alpha", "beta", "gamma", "delta")) {
                db.execute(
                    Statement.create("insert into $table(label) values (?)").bind(0, tag)
                ).getOrThrow()
            }

            val select = Statement.create(
                "select label from $table where label in ? order by label"
            ).bind(0, listOf(Tag("alpha"), Tag("gamma")))
            val rows = db.fetchAll(select).getOrThrow()
            val labels = rows.map { it.get(0).asString() }
            assertThat(labels).isEqualTo(listOf("alpha", "gamma"))
        } finally {
            runCatching { db.execute("drop table if exists $table") }
        }
    }

    fun `custom type mixed with primitives and collections`() = runBlocking {
        val table = newTable()
        try {
            db.execute(
                "create table $table(id integer primary key autoincrement, label text not null, amount integer not null, score integer not null)"
            ).getOrThrow()

            for ((label, amount, score) in listOf(
                Triple("alpha", 10, 80),
                Triple("beta", 20, 70),
                Triple("gamma", 30, 90),
                Triple("delta", 40, 60)
            )) {
                db.execute(
                    Statement.create("insert into $table(label, amount, score) values (?, ?, ?)")
                        .bind(0, label)
                        .bind(1, amount)
                        .bind(2, score)
                ).getOrThrow()
            }

            val select = Statement.create(
                "select label, amount, score from $table where label in :labels and amount >= :money and score > ? order by label"
            )
                .bind("labels", listOf(Tag("alpha"), Tag("gamma"), Tag("delta")))
                .bind("money", Money(15, "USD"))
                .bind(0, 50)
            val rows = db.fetchAll(select).getOrThrow()
            val results = rows.map { Triple(it.get(0).asString(), it.get(1).asInt(), it.get(2).asInt()) }
            assertThat(results).isEqualTo(listOf(Triple("delta", 40, 60), Triple("gamma", 30, 90)))
        } finally {
            runCatching { db.execute("drop table if exists $table") }
        }
    }

    fun `reused named param with custom type`() = runBlocking {
        val table = newTable()
        try {
            db.execute(
                "create table $table(id integer primary key autoincrement, col_a text not null, col_b text not null)"
            ).getOrThrow()

            db.execute(
                Statement.create("insert into $table(col_a, col_b) values ('x', 'x'), ('x', 'y'), ('y', 'y')")
            ).getOrThrow()

            // Use :tag twice — both occurrences should resolve via TagEncoder
            val select = Statement.create(
                "select col_a, col_b from $table where col_a = :tag or col_b = :tag order by col_a, col_b"
            ).bind("tag", Tag("x"))
            val rows = db.fetchAll(select).getOrThrow()
            val results = rows.map { it.get(0).asString() to it.get(1).asString() }
            assertThat(results).isEqualTo(listOf("x" to "x", "x" to "y"))
        } finally {
            runCatching { db.execute("drop table if exists $table") }
        }
    }

    fun `custom type with type cast`() = runBlocking {
        // Custom type + SQLite cast() must not confuse the scanner
        val select = Statement.create("select cast(:tag as text) as casted, cast(? as integer) as amount")
            .bind("tag", Tag("hello"))
            .bind(0, Money(7, "GBP"))
        val row = db.fetchAll(select).getOrThrow().first()

        assertAll {
            assertThat(row.get(0).asString()).isEqualTo("hello")
            assertThat(row.get(1).asInt()).isEqualTo(7)
        }
    }

    fun `enum as parameter`() = runBlocking {
        val table = newTable()
        try {
            db.execute(
                "create table $table(id integer primary key autoincrement, status text not null)"
            ).getOrThrow()

            val insert = Statement.create("insert into $table(status) values (?)")
                .bind(0, TestStatus.ACTIVE)
            db.execute(insert).getOrThrow()

            val select = Statement.create("select status from $table where status = :s")
                .bind("s", TestStatus.ACTIVE)
            val row = db.fetchAll(select).getOrThrow().first()
            assertThat(row.get(0).asString()).isEqualTo("ACTIVE")
        } finally {
            runCatching { db.execute("drop table if exists $table") }
        }
    }

    fun `char as parameter`() = runBlocking {
        val select = Statement.create("select cast(? as text) as ch")
            .bind(0, 'Z')
        val row = db.fetchAll(select).getOrThrow().first()
        assertThat(row.get(0).asString()).isEqualTo("Z")
    }

    fun `byte as parameter`() = runBlocking {
        val select = Statement.create("select cast(? as integer) as b")
            .bind(0, 5.toByte())
        val row = db.fetchAll(select).getOrThrow().first()
        assertThat(row.get(0).asShort()).isEqualTo(5.toShort())
    }

    // ---- Multiple rows with multiple params ----
    fun `batch insert and filtered select with multiple params`() = runBlocking {
        val table = newTable()
        try {
            db.execute(
                "create table $table(id integer primary key autoincrement, name text not null, score integer not null)"
            ).getOrThrow()

            for ((name, score) in listOf("alice" to 90, "bob" to 75, "carol" to 85, "dave" to 60)) {
                db.execute(
                    Statement.create("insert into $table(name, score) values (?, ?)")
                        .bind(0, name)
                        .bind(1, score)
                ).getOrThrow()
            }

            val select = Statement.create(
                "select name, score from $table where score >= :min and score < :max order by score desc"
            )
                .bind("min", 75)
                .bind("max", 90)
            val rows = db.fetchAll(select).getOrThrow()
            val results = rows.map { it.get(0).asString() to it.get(1).asInt() }

            assertThat(results).isEqualTo(listOf("carol" to 85, "bob" to 75))
        } finally {
            runCatching { db.execute("drop table if exists $table") }
        }
    }
}
