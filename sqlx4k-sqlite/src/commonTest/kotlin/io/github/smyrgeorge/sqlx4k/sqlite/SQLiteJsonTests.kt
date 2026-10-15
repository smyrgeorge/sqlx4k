@file:Suppress("SqlNoDataSourceInspection", "SqlDialectInspection")

package io.github.smyrgeorge.sqlx4k.sqlite

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import io.github.smyrgeorge.sqlx4k.Statement
import io.github.smyrgeorge.sqlx4k.impl.extensions.asInt
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlinx.coroutines.runBlocking

class SQLiteJsonTests : SQLiteTestBase() {

    private val db = testSQLite("SQLiteJsonTests")

    @AfterTest
    fun tearDown(): Unit = runBlocking { db.close().getOrThrow() }

    // Robolectric's SQLite is built without the JSON functions.
    private val jsonUnsupported = testPlatform == TestPlatform.Android

    private fun newTable(): String = "t_json_${Random.nextInt(1_000_000)}"

    private suspend fun scalar(statement: Statement): String? =
        db.fetchAll(statement).getOrThrow().first().get(0).asStringOrNull()

    private suspend fun int(statement: Statement): Int =
        db.fetchAll(statement).getOrThrow().first().get(0).asInt()

    // ============================================================================
    // json() — minification + key-order preservation
    // ============================================================================

    @Test
    fun `json minifies whitespace and preserves key order`(): Unit = runBlocking {
        if (jsonUnsupported) return@runBlocking
        val s = Statement.create("select json(?)").bind(0, """{ "b":2, "a" : 1 }""")
        assertThat(scalar(s)).isEqualTo("""{"b":2,"a":1}""")
    }

    @Test
    fun `json minifies an array`(): Unit = runBlocking {
        if (jsonUnsupported) return@runBlocking
        assertThat(scalar(Statement.create("select json(?)").bind(0, "[1,  2 , 3]"))).isEqualTo("[1,2,3]")
    }

    @Test
    fun `json_extract reads a top-level field`(): Unit = runBlocking {
        if (jsonUnsupported) return@runBlocking
        val s = Statement.create("select json_extract(?, '$.name')").bind(0, """{"name":"alice","age":30}""")
        assertThat(scalar(s)).isEqualTo("alice")
    }

    @Test
    fun `json_extract reads a nested path`(): Unit = runBlocking {
        if (jsonUnsupported) return@runBlocking
        val s = Statement.create("select json_extract(?, '$.a.b[1]')").bind(0, """{"a":{"b":[10,20]}}""")
        assertThat(int(s)).isEqualTo(20)
    }

    @Test
    fun `json_type reports the value type`(): Unit = runBlocking {
        if (jsonUnsupported) return@runBlocking
        assertThat(scalar(Statement.create("select json_type(?)").bind(0, """{"a":1}"""))).isEqualTo("object")
        assertThat(scalar(Statement.create("select json_type(?)").bind(0, "[1,2]"))).isEqualTo("array")
    }

    @Test
    fun `json_valid distinguishes valid and invalid json`(): Unit = runBlocking {
        if (jsonUnsupported) return@runBlocking
        assertThat(int(Statement.create("select json_valid(?)").bind(0, """{"a":1}"""))).isEqualTo(1)
        assertThat(int(Statement.create("select json_valid(?)").bind(0, "{bad}"))).isEqualTo(0)
    }

    @Test
    fun `json_array_length counts elements`(): Unit = runBlocking {
        if (jsonUnsupported) return@runBlocking
        assertThat(int(Statement.create("select json_array_length(?)").bind(0, "[1,2,3,4]"))).isEqualTo(4)
    }

    // ============================================================================
    // jsonb() — binary blob form
    // ============================================================================

    @Test
    fun `jsonb produces a blob`(): Unit = runBlocking {
        if (jsonUnsupported) return@runBlocking
        assertThat(scalar(Statement.create("select typeof(jsonb(?))").bind(0, """{"a":1}"""))).isEqualTo("blob")
    }

