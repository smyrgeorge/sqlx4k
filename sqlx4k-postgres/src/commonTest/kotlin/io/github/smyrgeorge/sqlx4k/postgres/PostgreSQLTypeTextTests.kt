@file:Suppress("SqlNoDataSourceInspection", "SqlDialectInspection")

package io.github.smyrgeorge.sqlx4k.postgres

import assertk.assertThat
import assertk.assertions.isEqualTo
import io.github.smyrgeorge.sqlx4k.Statement
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlinx.coroutines.runBlocking

/**
 * Values of types the driver has no decoder of its own for read as the text PostgreSQL writes for them, through a
 * plain query (text results) and through a prepared statement (binary results on the native driver) alike.
 */
class PostgreSQLTypeTextTests {

    private val db = testPostgreSQL()

    @AfterTest
    fun tearDown(): Unit = runBlocking { db.close().getOrThrow() }

    @Test
    fun `intervals read as text`(): Unit = runBlocking {
        db.assertText("interval '1 day'", "1 day")
        db.assertText("interval '2 days'", "2 days")
        db.assertText("interval '1 year 2 months 3 days 04:05:06.5'", "1 year 2 mons 3 days 04:05:06.5")
        db.assertText("interval '-1 day 2 hours'", "-1 days +02:00:00")
        db.assertText("interval '-1 hour'", "-01:00:00")
        db.assertText("interval '0'", "00:00:00")
        db.assertText("interval '-1 year -2 months'", "-1 years -2 mons")
    }

    @Test
    fun `times with a time zone read as text`(): Unit = runBlocking {
        db.assertText("timetz '12:34:56+02'", "12:34:56+02")
        db.assertText("timetz '12:34:56.5-03:30'", "12:34:56.5-03:30")
    }

    @Test
    fun `network addresses read as text`(): Unit = runBlocking {
        db.assertText("inet '192.168.0.1'", "192.168.0.1")
        db.assertText("inet '192.168.0.1/24'", "192.168.0.1/24")
        db.assertText("cidr '10.0.0.0/8'", "10.0.0.0/8")
        db.assertText("inet '::1'", "::1")
        db.assertText("inet '2001:db8::1/64'", "2001:db8::1/64")
        db.assertText("macaddr '08:00:2b:01:02:03'", "08:00:2b:01:02:03")
    }

    @Test
    fun `bit strings oids and points read as text`(): Unit = runBlocking {
        db.assertText("B'0101'::bit(4)", "0101")
        db.assertText("B'101100111'::varbit", "101100111")
        db.assertText("26::oid", "26")
        db.assertText("point(1.5, -2)", "(1.5,-2)")
    }

    @Test
    fun `ranges read as text`(): Unit = runBlocking {
        db.assertText("int4range(1, 10)", "[1,10)")
        db.assertText("numrange(null, 5, '(]')", "(,5]")
        db.assertText("'empty'::int4range", "empty")
        db.assertText("daterange('2020-01-01', '2020-02-01')", "[2020-01-01,2020-02-01)")
        db.assertText(
            "tsrange('2020-01-01 10:00', '2020-01-02 10:00')",
            "[\"2020-01-01 10:00:00\",\"2020-01-02 10:00:00\")"
        )
    }

    @Test
    fun `arrays of these types read as text`(): Unit = runBlocking {
        db.assertText("array[date '2020-01-01', null]", "{2020-01-01,NULL}")
        db.assertText("array[interval '1 day']", "{\"1 day\"}")
        db.assertText("array['{\"a\": 1}'::jsonb]", "{\"{\\\"a\\\": 1}\"}")
        db.assertText("array[int4range(1, 3)]", "{\"[1,3)\"}")
    }
}

/** Asserts that [expression] reads as [expected], through a plain query and a prepared statement alike. */
internal suspend fun IPostgresSQL.assertText(expression: String, expected: String) {
    val plain = fetchAll("select $expression as v;").getOrThrow().first().get("v").asString()
    assertThat(plain, "$expression (plain)").isEqualTo(expected)
    val prepared = Statement.create("select $expression as v where ? = 1;").bind(0, 1)
    val bound = fetchAll(prepared).getOrThrow().first().get("v").asString()
    assertThat(bound, "$expression (prepared)").isEqualTo(expected)
}
