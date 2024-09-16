package io.github.smyrgeorge.sqlx4k

import assertk.all
import assertk.assertThat
import assertk.assertions.contains
import kotlin.test.Test
import kotlin.test.assertFails

class StatementTest {

    @Test
    fun `Do not mix named and positional parameters`() {
        val sql = "select * from sqlx4k where id > ? and id < :id"
        assertFails {
            Statement(sql)
                .bind(0, 65)
                .bind("id", 66)
                .render()
        }
    }

    @Test
    fun `Positional parameter basic test for integers`() {
        val sql = "select * from sqlx4k where id > ? and id < ?"
        val res = Statement(sql)
            .bind(0, 65)
            .bind(1, 66)
            .render()

        assertThat(res).all {
            contains("id > 65")
            contains("id < 66")
        }
    }

    @Test
    fun `Positional parameter basic test for strings`() {
        val sql = "select * from sqlx4k where id = ?"
        val res = Statement(sql)
            .bind(0, "this is a test")
            .render()

        assertThat(res).contains("id = 'this is a test'")
    }

    @Test
    fun `Named parameter basic test for integers`() {
        val sql = "select * from sqlx4k where id > :id and id < :id"
        val res = Statement(sql)
            .bind("id", 65)
            .render()

        assertThat(res).all {
            contains("id > 65")
            contains("id < 65")
        }
    }

    @Test
    fun `Named parameter basic test for strings`() {
        val sql = "select * from sqlx4k where id = :id"
        val res = Statement(sql)
            .bind("id", "this is a test")
            .render()

        assertThat(res).contains("id = 'this is a test'")
    }
}
