package io.github.smyrgeorge.sqlx4k

import assertk.all
import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.doesNotContain
import assertk.assertions.isEqualTo
import kotlin.test.Test

/**
 * Tests focused on verifying that AbstractStatement renders values in a way that
 * prevents SQL injection by properly quoting strings, escaping single quotes,
 * and not misinterpreting user-provided content as SQL syntax.
 */
class StatementSqlInjectionTests {

    @Test
    fun `positional parameter with dangerous string is safely quoted and escaped`() {
        val evil = "'; DROP TABLE users; --"
        val sql = "select * from accounts where username = ?"
        val rendered = Statement.create(sql)
            .bind(0, evil)
            .render()
        // Expect the value to be single-quoted and inner quote escaped to ''
        assertThat(rendered).all {
            contains("select * from accounts where username = '")
            contains("''; DROP TABLE users; --'")
        }
        // Ensure there's no unquoted occurrence of the dangerous part
        assertThat(rendered).doesNotContain("username = '; DROP TABLE")
    }

    @Test
    fun `named parameter with dangerous string is safely quoted and escaped`() {
        val evil = "x'); DELETE FROM t; --"
        val sql = "update t set v = :val where id = :id"
        val rendered = Statement.create(sql)
            .bind("val", evil)
            .bind("id", 42)
            .render()
        assertThat(rendered).all {
            contains("set v = '")
            // single quote inside value doubled
            contains("x''); DELETE FROM t; --'")
            // id is numeric, should not be quoted
            contains("where id = 42")
        }
        // No stray closing quote sequence should appear except as escaped inside the literal
        assertThat(rendered).doesNotContain("set v = x')")
    }

    @Test
    fun `values containing placeholder-like text do not affect SQL parsing`() {
        val tricky = ":name ? $1"
        val sql = "insert into logs(txt, who) values(:txt, ?)"
        val rendered = Statement.create(sql)
            .bind("txt", tricky)
            .bind(0, "user")
            .render()
        // The tricky content must appear as a single quoted string with inner content untouched
        assertThat(rendered).all {
            contains("values('")
            contains(":name ? $1")
            contains("', 'user')")
        }
    }

    @Test
    fun `postgres type cast syntax is not treated as named parameter`() {
        val sql = "select :v::int as i"
        val rendered = Statement.create(sql)
            .bind("v", "123")
            .render()
        // The first :v is a named parameter, replaced; the '::int' must remain unchanged
        assertThat(rendered).all {
            contains("select '123'::int as i")
        }
    }

    @Test
    fun `single quotes inside value are doubled`() {
        val value = "Bob O'Connor"
        val sql = "select :name as nm"
        val rendered = Statement.create(sql)
            .bind("name", value)
            .render()
        assertThat(rendered).isEqualTo("select 'Bob O''Connor' as nm")
    }
}
