@file:Suppress("SqlNoDataSourceInspection", "SqlDialectInspection")

package io.github.smyrgeorge.sqlx4k.mysql

import assertk.assertAll
import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import io.github.smyrgeorge.sqlx4k.Statement
import io.github.smyrgeorge.sqlx4k.impl.extensions.asInt
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlinx.coroutines.runBlocking

class MySQLJsonTests {

    private val db = testMySQL()

    @AfterTest
    fun tearDown(): Unit = runBlocking { db.close().getOrThrow() }

    private fun newTable(): String = "t_json_${Random.nextInt(1_000_000)}"

    private suspend fun scalar(statement: Statement): String? =
        db.fetchAll(statement).getOrThrow().first().get(0).asStringOrNull()

    /** Runs [sql] through the text protocol and the binary (prepared) protocol, and expects [expected] from both. */
    private fun assertBoth(sql: String, expected: String?): Unit = runBlocking {
        assertAll {
            assertThat(db.fetchAll(sql).getOrThrow().first().get(0).asStringOrNull(), "text").isEqualTo(expected)
            assertThat(scalar(Statement.create(sql)), "binary").isEqualTo(expected)
        }
    }

    // MySQL stores JSON in a binary form and prints it normalized: `", "` and `": "` separators, object keys
    // sorted (shorter keys first, then by byte order) and duplicate keys collapsed keeping the last.

    @Test
    fun `json bound param renders canonical object`(): Unit = runBlocking {
        val s = Statement.create("select cast(? as json)").bind(0, """{"a":1,"b":2}""")
        assertThat(scalar(s)).isEqualTo("""{"a": 1, "b": 2}""")
    }

    @Test
    fun `json bound param normalizes whitespace and key order`(): Unit = runBlocking {
        val s = Statement.create("select cast(? as json)").bind(0, """{ "bb" : 1,   "a":2 }""")
        assertThat(scalar(s)).isEqualTo("""{"a": 2, "bb": 1}""")
    }

    @Test
    fun `json bound param duplicate keys keep last`(): Unit = runBlocking {
        val s = Statement.create("select cast(? as json)").bind(0, """{"a": 1, "a": 2}""")
        assertThat(scalar(s)).isEqualTo("""{"a": 2}""")
    }

    @Test
    fun `json bound param renders canonical array`(): Unit = runBlocking {
        val s = Statement.create("select cast(? as json)").bind(0, """[1,"two",true,null,{"x":[]}]""")
        assertThat(scalar(s)).isEqualTo("""[1, "two", true, null, {"x": []}]""")
    }

    @Test
    fun `json bound param scalars`(): Unit = runBlocking {
        assertAll {
            assertThat(scalar(Statement.create("select cast(? as json)").bind(0, "\"text\""))).isEqualTo("\"text\"")
            // JSON numbers with a fraction are stored as doubles.
            assertThat(scalar(Statement.create("select cast(? as json)").bind(0, "1.50"))).isEqualTo("1.5")
            assertThat(scalar(Statement.create("select cast(? as json)").bind(0, "true"))).isEqualTo("true")
        }
    }

    @Test
    fun `json null is not sql null`(): Unit = runBlocking {
        val s = Statement.create("select cast(? as json)").bind(0, "null")
        assertThat(scalar(s)).isEqualTo("null")
    }

    @Test
    fun `json bound param preserves escaped quotes and unicode`(): Unit = runBlocking {
        val s = Statement.create("select cast(? as json)").bind(0, """{"q":"say \"hi\"","u":"καλημέρα ✓"}""")
        assertThat(scalar(s)).isEqualTo("""{"q": "say \"hi\"", "u": "καλημέρα ✓"}""")
    }

    @Test
    fun `json literals read the same through both protocols`(): Unit =
        assertBoth("""select cast('{"b": [1, 2], "a": {"c": null}}' as json)""", """{"a": {"c": null}, "b": [1, 2]}""")

    @Test
    fun `json_object renders canonical json`(): Unit = runBlocking {
        val s = Statement.create("select json_object('b', ?, 'a', ?)").bind(0, 2).bind(1, "x")
        assertThat(scalar(s)).isEqualTo("""{"a": "x", "b": 2}""")
    }

