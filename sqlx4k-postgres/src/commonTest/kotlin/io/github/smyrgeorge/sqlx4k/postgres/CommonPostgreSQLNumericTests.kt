@file:Suppress("SqlNoDataSourceInspection", "SqlDialectInspection")

package io.github.smyrgeorge.sqlx4k.postgres

import assertk.assertAll
import assertk.assertThat
import assertk.assertions.isEqualTo
import io.github.smyrgeorge.sqlx4k.ResultSet
import io.github.smyrgeorge.sqlx4k.Statement
import io.github.smyrgeorge.sqlx4k.impl.extensions.asDouble
import io.github.smyrgeorge.sqlx4k.impl.extensions.asInt
import io.github.smyrgeorge.sqlx4k.impl.extensions.asLong
import io.github.smyrgeorge.sqlx4k.postgres.extensions.asStringList
import kotlin.random.Random
import kotlinx.coroutines.runBlocking

class CommonPostgreSQLNumericTests(
    private val db: IPostgresSQL
) {
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

    fun `numeric values should decode through prepared statements`(): Unit = runBlocking {
        // Bound parameters go through the extended protocol, where NUMERIC arrives in its
        // binary wire format (aggregates such as sum(int8) and avg(..) are NUMERIC too).
        val st = Statement.create(
            """
            select :a::numeric(10,2) as fixed,
                   :b::numeric as negative_fraction,
                   (select sum(x) from (values (1::int8), (2::int8), (4::int8)) as t(x) where x >= :min) as total,
                   (select avg(x) from (values (1::int8), (2::int8), (4::int8)) as t(x) where x >= :min) as mean,
                   :c::numeric as big,
                   0::numeric + :min as zero_plus,
                   'NaN'::numeric + :min as not_a_number
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
            assertThat(row.get(3).asString()).isEqualTo("2.3333333333333333")
            assertThat(row.get(4).asString()).isEqualTo("123456789.0001")
            assertThat(row.get(5).asString()).isEqualTo("0")
            assertThat(row.get(6).asString()).isEqualTo("NaN")
        }
    }

    fun `numeric literals preserve scale in both protocols`(): Unit = assertDecodes(
        """
        select 1.5::numeric(10,2),
               -1234.5::numeric(8,2),
               -0.0042::numeric,
               0.00000123::numeric,
               123456789.0001::numeric,
               10000::numeric,
               0::numeric,
               0::numeric(5,3),
               1::numeric(10,2)
        """.trimIndent(),
        "1.50", "-1234.50", "-0.0042", "0.00000123", "123456789.0001", "10000", "0", "0.000", "1.00"
    )

    fun `numeric special values decode in both protocols`(): Unit = assertDecodes(
        "select 'NaN'::numeric, 'Infinity'::numeric, '-Infinity'::numeric",
        "NaN", "Infinity", "-Infinity"
    )

    fun `numeric keeps arbitrary precision in both protocols`(): Unit = assertDecodes(
        """
        select '12345678901234567890.123456789012345678901234567890'::numeric,
               '-0.000000000000000000000000000001'::numeric,
               '99999999999999999999999999999999999999'::numeric
        """.trimIndent(),
        "12345678901234567890.123456789012345678901234567890",
        "-0.000000000000000000000000000001",
        "99999999999999999999999999999999999999"
    )

    fun `numeric null decodes as null in both protocols`(): Unit = assertDecodes(
        "select null::numeric, null::numeric(10,2)",
        null, null
    )

    fun `numeric aggregates decode in both protocols`(): Unit = assertDecodes(
        """
        select sum(x), avg(x), sum(x::numeric(10,2)), min(x::numeric(10,1)), max(x::numeric(10,1))
        from (values (1::int8), (2::int8), (4::int8)) as t(x)
        """.trimIndent(),
        "7", "2.3333333333333333", "7.00", "1.0", "4.0"
    )

    fun `numeric converts to kotlin numbers`(): Unit = runBlocking {
        val sql = "select 42::numeric, 9000000000::numeric, 2.25::numeric(10,2)"
        for (row in listOf(simple(sql), prepared(sql))) {
            assertAll {
                assertThat(row.get(0).asInt()).isEqualTo(42)
                assertThat(row.get(1).asLong()).isEqualTo(9_000_000_000L)
                assertThat(row.get(2).asDouble()).isEqualTo(2.25)
            }
        }
    }

    fun `numeric array decodes in both protocols`(): Unit = runBlocking {
        val sql = "select '{1.5,-2.25,0.000,NaN}'::numeric[], '{}'::numeric[], '{1.10,NULL}'::numeric(5,2)[]"
        for (row in listOf(simple(sql), prepared(sql))) {
            assertAll {
                assertThat(row.get(0).asStringList()).isEqualTo(listOf("1.5", "-2.25", "0.000", "NaN"))
                assertThat(row.get(1).asStringList()).isEqualTo(emptyList())
                assertThat(row.get(2).asString()).isEqualTo("{1.10,NULL}")
            }
        }
    }

    fun `numeric column round-trips bound values`(): Unit = runBlocking {
        val table = newTable()
        runCatching { db.execute("drop table if exists $table;") }
        db.execute("create table $table(id int primary key, amount numeric(12,4));").getOrThrow()

        db.execute(Statement.create("insert into $table(id, amount) values (1, :v);").bind("v", 7)).getOrThrow()
        db.execute(Statement.create("insert into $table(id, amount) values (2, :v);").bind("v", 9_000_000L))
            .getOrThrow()
        db.execute(Statement.create("insert into $table(id, amount) values (3, :v);").bind("v", 2.25)).getOrThrow()
        db.execute(Statement.create("insert into $table(id, amount) values (4, :v::numeric);").bind("v", "-3.14159"))
            .getOrThrow()
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
