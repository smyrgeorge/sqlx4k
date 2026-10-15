package io.github.smyrgeorge.sqlx4k

import assertk.assertAll
import assertk.assertThat
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import io.github.smyrgeorge.sqlx4k.impl.extensions.isInsertStatement
import kotlin.test.Test

class InsertStatementTests {

    @Test
    fun `insert and replace statements are recognised`() {
        assertAll {
            assertThat("insert into t(x) values (1)".isInsertStatement()).isTrue()
            assertThat("  INSERT INTO t(x) VALUES (1)".isInsertStatement()).isTrue()
            assertThat("Insert or ignore into t(x) values (1)".isInsertStatement()).isTrue()
            assertThat("replace into t(x) values (1)".isInsertStatement()).isTrue()
            assertThat("-- comment\ninsert into t(x) values (1)".isInsertStatement()).isTrue()
            assertThat("/* comment */ insert into t(x) values (1)".isInsertStatement()).isTrue()
        }
    }

    @Test
    fun `other statements are not`() {
        assertAll {
            assertThat("update t set x = 1".isInsertStatement()).isFalse()
            assertThat("delete from t".isInsertStatement()).isFalse()
            assertThat("select 'insert'".isInsertStatement()).isFalse()
            assertThat("inserted_at = 1".isInsertStatement()).isFalse()
            assertThat("with s as (select 1) insert into t(x) select * from s".isInsertStatement()).isFalse()
            assertThat("".isInsertStatement()).isFalse()
            assertThat("-- only a comment".isInsertStatement()).isFalse()
        }
    }
}
