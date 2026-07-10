@file:Suppress("SqlNoDataSourceInspection", "SqlDialectInspection")

package io.github.smyrgeorge.sqlx4k.sqlite

import assertk.assertThat
import assertk.assertions.isEqualTo
import kotlin.random.Random
import kotlinx.coroutines.runBlocking

class CommonSQLiteResultTests(
    private val db: ISQLite
) {
    private fun newTable(): String = "t_res_${Random.nextInt(1_000_000)}"

    fun `empty result set still reports column metadata`() = runBlocking {
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

    fun `text value 'null' is not coerced to SQL NULL`() = runBlocking {
        val rs = db.fetchAll("select 'null' as v").getOrThrow()
        assertThat(rs.first().get(0).asStringOrNull()).isEqualTo("null")
    }
}
