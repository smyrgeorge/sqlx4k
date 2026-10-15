@file:Suppress("SqlNoDataSourceInspection", "SqlDialectInspection")

package io.github.smyrgeorge.sqlx4k.mysql

import assertk.assertAll
import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import io.github.smyrgeorge.sqlx4k.Statement
import io.github.smyrgeorge.sqlx4k.impl.extensions.asInstant
import io.github.smyrgeorge.sqlx4k.impl.extensions.asInstantOrNull
import io.github.smyrgeorge.sqlx4k.impl.extensions.asInt
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.time.Instant
import kotlinx.coroutines.runBlocking

class MySQLInstantTests {

    private val db = testMySQL()

    @AfterTest
    fun tearDown(): Unit = runBlocking { db.close().getOrThrow() }

    private fun newTable(): String = "t_ins_${Random.nextInt(1_000_000)}"

    // ---- Positional parameter (?) ----
    @Test
    fun `instant positional param`(): Unit = runBlocking {
        val table = newTable()
        try {
            db.execute("create table $table(id int primary key, ts datetime(6) not null)").getOrThrow()

            val ts = Instant.parse("2025-03-25T07:31:43.330068Z")
            db.execute(
                Statement.create("insert into $table(id, ts) values (?, ?)").bind(0, 1).bind(1, ts)
            ).getOrThrow()

            val row = db.fetchAll(
                Statement.create("select ts from $table where id = ?").bind(0, 1)
            ).getOrThrow().first()
            assertThat(row.get(0).asInstant()).isEqualTo(ts)
        } finally {
            runCatching { db.execute("drop table if exists $table").getOrThrow() }
        }
    }

    // ---- Named parameter (:name) ----
    @Test
    fun `instant named param`(): Unit = runBlocking {
        val table = newTable()
        try {
            db.execute("create table $table(id int primary key, ts datetime(6) not null)").getOrThrow()

            val ts = Instant.parse("2024-12-31T23:59:59.999999Z")
            db.execute(
                Statement.create("insert into $table(id, ts) values (:id, :ts)").bind("id", 1).bind("ts", ts)
            ).getOrThrow()

            val row = db.fetchAll(
                Statement.create("select ts from $table where id = :id").bind("id", 1)
            ).getOrThrow().first()
            assertThat(row.get(0).asInstant()).isEqualTo(ts)
        } finally {
            runCatching { db.execute("drop table if exists $table").getOrThrow() }
        }
    }

    // ---- Null instant binding (positional) ----
    @Test
    fun `instant null positional binding`(): Unit = runBlocking {
        val table = newTable()
        try {
            db.execute("create table $table(id int primary key, ts datetime(6))").getOrThrow()

            db.execute(
                Statement.create("insert into $table(id, ts) values (?, ?)").bind(0, 1).bind(1, null)
            ).getOrThrow()

            val row = db.fetchAll(
                Statement.create("select ts from $table where id = ?").bind(0, 1)
            ).getOrThrow().first()
            assertThat(row.get(0).asInstantOrNull()).isNull()
        } finally {
            runCatching { db.execute("drop table if exists $table").getOrThrow() }
        }
    }

    // ---- Null instant binding (named) ----
    @Test
    fun `instant null named binding`(): Unit = runBlocking {
        val table = newTable()
        try {
            db.execute("create table $table(id int primary key, ts datetime(6))").getOrThrow()

            db.execute(
                Statement.create("insert into $table(id, ts) values (:id, :ts)")
                    .bind("id", 1).bind("ts", null)
            ).getOrThrow()

            val row = db.fetchAll(
                Statement.create("select ts from $table where id = :id").bind("id", 1)
            ).getOrThrow().first()
            assertThat(row.get(0).asInstantOrNull()).isNull()
        } finally {
            runCatching { db.execute("drop table if exists $table").getOrThrow() }
        }
    }

