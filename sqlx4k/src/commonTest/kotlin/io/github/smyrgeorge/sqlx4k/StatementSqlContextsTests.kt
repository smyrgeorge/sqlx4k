package io.github.smyrgeorge.sqlx4k

import assertk.all
import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isEqualTo
import io.github.smyrgeorge.sqlx4k.impl.statement.ExtendedStatement
import kotlin.test.Test
import kotlin.test.assertFailsWith

class StatementSqlContextsTests {

    @Test
    fun `placeholders in line comments are not replaced`() {
        val sql = """
            select 1 -- comment with ? and :name and $1
            , 2 as two
        """.trimIndent()
        val rendered = Statement.create(sql).render()
        assertThat(rendered).all {
            contains("-- comment with ? and :name and $1")
        }
        val renderedPg = ExtendedStatement(sql).render()
        assertThat(renderedPg).all {
            contains("-- comment with ? and :name and $1")
        }
    }

    @Test
    fun `placeholders in block comments are not replaced`() {
        val sql = """
            /* block with ? and :name and $2 */
            select ? as val, :name as nm, 3 as pg
        """.trimIndent()
        val rendered = Statement.create(sql)
            .bind(0, 1)
            .bind("name", "n")
            .render()
        assertThat(rendered).all {
            contains("/* block with ? and :name and $2 */")
            contains("select 1 as val, 'n' as nm")
        }
        val pgSql = """
            /* block with placeholders $2 */
            select $2 as pg
        """.trimIndent()
        val renderedPg = ExtendedStatement(pgSql)
            .bind(1, 5)
            .render()
        assertThat(renderedPg).all {
            contains("/* block with placeholders $2 */")
            contains("select 5 as pg")
        }
    }

    @Test
    fun `placeholders inside dollar-quoted strings are not replaced`() {
        val sql = """
            select $$ body with ? and :name and $3 $$ as txt, ? as id, :name as nm, $3 as pg
        """.trimIndent()
        val s = Statement.create(sql).bind(0, 7).bind("name", "abc").render()
        assertThat(s).all {
            contains("$$ body with ? and :name and $3 $$")
            contains("7 as id")
            contains("'abc' as nm")
        }
        val pg = ExtendedStatement(sql).bind(2, 11).render()
        assertThat(pg).all {
            contains("$$ body with ? and :name and $3 $$")
            contains("11 as pg")
        }
    }

    @Test
    fun `extended statement supports high indices like dollar10`() {
        val sql = "select $10 as v"
        val rendered = ExtendedStatement(sql).bind(9, 123).render()
        assertThat(rendered).contains("select 123 as v")
    }

    @Test
    fun `extended statement errors when used parameter missing`() {
        val sql = "select $2 as v"
        val ex = assertFailsWith<SQLError> {
            ExtendedStatement(sql).render()
        }
        assertThat(ex.code).isEqualTo(SQLError.Code.PositionalParameterValueNotSupplied)
    }
}
