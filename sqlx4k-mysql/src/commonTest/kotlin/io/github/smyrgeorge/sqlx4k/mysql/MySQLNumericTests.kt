@file:Suppress("SqlNoDataSourceInspection", "SqlDialectInspection")

package io.github.smyrgeorge.sqlx4k.mysql

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

class MySQLNumericTests {

    private val db = testMySQL()

    @AfterTest
    fun tearDown(): Unit = runBlocking { db.close().getOrThrow() }

    private fun newTable(): String = "t_numeric_${Random.nextInt(1_000_000)}"

    private suspend fun simple(sql: String): ResultSet.Row = db.fetchAll(sql).getOrThrow().first()
    private suspend fun prepared(sql: String): ResultSet.Row = db.fetchAll(Statement.create(sql)).getOrThrow().first()

    /** Runs [sql] through both protocols and checks that each column renders as [expected]. */
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
    fun `decimal values should decode through prepared statements`(): Unit = runBlocking {
        val st = Statement.create(
            """
            select cast(:a as decimal(10,2)) as fixed,
                   cast(:b as decimal(10,4)) as negative_fraction,
                   (select sum(x) from (select 1 as x union all select 2 union all select 4) as t where x >= :min) as total,
                   (select avg(x) from (select 1 as x union all select 2 union all select 4) as t where x >= :min) as mean,
                   cast(:c as decimal(20,4)) as big,
                   -- Typed explicitly: an untyped parameter in arithmetic makes a server-side prepare
                   -- (native) widen the result to scale 30, while a client-side prepare (r2dbc) sees a literal.
                   cast(0 as decimal(5,3)) + cast(:min as signed) as zero_plus
            """.trimIndent()
        )
            .bind("a", "1.5")
            .bind("b", "-0.0042")
            .bind("c", "123456789.0001")
            .bind("min", 0)
        val row = db.fetchAll(st).getOrThrow().first()

        assertAll {
            assertThat(row.get(0).asString()).isEqualTo("1.50")
            assertThat(row.get(1).asString()).isEqualTo("-0.0042")
            assertThat(row.get(2).asString()).isEqualTo("7")
            assertThat(row.get(3).asString()).isEqualTo("2.3333")
            assertThat(row.get(4).asString()).isEqualTo("123456789.0001")
            assertThat(row.get(5).asString()).isEqualTo("0.000")
        }
    }

    @Test
    fun `decimal literals preserve scale in both protocols`(): Unit = assertDecodes(
        """
        select cast(1.5 as decimal(10,2)),
               cast(-1234.5 as decimal(8,2)),
               cast(-0.0042 as decimal(10,4)),
               cast(0.00000123 as decimal(10,8)),
               cast(123456789.0001 as decimal(20,4)),
               cast(10000 as decimal(10,0)),
               cast(0 as decimal(5,3)),
               cast(1 as decimal(10,2)),
               1.50
        """.trimIndent(),
        "1.50", "-1234.50", "-0.0042", "0.00000123", "123456789.0001", "10000", "0.000", "1.00", "1.50"
    )

    @Test
    fun `decimal keeps full precision in both protocols`(): Unit = assertDecodes(
        """
        select cast('12345678901234567890.123456789012345678901234567890' as decimal(65,30)),
               cast('-0.000000000000000000000000000001' as decimal(65,30)),
               cast('99999999999999999999999999999999999999' as decimal(65,0))
        """.trimIndent(),
        "12345678901234567890.123456789012345678901234567890",
        "-0.000000000000000000000000000001",
        "99999999999999999999999999999999999999"
    )

    @Test
    fun `decimal null decodes as null in both protocols`(): Unit = assertDecodes(
        "select cast(null as decimal(10,2)), cast(null as decimal)",
        null, null
    )

    @Test
    fun `decimal aggregates decode in both protocols`(): Unit = assertDecodes(
        """
        select sum(x), avg(x), sum(cast(x as decimal(10,2))), min(cast(x as decimal(10,1))), max(cast(x as decimal(10,1)))
        from (select 1 as x union all select 2 union all select 4) as t
        """.trimIndent(),
        "7", "2.3333", "7.00", "1.0", "4.0"
    )

    @Test
    fun `decimal converts to kotlin numbers`(): Unit = runBlocking {
        val sql = "select cast(42 as decimal), cast(9000000000 as decimal(20,0)), cast(2.25 as decimal(10,2))"
        for (row in listOf(simple(sql), prepared(sql))) {
            assertAll {
                assertThat(row.get(0).asInt()).isEqualTo(42)
                assertThat(row.get(1).asLong()).isEqualTo(9_000_000_000L)
                assertThat(row.get(2).asDouble()).isEqualTo(2.25)
            }
        }
    }

    @Test
    fun `decimal column round-trips bound values`(): Unit = runBlocking {
        val table = newTable()
        runCatching { db.execute("drop table if exists $table;") }
        db.execute("create table $table(id int primary key, amount decimal(12,4));").getOrThrow()

        db.execute(Statement.create("insert into $table(id, amount) values (1, :v);").bind("v", 7)).getOrThrow()
        db.execute(Statement.create("insert into $table(id, amount) values (2, :v);").bind("v", 9_000_000L))
            .getOrThrow()
        db.execute(Statement.create("insert into $table(id, amount) values (3, :v);").bind("v", 2.25)).getOrThrow()
        db.execute(Statement.create("insert into $table(id, amount) values (4, :v);").bind("v", "-3.1416")).getOrThrow()
        db.execute(Statement.create("insert into $table(id, amount) values (5, :v);").bind("v", null)).getOrThrow()

        val sql = "select amount from $table order by id"
        val expected = listOf("7.0000", "9000000.0000", "2.2500", "-3.1416", null)
        val simple = db.fetchAll(sql).getOrThrow().map { it.get(0).asStringOrNull() }
        val prepared = db.fetchAll(Statement.create(sql)).getOrThrow().map { it.get(0).asStringOrNull() }
        val filtered =
            db.fetchAll(Statement.create("select amount from $table where amount > :min order by id").bind("min", 0))
                .getOrThrow().map { it.get(0).asStringOrNull() }

        assertAll {
            assertThat(simple, "simple").isEqualTo(expected)
            assertThat(prepared, "prepared").isEqualTo(expected)
            assertThat(filtered, "filtered").isEqualTo(listOf("7.0000", "9000000.0000", "2.2500"))
        }

        runCatching { db.execute("drop table if exists $table;").getOrThrow() }
    }
}