    // ---- Epoch zero ----
    @Test
    fun `instant epoch zero`(): Unit = runBlocking {
        val table = newTable()
        try {
            db.execute("create table $table(id int primary key, ts datetime(6) not null)").getOrThrow()

            val ts = Instant.parse("1970-01-01T00:00:01Z")
            db.execute(
                Statement.create("insert into $table(id, ts) values (?, ?)").bind(0, 1).bind(1, ts)
            ).getOrThrow()

            val row = db.fetchAll(
                Statement.create("select ts from $table where id = ?").bind(0, 1)
            ).getOrThrow().first()
            assertThat(row.get(0).asInstant()).isEqualTo(ts)
        } finally {
            runCatching { db.execute("drop table if exists $table").getOrThrow() }
        }
    }

    // ---- Microsecond precision ----
    @Test
    fun `instant with microsecond precision`(): Unit = runBlocking {
        val table = newTable()
        try {
            db.execute("create table $table(id int primary key, ts datetime(6) not null)").getOrThrow()

            val ts = Instant.parse("2025-06-15T12:34:56.123456Z")
            db.execute(
                Statement.create("insert into $table(id, ts) values (:id, :ts)").bind("id", 1).bind("ts", ts)
            ).getOrThrow()

            val row = db.fetchAll(
                Statement.create("select ts from $table where id = :id").bind("id", 1)
            ).getOrThrow().first()
            assertThat(row.get(0).asInstant()).isEqualTo(ts)
        } finally {
            runCatching { db.execute("drop table if exists $table").getOrThrow() }
        }
    }

    // ---- Past date ----
    @Test
    fun `instant past date 1985`(): Unit = runBlocking {
        val table = newTable()
        try {
            db.execute("create table $table(id int primary key, ts datetime(6) not null)").getOrThrow()

            val ts = Instant.parse("1985-10-26T09:00:00Z")
            db.execute(
                Statement.create("insert into $table(id, ts) values (?, ?)").bind(0, 1).bind(1, ts)
            ).getOrThrow()

            val row = db.fetchAll(
                Statement.create("select ts from $table where id = ?").bind(0, 1)
            ).getOrThrow().first()
            assertThat(row.get(0).asInstant()).isEqualTo(ts)
        } finally {
            runCatching { db.execute("drop table if exists $table").getOrThrow() }
        }
    }

    // ---- Far future ----
    @Test
    fun `instant far future 2100`(): Unit = runBlocking {
        val table = newTable()
        try {
            db.execute("create table $table(id int primary key, ts datetime(6) not null)").getOrThrow()

            val ts = Instant.parse("2100-12-31T23:59:59.999999Z")
            db.execute(
                Statement.create("insert into $table(id, ts) values (?, ?)").bind(0, 1).bind(1, ts)
            ).getOrThrow()

            val row = db.fetchAll(
                Statement.create("select ts from $table where id = ?").bind(0, 1)
            ).getOrThrow().first()
            assertThat(row.get(0).asInstant()).isEqualTo(ts)
        } finally {
            runCatching { db.execute("drop table if exists $table").getOrThrow() }
        }
    }

    // ---- Reused named parameter ----
    @Test
    fun `reused named instant param`(): Unit = runBlocking {
        val table = newTable()
        try {
            db.execute(
                "create table $table(id int primary key, a datetime(6) not null, b datetime(6) not null)"
            ).getOrThrow()

            val ts = Instant.parse("2025-03-25T07:31:43.330068Z")
            db.execute(
                Statement.create("insert into $table(id, a, b) values (:id, :t, :t)")
                    .bind("id", 1).bind("t", ts)
            ).getOrThrow()

            val row = db.fetchAll(
                Statement.create("select a, b from $table where id = :id").bind("id", 1)
            ).getOrThrow().first()

            assertAll {
                assertThat(row.get(0).asInstant()).isEqualTo(ts)
                assertThat(row.get(1).asInstant()).isEqualTo(ts)
            }
        } finally {
            runCatching { db.execute("drop table if exists $table").getOrThrow() }
        }
    }

    // ---- Type cast preservation with params ----
    @Test
    fun `instant param with type cast preservation`(): Unit = runBlocking {
        // ? / :val inside CAST must not confuse the param scanner.
        val a = Instant.parse("2025-01-02T03:04:05.060708Z")
        val b = Instant.parse("2026-09-08T07:06:05.040302Z")
        val select = Statement.create(
            "select cast(? as datetime(6)) as positional, cast(:val as datetime(6)) as named"
        )
            .bind(0, a)
            .bind("val", b)
        val row = db.fetchAll(select).getOrThrow().first()
        assertAll {
            assertThat(row.get(0).asInstant()).isEqualTo(a)
            assertThat(row.get(1).asInstant()).isEqualTo(b)
        }
    }

