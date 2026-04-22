@file:Suppress("SqlNoDataSourceInspection", "SqlDialectInspection")

package io.github.smyrgeorge.sqlx4k.mysql

import assertk.assertAll
import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import assertk.assertions.isTrue
import io.github.smyrgeorge.sqlx4k.Statement
import io.github.smyrgeorge.sqlx4k.impl.extensions.asByteArray
import io.github.smyrgeorge.sqlx4k.impl.extensions.asByteArrayOrNull
import io.github.smyrgeorge.sqlx4k.impl.extensions.asInt
import kotlin.random.Random
import kotlinx.coroutines.runBlocking

class CommonMySQLByteArrayTests(
    private val db: IMySQL
) {
    private fun newTable(): String = "t_ba_${Random.nextInt(1_000_000)}"

    // ---- Positional parameter (?) ----
    fun `binary positional param`() = runBlocking {
        val payload = byteArrayOf(0x01, 0x02, 0x03, 0x04)
        val select = Statement.create("select hex(?) as b").bind(0, payload)
        val row = db.fetchAll(select).getOrThrow().first()
        assertThat(row.get(0).asByteArray().toList()).isEqualTo(payload.toList())
    }

    // ---- Named parameter (:name) ----
    fun `binary named param`() = runBlocking {
        val payload = byteArrayOf(0x10, 0x20, 0x30)
        val select = Statement.create("select hex(:data) as b").bind("data", payload)
        val row = db.fetchAll(select).getOrThrow().first()
        assertThat(row.get(0).asByteArray().toList()).isEqualTo(payload.toList())
    }

    // ---- Null binary binding (positional) ----
    fun `binary null positional binding`() = runBlocking {
        val table = newTable()
        try {
            db.execute("create table $table(id int primary key, data varbinary(64))").getOrThrow()

            val insert = Statement.create("insert into $table(id, data) values (?, ?)")
                .bind(0, 1)
                .bind(1, null)
            db.execute(insert).getOrThrow()

            val select = Statement.create("select hex(data) as data from $table where id = ?").bind(0, 1)
            val row = db.fetchAll(select).getOrThrow().first()
            assertThat(row.get(0).asByteArrayOrNull()).isNull()
        } finally {
            runCatching { db.execute("drop table if exists $table").getOrThrow() }
        }
    }

    // ---- Null binary binding (named) ----
    fun `binary null named binding`() = runBlocking {
        val table = newTable()
        try {
            db.execute("create table $table(id int primary key, data varbinary(64))").getOrThrow()

            val insert = Statement.create("insert into $table(id, data) values (:id, :data)")
                .bind("id", 1)
                .bind("data", null)
            db.execute(insert).getOrThrow()

            val select = Statement.create("select hex(data) as data from $table where id = :id").bind("id", 1)
            val row = db.fetchAll(select).getOrThrow().first()
            assertThat(row.get(0).asByteArrayOrNull()).isNull()
        } finally {
            runCatching { db.execute("drop table if exists $table").getOrThrow() }
        }
    }

    // ---- Empty binary binding ----
    fun `binary empty array binding`() = runBlocking {
        val payload = ByteArray(0)
        val select = Statement.create("select hex(?) as b").bind(0, payload)
        val row = db.fetchAll(select).getOrThrow().first()
        assertThat(row.get(0).asByteArray().toList()).isEqualTo(emptyList())
    }

    // ---- Full byte range 0x00..0xFF ----
    fun `binary full byte range binding`() = runBlocking {
        val payload = ByteArray(256) { it.toByte() }
        val select = Statement.create("select hex(:b) as b").bind("b", payload)
        val row = db.fetchAll(select).getOrThrow().first()
        val actual = row.get(0).asByteArray()
        assertAll {
            assertThat(actual.size).isEqualTo(256)
            assertThat(actual.toList()).isEqualTo(payload.toList())
        }
    }

    // ---- Embedded null bytes ----
    fun `binary with embedded nulls`() = runBlocking {
        val payload = byteArrayOf(0x00, 0x01, 0x00, 0x02, 0x00, 0x00, 0x03)
        val select = Statement.create("select hex(?) as b").bind(0, payload)
        val row = db.fetchAll(select).getOrThrow().first()
        assertThat(row.get(0).asByteArray().toList()).isEqualTo(payload.toList())
    }

    // ---- Insert + select roundtrip in a real table ----
    fun `binary insert and select roundtrip`() = runBlocking {
        val table = newTable()
        try {
            db.execute("create table $table(id int primary key, data blob not null)").getOrThrow()

            val payload = byteArrayOf(0xDE.toByte(), 0xAD.toByte(), 0xBE.toByte(), 0xEF.toByte())
            val insert = Statement.create("insert into $table(id, data) values (:id, :data)")
                .bind("id", 1)
                .bind("data", payload)
            db.execute(insert).getOrThrow()

            val select = Statement.create("select hex(data) as data from $table where id = ?").bind(0, 1)
            val row = db.fetchAll(select).getOrThrow().first()
            assertThat(row.get(0).asByteArray().toList()).isEqualTo(payload.toList())
        } finally {
            runCatching { db.execute("drop table if exists $table").getOrThrow() }
        }
    }

    // ---- Large payload binding (8 KB) ----
    fun `binary large payload binding`() = runBlocking {
        val size = 8 * 1024
        val payload = ByteArray(size) { (it * 31).toByte() }
        val select = Statement.create("select hex(?) as b").bind(0, payload)
        val row = db.fetchAll(select).getOrThrow().first()
        val actual = row.get(0).asByteArray()
        assertAll {
            assertThat(actual.size).isEqualTo(size)
            assertThat(actual.toList()).isEqualTo(payload.toList())
        }
    }

    // ---- UTF-8 text roundtrip via binary param ----
    fun `binary utf8 roundtrip via param`() = runBlocking {
        val text = "Γειά σου κόσμε — 🌍"
        val payload = text.encodeToByteArray()
        val select = Statement.create("select hex(:b) as b").bind("b", payload)
        val row = db.fetchAll(select).getOrThrow().first()
        assertThat(row.get(0).asByteArray().decodeToString()).isEqualTo(text)
    }

    // ---- Reused named binary parameter ----
    fun `reused named binary param`() = runBlocking {
        val table = newTable()
        try {
            db.execute("create table $table(id int primary key, a varbinary(64) not null, b varbinary(64) not null)").getOrThrow()

            val payload = byteArrayOf(0x11, 0x22, 0x33)
            val insert = Statement.create("insert into $table(id, a, b) values (:id, :v, :v)")
                .bind("id", 1)
                .bind("v", payload)
            db.execute(insert).getOrThrow()

            val select = Statement.create("select hex(a) as a, hex(b) as b from $table where id = :id").bind("id", 1)
            val row = db.fetchAll(select).getOrThrow().first()
            assertAll {
                assertThat(row.get(0).asByteArray().toList()).isEqualTo(payload.toList())
                assertThat(row.get(1).asByteArray().toList()).isEqualTo(payload.toList())
            }
        } finally {
            runCatching { db.execute("drop table if exists $table").getOrThrow() }
        }
    }

    // ---- Type cast preservation with binary params ----
    fun `binary param with type cast preservation`() = runBlocking {
        val a = byteArrayOf(0x01, 0x02)
        val b = byteArrayOf(0x03, 0x04)
        val select = Statement.create("select hex(cast(? as binary)) as positional, hex(cast(:val as binary)) as named")
            .bind(0, a)
            .bind("val", b)
        val row = db.fetchAll(select).getOrThrow().first()
        assertAll {
            assertThat(row.get(0).asByteArray().toList()).isEqualTo(a.toList())
            assertThat(row.get(1).asByteArray().toList()).isEqualTo(b.toList())
        }
    }

    // ---- Filter by binary equality ----
    fun `binary equality filter with prepared statement`() = runBlocking {
        val table = newTable()
        try {
            db.execute("create table $table(id int primary key, data varbinary(64) not null)").getOrThrow()

            val targets = mapOf(
                1 to byteArrayOf(0x01, 0x02, 0x03),
                2 to byteArrayOf(0xAA.toByte(), 0xBB.toByte()),
                3 to "sqlx4k".encodeToByteArray(),
            )
            for ((id, data) in targets) {
                db.execute(
                    Statement.create("insert into $table(id, data) values (?, ?)")
                        .bind(0, id)
                        .bind(1, data)
                ).getOrThrow()
            }

            val needle = targets[2]!!
            val select = Statement.create("select id from $table where data = :needle").bind("needle", needle)
            val row = db.fetchAll(select).getOrThrow().first()
            assertThat(row.get(0).asInt()).isEqualTo(2)
        } finally {
            runCatching { db.execute("drop table if exists $table").getOrThrow() }
        }
    }

    // ---- Batch insert and filtered select ----
    fun `batch insert binary and filter by id`() = runBlocking {
        val table = newTable()
        try {
            db.execute("create table $table(id int primary key, data varbinary(255) not null)").getOrThrow()

            val payloads = listOf(
                1 to byteArrayOf(0x00, 0x01, 0x02),
                2 to byteArrayOf(0xFF.toByte(), 0xFE.toByte(), 0xFD.toByte()),
                3 to ByteArray(128) { it.toByte() },
                4 to "hello world".encodeToByteArray(),
            )
            for ((id, data) in payloads) {
                db.execute(
                    Statement.create("insert into $table(id, data) values (:id, :data)")
                        .bind("id", id)
                        .bind("data", data)
                ).getOrThrow()
            }

            val rows = db.fetchAll(
                Statement.create("select id, hex(data) as data from $table where id in ? order by id asc")
                    .bind(0, listOf(1, 3, 4))
            ).getOrThrow().toList()

            assertAll {
                assertThat(rows.size).isEqualTo(3)
                assertThat(rows[0].get(0).asInt()).isEqualTo(1)
                assertThat(rows[0].get(1).asByteArray().toList()).isEqualTo(payloads[0].second.toList())
                assertThat(rows[1].get(0).asInt()).isEqualTo(3)
                assertThat(rows[1].get(1).asByteArray().toList()).isEqualTo(payloads[2].second.toList())
                assertThat(rows[2].get(0).asInt()).isEqualTo(4)
                assertThat(rows[2].get(1).asByteArray().toList()).isEqualTo(payloads[3].second.toList())
            }
        } finally {
            runCatching { db.execute("drop table if exists $table").getOrThrow() }
        }
    }

    // ---- Single byte boundary values ----
    fun `binary single byte boundary values`() = runBlocking {
        val cases = listOf(
            byteArrayOf(0x00),
            byteArrayOf(0x7F),
            byteArrayOf(0x80.toByte()),
            byteArrayOf(0xFF.toByte()),
        )
        for (payload in cases) {
            val select = Statement.create("select hex(?) as b").bind(0, payload)
            val row = db.fetchAll(select).getOrThrow().first()
            assertThat(row.get(0).asByteArray().toList()).isEqualTo(payload.toList())
        }
    }

    // ---- Update binary via named param ----
    fun `update binary via named param`() = runBlocking {
        val table = newTable()
        try {
            db.execute("create table $table(id int primary key, data varbinary(64) not null)").getOrThrow()

            val initial = byteArrayOf(0x01, 0x02)
            db.execute(
                Statement.create("insert into $table(id, data) values (?, ?)")
                    .bind(0, 1)
                    .bind(1, initial)
            ).getOrThrow()

            val updated = byteArrayOf(0x99.toByte(), 0x88.toByte(), 0x77)
            db.execute(
                Statement.create("update $table set data = :data where id = :id")
                    .bind("data", updated)
                    .bind("id", 1)
            ).getOrThrow()

            val row = db.fetchAll(
                Statement.create("select hex(data) as data from $table where id = ?").bind(0, 1)
            ).getOrThrow().first()
            assertThat(row.get(0).asByteArray().toList()).isEqualTo(updated.toList())
        } finally {
            runCatching { db.execute("drop table if exists $table").getOrThrow() }
        }
    }

    // ---- Binary in WHERE with length on bound param ----
    fun `binary param with length`() = runBlocking {
        val payload = ByteArray(100) { it.toByte() }
        val select = Statement.create("select length(?) as len").bind(0, payload)
        val row = db.fetchAll(select).getOrThrow().first()
        assertThat(row.get(0).asInt()).isEqualTo(100)
    }

    // ---- Mixed: binary + other param types ----
    fun `binary mixed with other params`() = runBlocking {
        val table = newTable()
        try {
            db.execute(
                "create table $table(id int primary key, label text not null, data varbinary(64) not null, amount int not null)"
            ).getOrThrow()

            val payload = byteArrayOf(0x10, 0x20, 0x30, 0x40)
            db.execute(
                Statement.create("insert into $table(id, label, data, amount) values (?, ?, ?, ?)")
                    .bind(0, 1)
                    .bind(1, "first")
                    .bind(2, payload)
                    .bind(3, 42)
            ).getOrThrow()

            val row = db.fetchAll(
                Statement.create("select label, hex(data) as data, amount from $table where id = :id and label = :label")
                    .bind("id", 1)
                    .bind("label", "first")
            ).getOrThrow().first()

            assertAll {
                assertThat(row.get(0).asString()).isEqualTo("first")
                assertThat(row.get(1).asByteArray().toList()).isEqualTo(payload.toList())
                assertThat(row.get(2).asInt()).isEqualTo(42)
            }
        } finally {
            runCatching { db.execute("drop table if exists $table").getOrThrow() }
        }
    }

    // ---- bindNull explicit ByteArray type ----
    fun `bindNull with ByteArray type`() = runBlocking {
        val table = newTable()
        try {
            db.execute("create table $table(id int primary key, data varbinary(64))").getOrThrow()

            val insert = Statement.create("insert into $table(id, data) values (?, ?)")
                .bind(0, 1)
                .bindNull(1, ByteArray::class)
            db.execute(insert).getOrThrow()

            val row = db.fetchAll(
                Statement.create("select hex(data) as data from $table where id = ?").bind(0, 1)
            ).getOrThrow().first()
            assertThat(row.get(0).asByteArrayOrNull()).isNull()
        } finally {
            runCatching { db.execute("drop table if exists $table").getOrThrow() }
        }
    }

    // ---- Repeated byte pattern ----
    fun `binary repeated byte pattern binding`() = runBlocking {
        val payload = ByteArray(512) { 0xAB.toByte() }
        val select = Statement.create("select hex(?) as b").bind(0, payload)
        val row = db.fetchAll(select).getOrThrow().first()
        val actual = row.get(0).asByteArray()
        assertAll {
            assertThat(actual.size).isEqualTo(512)
            assertThat(actual.all { it == 0xAB.toByte() }).isTrue()
        }
    }
}
