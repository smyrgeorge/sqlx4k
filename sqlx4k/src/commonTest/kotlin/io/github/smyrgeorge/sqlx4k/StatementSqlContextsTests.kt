package io.github.smyrgeorge.sqlx4k

import assertk.all
import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isEqualTo
import kotlin.test.Test

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
    }

    @Test
    fun `dollar-sign followed by digits is not treated as a dollar tag`() {
        // $1$ should NOT be recognized as a dollar-quoted string delimiter.
        // PostgreSQL requires dollar tag identifiers to start with a letter or underscore.
        // If $1$ were treated as a tag, the scanner would enter dollar-quoted mode and
        // the following placeholders would not be replaced.
        val sql = "select $1 as a, ? as b, :name as c"
        val rendered = Statement.create(sql)
            .bind(0, 10)
            .bind("name", "n")
            .render()
        assertThat(rendered).isEqualTo("select $1 as a, 10 as b, 'n' as c")
    }

    @Test
    fun `dollar-sign with digit-starting tag does not swallow SQL`() {
        // If $1$ were mistakenly treated as a dollar tag, the scanner would enter
        // dollar-quoted mode and swallow everything until the next $1$.
        val sql = "select $1, $2, ? from t"
        val rendered = Statement.create(sql)
            .bind(0, "a")
            .render()
        assertThat(rendered).isEqualTo("select $1, $2, 'a' from t")
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
}