    // ---- Filter by instant equality ----
    @Test
    fun `instant equality filter with prepared statement`(): Unit = runBlocking {
        val table = newTable()
        try {
            db.execute("create table $table(id int primary key, ts datetime(6) not null)").getOrThrow()

            val a = Instant.parse("2025-01-15T10:00:00Z")
            val b = Instant.parse("2025-06-15T10:00:00Z")
            val c = Instant.parse("2025-12-15T10:00:00Z")
            for ((id, ts) in listOf(1 to a, 2 to b, 3 to c)) {
                db.execute(
                    Statement.create("insert into $table(id, ts) values (?, ?)").bind(0, id).bind(1, ts)
                ).getOrThrow()
            }

            val row = db.fetchAll(
                Statement.create("select id from $table where ts = :needle").bind("needle", b)
            ).getOrThrow().first()
            assertThat(row.get(0).asInt()).isEqualTo(2)
        } finally {
            runCatching { db.execute("drop table if exists $table").getOrThrow() }
        }
    }

    // ---- Range filter ----
    @Test
    fun `instant range filter`(): Unit = runBlocking {
        val table = newTable()
        try {
            db.execute("create table $table(id int primary key, ts datetime(6) not null)").getOrThrow()

            val data = mapOf(
                1 to Instant.parse("2025-01-01T00:00:00Z"),
                2 to Instant.parse("2025-03-15T12:00:00Z"),
                3 to Instant.parse("2025-06-30T23:59:59Z"),
                4 to Instant.parse("2025-12-31T23:59:59Z"),
            )
            for ((id, ts) in data) {
                db.execute(
                    Statement.create("insert into $table(id, ts) values (?, ?)").bind(0, id).bind(1, ts)
                ).getOrThrow()
            }

            val lo = Instant.parse("2025-03-01T00:00:00Z")
            val hi = Instant.parse("2025-07-01T00:00:00Z")
            val rows = db.fetchAll(
                Statement.create("select id from $table where ts >= :lo and ts < :hi order by id asc")
                    .bind("lo", lo).bind("hi", hi)
            ).getOrThrow().toList()

            assertAll {
                assertThat(rows.size).isEqualTo(2)
                assertThat(rows[0].get(0).asInt()).isEqualTo(2)
                assertThat(rows[1].get(0).asInt()).isEqualTo(3)
            }
        } finally {
            runCatching { db.execute("drop table if exists $table").getOrThrow() }
        }
    }

    // ---- Update via named param ----
    @Test
    fun `update instant via named param`(): Unit = runBlocking {
        val table = newTable()
        try {
            db.execute("create table $table(id int primary key, ts datetime(6) not null)").getOrThrow()

            val initial = Instant.parse("2025-01-01T00:00:00Z")
            db.execute(
                Statement.create("insert into $table(id, ts) values (?, ?)").bind(0, 1).bind(1, initial)
            ).getOrThrow()

            val updated = Instant.parse("2025-12-31T23:59:59.999999Z")
            db.execute(
                Statement.create("update $table set ts = :ts where id = :id")
                    .bind("ts", updated).bind("id", 1)
            ).getOrThrow()

            val row = db.fetchAll(
                Statement.create("select ts from $table where id = ?").bind(0, 1)
            ).getOrThrow().first()
            assertThat(row.get(0).asInstant()).isEqualTo(updated)
        } finally {
            runCatching { db.execute("drop table if exists $table").getOrThrow() }
        }
    }

    // ---- Mixed with other params ----
    @Test
    fun `instant mixed with other params`(): Unit = runBlocking {
        val table = newTable()
        try {
            db.execute(
                "create table $table(id int primary key, label text not null, ts datetime(6) not null, amount int not null)"
            ).getOrThrow()

            val ts = Instant.parse("2025-03-25T07:31:43.330068Z")
            db.execute(
                Statement.create("insert into $table(id, label, ts, amount) values (?, ?, ?, ?)")
                    .bind(0, 1).bind(1, "first").bind(2, ts).bind(3, 42)
            ).getOrThrow()

            val row = db.fetchAll(
                Statement.create("select label, ts, amount from $table where id = :id and label = :label")
                    .bind("id", 1).bind("label", "first")
            ).getOrThrow().first()

            assertAll {
                assertThat(row.get(0).asString()).isEqualTo("first")
                assertThat(row.get(1).asInstant()).isEqualTo(ts)
                assertThat(row.get(2).asInt()).isEqualTo(42)
            }
        } finally {
            runCatching { db.execute("drop table if exists $table").getOrThrow() }
        }
    }

