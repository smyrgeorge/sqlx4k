@file:Suppress("SqlNoDataSourceInspection", "SqlDialectInspection")

package io.github.smyrgeorge.sqlx4k.sqlite

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import io.github.smyrgeorge.sqlx4k.impl.extensions.asLong
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlinx.coroutines.runBlocking

// The Android platform driver reads past its cursor window (about 2 MB) by running the statement again: it does so for
// a query only, not for a statement that may write (`INSERT ... RETURNING`, which Robolectric's SQLite predates).
class AndroidSQLiteCursorWindowTests : SQLiteTestBase() {

    private val db = testSQLite("AndroidSQLiteCursorWindowTests")

    @AfterTest
    fun tearDown(): Unit = runBlocking { db.close().getOrThrow() }

    @Test
    fun `statements that only read are told apart from those that may write`() {
        assertThat("select * from t".mayWrite()).isFalse()
        assertThat("  -- a comment\n/* another */ SELECT 1".mayWrite()).isFalse()
        assertThat("values (1), (2)".mayWrite()).isFalse()
        assertThat("pragma table_info(t)".mayWrite()).isFalse()
        assertThat("with c(n) as (select 1) select n from c".mayWrite()).isFalse()
        assertThat("with c(n) as (select 'insert') select n as \"update\" from c -- delete".mayWrite()).isFalse()
        assertThat("with c(n) as (select replace(name, 'a', 'b') from t) select n from c".mayWrite()).isFalse()
        assertThat("with c(n) as (select 1) replace into t(v) select n from c".mayWrite()).isTrue()
        assertThat("insert into t(v) values (1) returning id".mayWrite()).isTrue()
        assertThat("update t set v = 1 returning id".mayWrite()).isTrue()
        assertThat("delete from t returning id".mayWrite()).isTrue()
        assertThat("with c(n) as (select 1) insert into t(v) select n from c returning id".mayWrite()).isTrue()
    }

    @Test
    fun `a query is read past the cursor window`(): Unit = runBlocking {
        val table = "t_window_${Random.nextInt(1_000_000)}"
        db.execute("create table $table(v text);").getOrThrow()
        // About 3 MB of rows.
        db.execute(
            "insert into $table(v) select hex(zeroblob(500)) from (" +
                    "with recursive c(n) as (select 1 union all select n + 1 from c where n < 3000) select n from c);"
        ).getOrThrow()
        assertThat(db.fetchAll("select v from $table;").getOrThrow().size).isEqualTo(3_000)
        assertThat(db.fetchAll("select count(*) from $table;").getOrThrow().first().get(0).asLong()).isEqualTo(3_000L)
    }
}
