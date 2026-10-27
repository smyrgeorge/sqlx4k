@file:Suppress("SqlNoDataSourceInspection", "SqlDialectInspection")

package io.github.smyrgeorge.sqlx4k.mysql

import assertk.assertThat
import assertk.assertions.isEqualTo
import io.github.smyrgeorge.sqlx4k.ResultSet
import io.github.smyrgeorge.sqlx4k.Statement
import io.github.smyrgeorge.sqlx4k.impl.extensions.asInt
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking

// Queries that return several result sets, whose columns differ. The JVM driver takes a single statement.
class MySQLNativeResultSetsTests {

    private val db = testMySQL()

    @AfterTest
    fun tearDown(): Unit = runBlocking { db.close().getOrThrow() }

    private fun ResultSet.Row.names(): List<String> = List(size) { get(it).name }

    private suspend fun withProcedure(block: suspend (String) -> Unit) {
        val procedure = "p_result_sets_${Random.nextInt(1_000_000)}"
        db.execute("create procedure $procedure() begin select 1 as a; select 2 as a, 3 as b; end").getOrThrow()
        try {
            block(procedure)
        } finally {
            db.execute("drop procedure $procedure").getOrThrow()
        }
    }

    @Test
    fun `fetchAll returns the rows of the last result set`(): Unit = runBlocking {
        val rows = db.fetchAll("select 1 as a; select 2 as a, 3 as b;").getOrThrow()
        assertThat(rows.map { it.names() }).isEqualTo(listOf(listOf("a", "b")))
        assertThat(rows.first().get("b").asInt()).isEqualTo(3)
    }

    @Test
    fun `fetchAll of a procedure returns the rows of its last SELECT`(): Unit = runBlocking {
        withProcedure { procedure ->
            val rows = db.fetchAll("call $procedure()").getOrThrow()
            assertThat(rows.map { it.names() }).isEqualTo(listOf(listOf("a", "b")))
            val prepared = db.fetchAll(Statement.create("call $procedure()")).getOrThrow()
            assertThat(prepared.map { it.names() }).isEqualTo(listOf(listOf("a", "b")))
        }
    }

    @Test
    fun `the rows of result sets of different columns are streamed with their own columns`(): Unit = runBlocking {
        val rows = db.fetch("select 1 as a; select 2 as a, 3 as b; select 4 as c;", fetchSize = 10).toList()
        assertThat(rows.map { it.names() }).isEqualTo(listOf(listOf("a"), listOf("a", "b"), listOf("c")))
        assertThat(rows[1].get("b").asInt()).isEqualTo(3)
        assertThat(rows[2].get("c").asInt()).isEqualTo(4)
        withProcedure { procedure ->
            val streamed = db.fetch(Statement.create("call $procedure()"), fetchSize = 10).toList()
            assertThat(streamed.map { it.names() }).isEqualTo(listOf(listOf("a"), listOf("a", "b")))
        }
    }
}