    // ---- bindNull Instant type ----
    @Test
    fun `bindNull with Instant type`(): Unit = runBlocking {
        val table = newTable()
        try {
            db.execute("create table $table(id int primary key, ts datetime(6))").getOrThrow()

            val insert = Statement.create("insert into $table(id, ts) values (?, ?)")
                .bind(0, 1)
                .bindNull(1, Instant::class)
            db.execute(insert).getOrThrow()

            val row = db.fetchAll(
                Statement.create("select ts from $table where id = ?").bind(0, 1)
            ).getOrThrow().first()
            assertThat(row.get(0).asInstantOrNull()).isNull()
        } finally {
            runCatching { db.execute("drop table if exists $table").getOrThrow() }
        }
    }

    // ---- Batch insert + IN filter ----
    @Test
    fun `batch insert instants and filter by id`(): Unit = runBlocking {
        val table = newTable()
        try {
            db.execute("create table $table(id int primary key, ts datetime(6) not null)").getOrThrow()

            val payloads = listOf(
                1 to Instant.parse("2025-01-01T00:00:00Z"),
                2 to Instant.parse("2025-04-01T12:00:00.123456Z"),
                3 to Instant.parse("2025-07-01T23:59:59.999999Z"),
                4 to Instant.parse("2025-10-01T06:30:15Z"),
            )
            for ((id, ts) in payloads) {
                db.execute(
                    Statement.create("insert into $table(id, ts) values (:id, :ts)")
                        .bind("id", id).bind("ts", ts)
                ).getOrThrow()
            }

            val rows = db.fetchAll(
                Statement.create("select id, ts from $table where id in ? order by id asc")
                    .bind(0, listOf(1, 3, 4))
            ).getOrThrow().toList()

            assertAll {
                assertThat(rows.size).isEqualTo(3)
                assertThat(rows[0].get(0).asInt()).isEqualTo(1)
                assertThat(rows[0].get(1).asInstant()).isEqualTo(payloads[0].second)
                assertThat(rows[1].get(0).asInt()).isEqualTo(3)
                assertThat(rows[1].get(1).asInstant()).isEqualTo(payloads[2].second)
                assertThat(rows[2].get(0).asInt()).isEqualTo(4)
                assertThat(rows[2].get(1).asInstant()).isEqualTo(payloads[3].second)
            }
        } finally {
            runCatching { db.execute("drop table if exists $table").getOrThrow() }
        }
    }

    // ---- Ordering ----
    @Test
    fun `instant ordering`(): Unit = runBlocking {
        val table = newTable()
        try {
            db.execute("create table $table(id int primary key, ts datetime(6) not null)").getOrThrow()

            val data = listOf(
                3 to Instant.parse("2025-07-01T00:00:00Z"),
                1 to Instant.parse("2025-01-01T00:00:00Z"),
                4 to Instant.parse("2025-10-01T00:00:00Z"),
                2 to Instant.parse("2025-04-01T00:00:00Z"),
            )
            for ((id, ts) in data) {
                db.execute(
                    Statement.create("insert into $table(id, ts) values (?, ?)")
                        .bind(0, id).bind(1, ts)
                ).getOrThrow()
            }

            val rows = db.fetchAll("select id from $table order by ts asc").getOrThrow().toList()
            assertAll {
                assertThat(rows[0].get(0).asInt()).isEqualTo(1)
                assertThat(rows[1].get(0).asInt()).isEqualTo(2)
                assertThat(rows[2].get(0).asInt()).isEqualTo(3)
                assertThat(rows[3].get(0).asInt()).isEqualTo(4)
            }
        } finally {
            runCatching { db.execute("drop table if exists $table").getOrThrow() }
        }
    }
}
