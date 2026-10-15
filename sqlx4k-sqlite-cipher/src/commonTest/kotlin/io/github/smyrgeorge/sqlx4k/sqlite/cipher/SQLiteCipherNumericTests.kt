@file:Suppress("SqlNoDataSourceInspection", "SqlDialectInspection")

package io.github.smyrgeorge.sqlx4k.sqlite.cipher

import assertk.assertAll
import assertk.assertThat
import assertk.assertions.isEqualTo
import io.github.smyrgeorge.sqlx4k.ResultSet
import io.github.smyrgeorge.sqlx4k.Statement
import io.github.smyrgeorge.sqlx4k.impl.extensions.asDouble
import io.github.smyrgeorge.sqlx4k.impl.extensions.asInt
import io.github.smyrgeorge.sqlx4k.impl.extensions.asLong
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlinx.coroutines.runBlocking

class SQLiteCipherNumericTests : SQLiteCipherTestBase() {

    private val db = testSQLiteCipher("SQLiteCipherNumericTests")

    @AfterTest
    fun tearDown(): Unit = runBlocking { db.close().getOrThrow() }

    private fun newTable(): String = "t_numeric_${Random.nextInt(1_000_000)}"

    private suspend fun simple(sql: String): ResultSet.Row = db.fetchAll(sql).getOrThrow().first()
    private suspend fun prepared(sql: String): ResultSet.Row = db.fetchAll(Statement.create(sql)).getOrThrow().first()

    /** Runs [sql] both as plain SQL and as a prepared statement and checks that each column renders as [expected]. */
    private fun assertDecodes(sql: String, vararg expected: String?): Unit = runBlocking {
        val simple = simple(sql)
        val prepared = prepared(sql)
        assertAll {
            expected.forEachIndexed { i, e ->
                assertThat(simple.get(i).asStringOrNull(), "simple[$i]").isEqualTo(e)
                assertThat(prepared.get(i).asStringOrNull(), "prepared[$i]").isEqualTo(e)
            }
        }
    }

    @Test
    fun `numeric values should decode through prepared statements`(): Unit = runBlocking {
        val st = Statement.create(
            """
            select cast(:a as numeric) as fixed,
                   cast(:b as numeric) as negative_fraction,
                   (select sum(x) from (select 1 as x union all select 2 union all select 4) where x >= :min) as total,
                   (select avg(x) from (select 1 as x union all select 2 union all select 4) where x >= :min) as mean,
                   cast(:c as numeric) as big,
                   0 + :min as zero_plus
            """.trimIndent()
        )
            .bind("a", "1.50")
            .bind("b", "-0.0042")
            .bind("c", "123456789.0001")
            .bind("min", 0)
        val row = db.fetchAll(st).getOrThrow().first()

        assertAll {
            assertThat(row.get(0).asString()).isEqualTo("1.5")
            assertThat(row.get(1).asString()).isEqualTo("-0.0042")
            assertThat(row.get(2).asString()).isEqualTo("7")
            assertThat(row.get(3).asString()).isEqualTo("2.3333333333333335")
            assertThat(row.get(4).asString()).isEqualTo("123456789.0001")
            assertThat(row.get(5).asString()).isEqualTo("0")
        }
    }

    @Test
    fun `numeric literals render identically`(): Unit = assertDecodes(
        """
        select 1.5,
               -1234.5,
               -0.0042,
               0.00000123,
               123456789.0001,
               10000,
               0,
               0.0,
               cast(1 as numeric(10,2)),
               cast('1.50' as numeric),
               cast('abc' as numeric)
        """.trimIndent(),
        "1.5", "-1234.5", "-0.0042", "1.23e-06", "123456789.0001", "10000", "0", "0.0", "1", "1.5", "0"
    )

