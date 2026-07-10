@file:Suppress("SqlNoDataSourceInspection", "SqlDialectInspection")

package io.github.smyrgeorge.sqlx4k.postgres

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import io.github.smyrgeorge.sqlx4k.Statement
import kotlin.random.Random
import kotlinx.coroutines.runBlocking

/**
 * Round-trip tests for the PostgreSQL `json` and `jsonb` types.
 *
 * The important cases bind their input as a prepared-statement parameter (`?::jsonb` /
 * `:name::jsonb`) so the query runs over the extended/binary protocol — that is the path where
 * `jsonb` values carry a 1-byte version header on the wire, which the driver must strip when
 * decoding to text. Simple-query (`fetchAll(sql)`) variants are included too, so both protocols
 * are covered.
 *
 * Semantics being pinned:
 * - `jsonb` normalizes: insignificant whitespace removed, `": "`/`", "` spacing, object keys
 *   ordered by length-then-lexicographically, duplicate keys collapsed keeping the last.
 * - `json` preserves the input text verbatim (whitespace and key order).
 */
class CommonPostgreSQLJsonTests(
    private val db: IPostgresSQL
) {
    private fun newTable(): String = "t_json_${Random.nextInt(1_000_000)}"

    private suspend fun scalar(statement: Statement): String? =
        db.fetchAll(statement).getOrThrow().first().get(0).asStringOrNull()

    // ============================================================================
    // jsonb via bound parameter (extended/binary protocol) — canonical rendering
    // ============================================================================

    fun `jsonb bound param renders canonical object`() = runBlocking {
        val s = Statement.create("select ?::jsonb").bind(0, """{"a":1,"b":2}""")
        assertThat(scalar(s)).isEqualTo("""{"a": 1, "b": 2}""")
    }

    fun `jsonb bound param normalizes whitespace and key order`() = runBlocking {
        val s = Statement.create("select ?::jsonb").bind(0, """{ "b" : 2,  "a":1, "cc": 3 }""")
        assertThat(scalar(s)).isEqualTo("""{"a": 1, "b": 2, "cc": 3}""")
    }

    fun `jsonb bound param renders canonical array`() = runBlocking {
        val s = Statement.create("select ?::jsonb").bind(0, "[1,2,3]")
        assertThat(scalar(s)).isEqualTo("[1, 2, 3]")
    }

    fun `jsonb bound param scalar string`() = runBlocking {
        val s = Statement.create("select ?::jsonb").bind(0, "\"hi\"")
        assertThat(scalar(s)).isEqualTo("\"hi\"")
    }

    fun `jsonb bound param scalar number preserves scale`() = runBlocking {
        val s = Statement.create("select ?::jsonb").bind(0, "1.50")
        assertThat(scalar(s)).isEqualTo("1.50")
    }

    fun `jsonb bound param boolean`() = runBlocking {
        val s = Statement.create("select ?::jsonb").bind(0, "true")
        assertThat(scalar(s)).isEqualTo("true")
    }

    fun `jsonb bound param json null is not sql null`() = runBlocking {
        val s = Statement.create("select ?::jsonb").bind(0, "null")
        // A jsonb 'null' is a value, not SQL NULL.
        assertThat(scalar(s)).isEqualTo("null")
    }

    fun `jsonb bound param duplicate keys keep last`() = runBlocking {
        val s = Statement.create("select ?::jsonb").bind(0, """{"a": 1, "a": 2}""")
        assertThat(scalar(s)).isEqualTo("""{"a": 2}""")
    }

    fun `jsonb bound param nested structure round-trips`() = runBlocking {
        val doc = """{"a": {"b": [1, 2, {"c": true}]}}"""
        val s = Statement.create("select ?::jsonb").bind(0, doc)
        assertThat(scalar(s)).isEqualTo(doc)
    }

    fun `jsonb bound param preserves nested null values`() = runBlocking {
        val doc = """{"n": null, "s": "x"}"""
        val s = Statement.create("select ?::jsonb").bind(0, doc)
        assertThat(scalar(s)).isEqualTo(doc)
    }

    fun `jsonb bound param preserves escaped quotes`() = runBlocking {
        val doc = """{"msg": "he said \"hi\""}"""
        val s = Statement.create("select ?::jsonb").bind(0, doc)
        assertThat(scalar(s)).isEqualTo(doc)
    }

    fun `jsonb bound param preserves unicode`() = runBlocking {
        val doc = """{"emoji": "grin 😀"}"""
        val s = Statement.create("select ?::jsonb").bind(0, doc)
        assertThat(scalar(s)).isEqualTo(doc)
    }

    fun `jsonb named bound param round-trips`() = runBlocking {
        val s = Statement.create("select :doc::jsonb").bind("doc", """{"k":"v"}""")
        assertThat(scalar(s)).isEqualTo("""{"k": "v"}""")
    }

    // ============================================================================
    // json via bound parameter — text is preserved verbatim
    // ============================================================================

    fun `json bound param preserves exact text and key order`() = runBlocking {
        val doc = """{"b":2,"a":1}"""
        val s = Statement.create("select ?::json").bind(0, doc)
        assertThat(scalar(s)).isEqualTo(doc)
    }

    fun `json bound param preserves whitespace`() = runBlocking {
        val doc = """{ "a" :   1 }"""
        val s = Statement.create("select ?::json").bind(0, doc)
        assertThat(scalar(s)).isEqualTo(doc)
    }

    // ============================================================================
    // Table column round-trips
    // ============================================================================

    fun `jsonb column round-trips through a table`() = runBlocking {
        val table = newTable()
        try {
            db.execute("create table $table(id int primary key, doc jsonb)").getOrThrow()
            db.execute(
                Statement.create("insert into $table(id, doc) values (?, ?::jsonb)")
                    .bind(0, 1)
                    .bind(1, """{"hello":"world","n":10}""")
            ).getOrThrow()

            val select = Statement.create("select doc from $table where id = ?").bind(0, 1)
            assertThat(scalar(select)).isEqualTo("""{"n": 10, "hello": "world"}""")
        } finally {
            runCatching { db.execute("drop table if exists $table").getOrThrow() }
        }
    }

    fun `json column round-trips through a table preserving text`() = runBlocking {
        val table = newTable()
        try {
            db.execute("create table $table(id int primary key, doc json)").getOrThrow()
            val doc = """{"hello":"world","n":10}"""
            db.execute(
                Statement.create("insert into $table(id, doc) values (:id, :doc::json)")
                    .bind("id", 1)
                    .bind("doc", doc)
            ).getOrThrow()

            val select = Statement.create("select doc from $table where id = :id").bind("id", 1)
            assertThat(scalar(select)).isEqualTo(doc)
        } finally {
            runCatching { db.execute("drop table if exists $table").getOrThrow() }
        }
    }

    fun `null jsonb column reads as null`() = runBlocking {
        val table = newTable()
        try {
            db.execute("create table $table(id int primary key, doc jsonb)").getOrThrow()
            db.execute(
                Statement.create("insert into $table(id, doc) values (?, ?)").bind(0, 1).bind(1, null)
            ).getOrThrow()

            val select = Statement.create("select doc from $table where id = ?").bind(0, 1)
            assertThat(scalar(select)).isNull()
        } finally {
            runCatching { db.execute("drop table if exists $table").getOrThrow() }
        }
    }

    fun `null json column reads as null`() = runBlocking {
        val table = newTable()
        try {
            db.execute("create table $table(id int primary key, doc json)").getOrThrow()
            db.execute(
                Statement.create("insert into $table(id, doc) values (?, ?)").bind(0, 1).bind(1, null)
            ).getOrThrow()

            val select = Statement.create("select doc from $table where id = ?").bind(0, 1)
            assertThat(scalar(select)).isNull()
        } finally {
            runCatching { db.execute("drop table if exists $table").getOrThrow() }
        }
    }

    // ============================================================================
    // jsonb produced by server-side functions / operators (still binary on the wire)
    // ============================================================================

    fun `jsonb_build_object renders canonical`() = runBlocking {
        val s = Statement.create("select jsonb_build_object('x', ?::int, 'y', ?::text)")
            .bind(0, 1)
            .bind(1, "z")
        assertThat(scalar(s)).isEqualTo("""{"x": 1, "y": "z"}""")
    }

    fun `jsonb delete-key operator round-trips`() = runBlocking {
        val s = Statement.create("select (?::jsonb) - 'a'").bind(0, """{"a": 1, "b": 2}""")
        assertThat(scalar(s)).isEqualTo("""{"b": 2}""")
    }

    fun `jsonb path extraction returns scalar text`() = runBlocking {
        val s = Statement.create("select (?::jsonb) #>> '{a,b}'").bind(0, """{"a": {"b": 5}}""")
        assertThat(scalar(s)).isEqualTo("5")
    }

    // ============================================================================
    // Simple-query protocol (fetchAll(sql)) — jsonb/json read as text
    // ============================================================================

    fun `jsonb literal via simple query reads canonical`() = runBlocking {
        val row = db.fetchAll("""select '{ "a":1 , "b" : 2 }'::jsonb""").getOrThrow().first()
        assertThat(row.get(0).asString()).isEqualTo("""{"a": 1, "b": 2}""")
    }

    fun `json literal via simple query preserves text`() = runBlocking {
        val row = db.fetchAll("""select '{"b":2,"a":1}'::json""").getOrThrow().first()
        assertThat(row.get(0).asString()).isEqualTo("""{"b":2,"a":1}""")
    }
}