    @Test
    fun `json of jsonb round-trips to canonical text`(): Unit = runBlocking {
        if (jsonUnsupported) return@runBlocking
        val s = Statement.create("select json(jsonb(?))").bind(0, """{ "x" : 10, "y":20 }""")
        assertThat(scalar(s)).isEqualTo("""{"x":10,"y":20}""")
    }

    @Test
    fun `json_extract reads a field from a jsonb blob`(): Unit = runBlocking {
        if (jsonUnsupported) return@runBlocking
        val s = Statement.create("select json_extract(jsonb(?), '$.n')").bind(0, """{"k":"v","n":5}""")
        assertThat(int(s)).isEqualTo(5)
    }

    @Test
    fun `jsonb_object builds canonical json`(): Unit = runBlocking {
        if (jsonUnsupported) return@runBlocking
        val s = Statement.create("select json(jsonb_object('a', ?, 'b', ?))").bind(0, 1).bind(1, "x")
        assertThat(scalar(s)).isEqualTo("""{"a":1,"b":"x"}""")
    }

    @Test
    fun `jsonb preserves a nested structure`(): Unit = runBlocking {
        if (jsonUnsupported) return@runBlocking
        val doc = """{"a":{"b":[1,2,{"c":true}]}}"""
        assertThat(scalar(Statement.create("select json(jsonb(?))").bind(0, doc))).isEqualTo(doc)
    }

    @Test
    fun `jsonb preserves escaped quotes and unicode`(): Unit = runBlocking {
        if (jsonUnsupported) return@runBlocking
        val doc = """{"msg":"he said \"hi\"","emoji":"grin 😀"}"""
        assertThat(scalar(Statement.create("select json(jsonb(?))").bind(0, doc))).isEqualTo(doc)
    }

    // ============================================================================
    // Table round-trips
    // ============================================================================

    @Test
    fun `json text column round-trips through a table`(): Unit = runBlocking {
        if (jsonUnsupported) return@runBlocking
        val table = newTable()
        try {
            db.execute("create table $table(id integer primary key, doc text)").getOrThrow()
            db.execute(
                Statement.create("insert into $table(id, doc) values (?, json(?))")
                    .bind(0, 1)
                    .bind(1, """{ "hello":"world","n":10 }""")
            ).getOrThrow()

            val select = Statement.create("select json(doc) from $table where id = ?").bind(0, 1)
            assertThat(scalar(select)).isEqualTo("""{"hello":"world","n":10}""")
        } finally {
            runCatching { db.execute("drop table if exists $table").getOrThrow() }
        }
    }

    @Test
    fun `jsonb blob column round-trips through a table`(): Unit = runBlocking {
        if (jsonUnsupported) return@runBlocking
        val table = newTable()
        try {
            db.execute("create table $table(id integer primary key, doc blob)").getOrThrow()
            db.execute(
                Statement.create("insert into $table(id, doc) values (:id, jsonb(:doc))")
                    .bind("id", 1)
                    .bind("doc", """{"k":"v","n":5}""")
            ).getOrThrow()

            assertThat(scalar(Statement.create("select json(doc) from $table where id = :id").bind("id", 1)))
                .isEqualTo("""{"k":"v","n":5}""")
            assertThat(
                int(
                    Statement.create("select json_extract(doc, '$.n') from $table where id = :id").bind("id", 1)
                )
            )
                .isEqualTo(5)
        } finally {
            runCatching { db.execute("drop table if exists $table").getOrThrow() }
        }
    }

    @Test
    fun `null json column reads as null`(): Unit = runBlocking {
        if (jsonUnsupported) return@runBlocking
        val table = newTable()
        try {
            db.execute("create table $table(id integer primary key, doc text)").getOrThrow()
            db.execute(
                Statement.create("insert into $table(id, doc) values (?, ?)").bind(0, 1).bind(1, null)
            ).getOrThrow()

            assertThat(scalar(Statement.create("select doc from $table where id = ?").bind(0, 1))).isNull()
        } finally {
            runCatching { db.execute("drop table if exists $table").getOrThrow() }
        }
    }
}