    @Test
    fun `json_extract returns json and the unquoting operator returns text`(): Unit = runBlocking {
        val doc = """{"name":"alice","tags":["a","b"],"n":10}"""
        assertAll {
            assertThat(scalar(Statement.create("select json_extract(cast(? as json), '$.name')").bind(0, doc)))
                .isEqualTo("\"alice\"")
            assertThat(scalar(Statement.create("select json_unquote(json_extract(cast(? as json), '$.name'))").bind(0, doc)))
                .isEqualTo("alice")
            assertThat(scalar(Statement.create("select json_extract(cast(? as json), '$.tags')").bind(0, doc)))
                .isEqualTo("""["a", "b"]""")
            assertThat(scalar(Statement.create("select json_extract(cast(? as json), '$.missing')").bind(0, doc)))
                .isNull()
        }
    }

    @Test
    fun `json functions report type length and validity`(): Unit = runBlocking {
        assertAll {
            assertThat(scalar(Statement.create("select json_type(cast(? as json))").bind(0, "[1, 2]"))).isEqualTo("ARRAY")
            assertThat(scalar(Statement.create("select json_type(cast(? as json))").bind(0, "{}"))).isEqualTo("OBJECT")
            assertThat(db.fetchAll(Statement.create("select json_length(cast(? as json))").bind(0, "[1, 2, 3]"))
                .getOrThrow().first().get(0).asInt()).isEqualTo(3)
            // Cast to text: a server-side prepare (native) would otherwise type the parameter as JSON and reject it.
            assertThat(db.fetchAll(Statement.create("select json_valid(cast(? as char))").bind(0, "{\"a\": 1}"))
                .getOrThrow().first().get(0).asInt()).isEqualTo(1)
            assertThat(db.fetchAll(Statement.create("select json_valid(cast(? as char))").bind(0, "{a: 1}"))
                .getOrThrow().first().get(0).asInt()).isEqualTo(0)
        }
    }

    @Test
    fun `json column round-trips through a table`(): Unit = runBlocking {
        val table = newTable()
        try {
            db.execute("create table $table(id int primary key, doc json)").getOrThrow()
            db.execute(
                Statement.create("insert into $table(id, doc) values (:id, :doc)")
                    .bind("id", 1)
                    .bind("doc", """{"n":10,"hello":"world"}""")
            ).getOrThrow()
            db.execute(Statement.create("insert into $table(id, doc) values (:id, :doc)").bind("id", 2).bind("doc", null))
                .getOrThrow()

            val expected = listOf("""{"n": 10, "hello": "world"}""", null)
            val sql = "select doc from $table order by id"
            assertAll {
                assertThat(db.fetchAll(sql).getOrThrow().map { it.get(0).asStringOrNull() }, "text").isEqualTo(expected)
                assertThat(db.fetchAll(Statement.create(sql)).getOrThrow().map { it.get(0).asStringOrNull() }, "binary")
                    .isEqualTo(expected)
            }
        } finally {
            runCatching { db.execute("drop table if exists $table").getOrThrow() }
        }
    }

    @Test
    fun `json column can be filtered by a path`(): Unit = runBlocking {
        val table = newTable()
        try {
            db.execute("create table $table(id int primary key, doc json)").getOrThrow()
            listOf(1 to """{"kind":"a"}""", 2 to """{"kind":"b"}""", 3 to """{"kind":"a"}""").forEach { (id, doc) ->
                db.execute(Statement.create("insert into $table(id, doc) values (?, ?)").bind(0, id).bind(1, doc))
                    .getOrThrow()
            }
            val ids = db.fetchAll(Statement.create("select id from $table where doc->>'$.kind' = ? order by id").bind(0, "a"))
                .getOrThrow().map { it.get(0).asInt() }
            assertThat(ids).isEqualTo(listOf(1, 3))
        } finally {
            runCatching { db.execute("drop table if exists $table").getOrThrow() }
        }
    }
}
