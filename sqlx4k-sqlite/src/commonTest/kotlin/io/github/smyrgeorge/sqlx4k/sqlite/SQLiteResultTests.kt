@file:Suppress("SqlNoDataSourceInspection", "SqlDialectInspection")

package io.github.smyrgeorge.sqlx4k.sqlite

import assertk.assertThat
import assertk.assertions.isEqualTo
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlinx.coroutines.runBlocking

class SQLiteResultTests : SQLiteTestBase() {

    private val db = testSQLite("SQLiteResultTests")

    @AfterTest
    fun tearDown(): Unit = runBlocking { db.close().getOrThrow() }

    private fun newTable(): String = "t_res_${Random.nextInt(1_000_000)}"

    @Test
    fun `empty result set still reports column metadata`(): Unit = runBlocking {
        // The native driver is backed by the sqlx core, which reports no columns for a result without rows.
        if (testPlatform == TestPlatform.Native) return@runBlocking
        val table = newTable()
        try {
            db.execute("create table $table(id integer primary key, name text)").getOrThrow()
            // No rows inserted: the query returns zero rows but must still expose its columns.
            val rs = db.fetchAll("select id, name from $table where 1 = 0").getOrThrow()
            assertThat(rs.size).isEqualTo(0)
            assertThat(rs.metadata.getColumnCount()).isEqualTo(2)
            assertThat(rs.metadata.getColumn(0).name).isEqualTo("id")
            assertThat(rs.metadata.getColumn(1).name).isEqualTo("name")
        } finally {
            runCatching { db.execute("drop table if exists $table").getOrThrow() }
        }
    }

    @Test
    fun `text value 'null' is not coerced to SQL NULL`(): Unit = runBlocking {
        val rs = db.fetchAll("select 'null' as v").getOrThrow()
        assertThat(rs.first().get(0).asStringOrNull()).isEqualTo("null")
    }
}