    @Test
    fun `numeric extremes render identically`(): Unit = assertDecodes(
        """
        select 9223372036854775807,
               -9223372036854775808,
               3.0,
               1e16,
               123456789012345678.0,
               1e20,
               -2.5e-7,
               0.1 + 0.2,
               1.0 / 3
        """.trimIndent(),
        "9223372036854775807", "-9223372036854775808", "3.0", "10000000000000000.0", "1.2345678901234568e+17",
        "1.0e+20", "-2.5e-07", "0.30000000000000004", "0.3333333333333333"
    )

    @Test
    fun `numeric null decodes as null`(): Unit = assertDecodes(
        "select cast(null as numeric), cast(null as real)",
        null, null
    )

    @Test
    fun `numeric aggregates render identically`(): Unit = assertDecodes(
        """
        select sum(x), avg(x), total(x), sum(x * 1.5), min(x * 0.5), max(x * 0.5)
        from (select 1 as x union all select 2 union all select 4)
        """.trimIndent(),
        "7", "2.3333333333333335", "7.0", "10.5", "0.5", "2.0"
    )

    @Test
    fun `numeric converts to kotlin numbers`(): Unit = runBlocking {
        val sql = "select cast('42' as numeric), 9000000000, cast('2.25' as numeric)"
        for (row in listOf(simple(sql), prepared(sql))) {
            assertAll {
                assertThat(row.get(0).asInt()).isEqualTo(42)
                assertThat(row.get(1).asLong()).isEqualTo(9_000_000_000L)
                assertThat(row.get(2).asDouble()).isEqualTo(2.25)
            }
        }
    }

    @Test
    fun `numeric column round-trips bound values`(): Unit = runBlocking {
        val table = newTable()
        runCatching { db.execute("drop table if exists $table;") }
        db.execute("create table $table(id integer primary key, amount numeric(12,4));").getOrThrow()

        // NUMERIC affinity stores well-formed numeric text as INTEGER/REAL and leaves anything else as TEXT.
        db.execute(Statement.create("insert into $table(id, amount) values (1, :v);").bind("v", 7)).getOrThrow()
        db.execute(Statement.create("insert into $table(id, amount) values (2, :v);").bind("v", 9_000_000_000L))
            .getOrThrow()
        db.execute(Statement.create("insert into $table(id, amount) values (3, :v);").bind("v", 2.25)).getOrThrow()
        db.execute(Statement.create("insert into $table(id, amount) values (4, :v);").bind("v", "-3.1416")).getOrThrow()
        db.execute(Statement.create("insert into $table(id, amount) values (5, :v);").bind("v", "12.50")).getOrThrow()
        db.execute(Statement.create("insert into $table(id, amount) values (6, :v);").bind("v", "abc")).getOrThrow()
        db.execute(Statement.create("insert into $table(id, amount) values (7, :v);").bind("v", 1.0 / 3)).getOrThrow()
        db.execute(Statement.create("insert into $table(id, amount) values (8, :v);").bind("v", null)).getOrThrow()

        val sql = "select amount, typeof(amount) from $table order by id"
        val expected = listOf(
            "7" to "integer",
            "9000000000" to "integer",
            "2.25" to "real",
            "-3.1416" to "real",
            "12.5" to "real",
            "abc" to "text",
            "0.3333333333333333" to "real",
            null to "null",
        )
        val simple = db.fetchAll(sql).getOrThrow().map { it.get(0).asStringOrNull() to it.get(1).asString() }
        val prepared =
            db.fetchAll(Statement.create(sql)).getOrThrow().map { it.get(0).asStringOrNull() to it.get(1).asString() }
        val filtered = db.fetchAll(
            Statement.create("select amount from $table where amount > :min and typeof(amount) <> 'text' order by id")
                .bind("min", 0)
        )
            .getOrThrow().map { it.get(0).asStringOrNull() }

        assertAll {
            assertThat(simple, "simple").isEqualTo(expected)
            assertThat(prepared, "prepared").isEqualTo(expected)
            assertThat(filtered, "filtered").isEqualTo(listOf("7", "9000000000", "2.25", "12.5", "0.3333333333333333"))
        }

        runCatching { db.execute("drop table if exists $table;").getOrThrow() }
    }
}
