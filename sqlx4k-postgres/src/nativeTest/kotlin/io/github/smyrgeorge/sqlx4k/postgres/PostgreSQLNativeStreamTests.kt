@file:Suppress("SqlNoDataSourceInspection", "SqlDialectInspection")

package io.github.smyrgeorge.sqlx4k.postgres

import assertk.assertThat
import assertk.assertions.isEqualTo
import io.github.smyrgeorge.sqlx4k.impl.extensions.asInt
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking

// The JVM driver streams a query as a prepared statement, which takes a single statement.
class PostgreSQLNativeStreamTests {

    private val db = testPostgreSQL()

    @AfterTest
    fun tearDown(): Unit = runBlocking { db.close().getOrThrow() }

    @Test
    fun `the rows of statements of different columns are streamed with their own columns`(): Unit = runBlocking {
        val rows = db.fetch("select 1 as a; select 2 as a, 3 as b; select 4 as c;", fetchSize = 10).toList()
        assertThat(rows.map { row -> List(row.size) { row.get(it).name } })
            .isEqualTo(listOf(listOf("a"), listOf("a", "b"), listOf("c")))
        assertThat(rows[1].get("b").asInt()).isEqualTo(3)
        assertThat(rows[2].get("c").asInt()).isEqualTo(4)
    }
}
