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

    @Test
    fun `dollar-sign followed by digits is not treated as a dollar tag`() {
        // $1$ should NOT be recognized as a dollar-quoted string delimiter.
        // PostgreSQL requires dollar tag identifiers to start with a letter or underscore.
        val sql = "select $1 as a, $2 as b"
        val rendered = ExtendedStatement(sql)
            .bind(0, 10)
            .bind(1, 20)
            .render()
        assertThat(rendered).isEqualTo("select 10 as a, 20 as b")
    }

    @Test
    fun `dollar-sign with digit-starting tag does not swallow SQL`() {
        // If $1$ were mistakenly treated as a dollar tag, the scanner would enter
        // dollar-quoted mode and swallow everything until the next $1$.
        val sql = "select $1, $2 from t"
        val rendered = ExtendedStatement(sql)
            .bind(0, "a")
            .bind(1, "b")
            .render()
        assertThat(rendered).isEqualTo("select 'a', 'b' from t")
    }

    @Test
    fun `named dollar-quoted tags still work`() {
        val sql = $$"""select $fn$ body :name $fn$ as txt, :name as nm"""
        val rendered = Statement.create(sql)
            .bind("name", "v")
            .render()
        assertThat(rendered).all {
            contains($$"$fn$ body :name $fn$")
            contains("'v' as nm")
        }
    }

    @Test
    fun `nested block comments hide placeholders`() {
        val sql = """
            /* outer /* inner :param */ still comment ? */ select ? as val
        """.trimIndent()
        val rendered = Statement.create(sql)
            .bind(0, 42)
            .render()
        assertThat(rendered).all {
            contains("/* outer /* inner :param */ still comment ? */")
            contains("select 42 as val")
        }
    }

    @Test
    fun `deeply nested block comments`() {
        val sql = """
            /* l1 /* l2 /* l3 :deep */ l2 ? */ l1 $1 */ select :x as v
        """.trimIndent()
        val rendered = Statement.create(sql)
            .bind("x", "ok")
            .render()
        assertThat(rendered).all {
            contains("/* l1 /* l2 /* l3 :deep */ l2 ? */ l1 $1 */")
            contains("select 'ok' as v")
        }
    }

    @Test
    fun `nested block comments with extended statement`() {
        val sql = """
            /* outer /* $1 */ still comment */ select $1 as val
        """.trimIndent()
        val rendered = ExtendedStatement(sql)
            .bind(0, 99)
            .render()
        assertThat(rendered).all {
            contains("/* outer /* $1 */ still comment */")
            contains("select 99 as val")
        }
    }
}
