package io.github.smyrgeorge.sqlx4k.processor

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.doesNotContain
import assertk.assertions.hasSize
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import io.github.smyrgeorge.sqlx4k.processor.SqlValidator.StatementKind
import kotlin.test.Test
import kotlin.test.assertFailsWith

/**
 * Unit tests for the compile-time guards and rewrites in [SqlValidator].
 *
 * These functions operate on the parsed JSqlParser AST, so each test parses the SQL through the same
 * entry point the processor uses ([SqlValidator.validateQuerySyntax]) and then invokes the function
 * under test directly. Failures surface as [IllegalStateException] (thrown by Kotlin's `error(...)`).
 */
class SqlValidatorTests {

    private fun parse(sql: String) = SqlValidator.validateQuerySyntax("fn", sql).single()
    private fun parseAll(sql: String) = SqlValidator.validateQuerySyntax("fn", sql)

    private val userColumns = listOf("id", "name", "email")
    private val userColumnSet = setOf("id", "name", "email")

    // =============================================================================================
    // validateQuerySyntax (SQL syntax validation)
    // =============================================================================================

    @Test
    fun `validateQuerySyntax returns the parsed statement for valid SQL`() {
        assertThat(parseAll("select * from users")).hasSize(1)
    }

    @Test
    fun `validateQuerySyntax returns every statement of a stacked query`() {
        assertThat(parseAll("select * from users; delete from orders")).hasSize(2)
    }

    @Test
    fun `validateQuerySyntax throws on malformed SQL when reporting errors`() {
        val ex = assertFailsWith<IllegalStateException> {
            SqlValidator.validateQuerySyntax("findAllBroken", "SELECT * FROM")
        }
        assertThat(ex.message!!).contains("Invalid SQL syntax")
        assertThat(ex.message!!).contains("findAllBroken")
    }

    @Test
    fun `validateQuerySyntax swallows malformed SQL when not reporting errors`() {
        assertThat(SqlValidator.validateQuerySyntax("fn", "SELECT * FROM", reportErrors = false)).isEmpty()
    }

    // =============================================================================================
    // rejectStackedStatements
    // =============================================================================================

    @Test
    fun `rejectStackedStatements passes for a single statement`() {
        SqlValidator.rejectStackedStatements("fn", parseAll("select * from users"))
    }

    @Test
    fun `rejectStackedStatements passes for an empty statement list`() {
        SqlValidator.rejectStackedStatements("fn", emptyList())
    }

    @Test
    fun `rejectStackedStatements fails for more than one statement`() {
        val ex = assertFailsWith<IllegalStateException> {
            SqlValidator.rejectStackedStatements("fn", parseAll("delete from users; delete from orders"))
        }
        assertThat(ex.message!!).contains("Multiple SQL statements")
        assertThat(ex.message!!).contains("2 statements")
    }

    // =============================================================================================
    // validateTable
    // =============================================================================================

    @Test
    fun `validateTable passes when a SELECT targets the entity table`() {
        SqlValidator.validateTable("fn", parse("select * from users where id = :id"), "users")
    }

    @Test
    fun `validateTable is case-insensitive on the table name`() {
        SqlValidator.validateTable("fn", parse("select * from USERS"), "users")
    }

    @Test
    fun `validateTable fails when a SELECT targets a different table`() {
        val ex = assertFailsWith<IllegalStateException> {
            SqlValidator.validateTable("fn", parse("select * from orders"), "users")
        }
        assertThat(ex.message!!).contains("orders")
        assertThat(ex.message!!).contains("users")
    }

    @Test
    fun `validateTable passes for a matching DELETE and fails for a mismatched one`() {
        SqlValidator.validateTable("fn", parse("delete from users where id = :id"), "users")
        assertFailsWith<IllegalStateException> {
            SqlValidator.validateTable("fn", parse("delete from orders where id = :id"), "users")
        }
    }

    @Test
    fun `validateTable passes for a matching UPDATE and fails for a mismatched one`() {
        SqlValidator.validateTable("fn", parse("update users set name = :name where id = :id"), "users")
        assertFailsWith<IllegalStateException> {
            SqlValidator.validateTable("fn", parse("update orders set name = :name where id = :id"), "users")
        }
    }

    @Test
    fun `validateTable skips a subselect FROM clause`() {
        // fromItem is a derived table, not a plain Table -> skipped, no throw even for a different inner table.
        SqlValidator.validateTable("fn", parse("select * from (select * from orders) t"), "users")
    }

    @Test
    fun `validateTable skips a non-CRUD statement`() {
        SqlValidator.validateTable("fn", parse("insert into orders (id) values (:id)"), "users")
    }

    // =============================================================================================
    // validateCountProjection
    // =============================================================================================

    @Test
    fun `validateCountProjection passes for count star`() {
        SqlValidator.validateCountProjection("countAll", parse("select count(*) from users"))
    }

    @Test
    fun `validateCountProjection passes for count of a column`() {
        SqlValidator.validateCountProjection("countAll", parse("select count(id) from users"))
    }

    @Test
    fun `validateCountProjection is case-insensitive on the function name`() {
        SqlValidator.validateCountProjection("countAll", parse("select COUNT(*) from users"))
    }

    @Test
    fun `validateCountProjection fails for a star projection`() {
        val ex = assertFailsWith<IllegalStateException> {
            SqlValidator.validateCountProjection("countAll", parse("select * from users"))
        }
        assertThat(ex.message!!).contains("count(...) aggregate")
    }

    @Test
    fun `validateCountProjection fails for a non-count aggregate`() {
        assertFailsWith<IllegalStateException> {
            SqlValidator.validateCountProjection("countAll", parse("select sum(id) from users"))
        }
    }

    @Test
    fun `validateCountProjection fails when more than one item is selected`() {
        assertFailsWith<IllegalStateException> {
            SqlValidator.validateCountProjection("countAll", parse("select id, count(*) from users"))
        }
    }

    @Test
    fun `validateCountProjection skips a non-plain-select`() {
        SqlValidator.validateCountProjection("countAll", parse("delete from users where id = :id"))
    }

    // =============================================================================================
    // validateStatementKind
    // =============================================================================================

    @Test
    fun `validateStatementKind accepts a SELECT for the SELECT kind`() {
        SqlValidator.validateStatementKind("findAll", parse("select * from users"), StatementKind.SELECT)
    }

    @Test
    fun `validateStatementKind rejects a DELETE for the SELECT kind`() {
        val ex = assertFailsWith<IllegalStateException> {
            SqlValidator.validateStatementKind("findAll", parse("delete from users"), StatementKind.SELECT)
        }
        assertThat(ex.message!!).contains("SELECT")
        assertThat(ex.message!!).contains("DELETE")
    }

    @Test
    fun `validateStatementKind accepts a DELETE for the DELETE kind`() {
        SqlValidator.validateStatementKind("deleteById", parse("delete from users where id = :id"), StatementKind.DELETE)
    }

    @Test
    fun `validateStatementKind rejects a SELECT for the DELETE kind`() {
        assertFailsWith<IllegalStateException> {
            SqlValidator.validateStatementKind("deleteById", parse("select * from users"), StatementKind.DELETE)
        }
    }

    @Test
    fun `validateStatementKind accepts INSERT UPDATE and DELETE for the WRITE kind`() {
        SqlValidator.validateStatementKind("execute", parse("insert into users (id) values (:id)"), StatementKind.WRITE)
        SqlValidator.validateStatementKind(
            "execute",
            parse("update users set name = :name where id = :id"),
            StatementKind.WRITE
        )
        SqlValidator.validateStatementKind("execute", parse("delete from users where id = :id"), StatementKind.WRITE)
    }

    @Test
    fun `validateStatementKind rejects a SELECT for the WRITE kind`() {
        val ex = assertFailsWith<IllegalStateException> {
            SqlValidator.validateStatementKind("execute", parse("select * from users"), StatementKind.WRITE)
        }
        assertThat(ex.message!!).contains("INSERT/UPDATE/DELETE")
        assertThat(ex.message!!).contains("SELECT")
    }

    // =============================================================================================
    // validateProjection
    // =============================================================================================

    @Test
    fun `validateProjection passes for SELECT star`() {
        SqlValidator.validateProjection("findAll", parse("select * from users"), userColumnSet)
    }

    @Test
    fun `validateProjection passes when every entity column is listed`() {
        SqlValidator.validateProjection("findAll", parse("select id, name, email from users"), userColumnSet)
    }

    @Test
    fun `validateProjection is case-insensitive on the selected columns`() {
        SqlValidator.validateProjection("findAll", parse("select ID, NAME, EMAIL from users"), userColumnSet)
    }

    @Test
    fun `validateProjection tolerates quoted column identifiers`() {
        SqlValidator.validateProjection(
            "findAll",
            parse("""select "id", "name", "email" from users"""),
            userColumnSet
        )
    }

    @Test
    fun `validateProjection fails when a column is missing from the projection`() {
        val ex = assertFailsWith<IllegalStateException> {
            SqlValidator.validateProjection("findAll", parse("select id, name from users"), userColumnSet)
        }
        assertThat(ex.message!!).contains("email")
    }

    @Test
    fun `validateProjection skips a query with joins`() {
        // A partial projection would normally fail, but a join short-circuits the check.
        SqlValidator.validateProjection(
            "findAll",
            parse("select id from users u join orders o on u.id = o.user_id"),
            userColumnSet
        )
    }

    @Test
    fun `validateProjection skips a non-plain-select`() {
        SqlValidator.validateProjection("findAll", parse("delete from users"), userColumnSet)
    }

    // =============================================================================================
    // validateReturningColumns
    // =============================================================================================

    @Test
    fun `validateReturningColumns passes when RETURNING lists known columns`() {
        SqlValidator.validateReturningColumns(
            "execute",
            parse("insert into users (name) values (:name) returning id, name"),
            userColumnSet
        )
    }

    @Test
    fun `validateReturningColumns tolerates RETURNING star`() {
        SqlValidator.validateReturningColumns(
            "execute",
            parse("insert into users (name) values (:name) returning *"),
            userColumnSet
        )
    }

    @Test
    fun `validateReturningColumns fails on an unknown INSERT RETURNING column`() {
        val ex = assertFailsWith<IllegalStateException> {
            SqlValidator.validateReturningColumns(
                "execute",
                parse("insert into users (name) values (:name) returning id, unknown_col"),
                userColumnSet
            )
        }
        assertThat(ex.message!!).contains("unknown_col")
    }

    @Test
    fun `validateReturningColumns validates UPDATE RETURNING`() {
        SqlValidator.validateReturningColumns(
            "execute",
            parse("update users set name = :name where id = :id returning email"),
            userColumnSet
        )
        assertFailsWith<IllegalStateException> {
            SqlValidator.validateReturningColumns(
                "execute",
                parse("update users set name = :name where id = :id returning bogus"),
                userColumnSet
            )
        }
    }

    @Test
    fun `validateReturningColumns validates DELETE RETURNING`() {
        SqlValidator.validateReturningColumns(
            "deleteById",
            parse("delete from users where id = :id returning id"),
            userColumnSet
        )
        assertFailsWith<IllegalStateException> {
            SqlValidator.validateReturningColumns(
                "deleteById",
                parse("delete from users where id = :id returning bogus"),
                userColumnSet
            )
        }
    }

    @Test
    fun `validateReturningColumns skips a statement without a RETURNING clause`() {
        SqlValidator.validateReturningColumns("findAll", parse("select id from users"), userColumnSet)
        SqlValidator.validateReturningColumns(
            "execute",
            parse("insert into users (name) values (:name)"),
            userColumnSet
        )
    }

    // =============================================================================================
    // validateColumnsExist
    // =============================================================================================

    @Test
    fun `validateColumnsExist passes when all referenced columns exist`() {
        SqlValidator.validateColumnsExist(
            "findAllByEmail",
            parse("select id, name, email from users where email = :email"),
            "users",
            userColumnSet
        )
    }

    @Test
    fun `validateColumnsExist fails on an unknown column in the SELECT list`() {
        val ex = assertFailsWith<IllegalStateException> {
            SqlValidator.validateColumnsExist("findAll", parse("select id, bogus from users"), "users", userColumnSet)
        }
        assertThat(ex.message!!).contains("bogus")
    }

    @Test
    fun `validateColumnsExist fails on an unknown column in the WHERE clause`() {
        val ex = assertFailsWith<IllegalStateException> {
            SqlValidator.validateColumnsExist(
                "findAllByBogus",
                parse("select id, name, email from users where bogus = :bogus"),
                "users",
                userColumnSet
            )
        }
        assertThat(ex.message!!).contains("bogus")
    }

    @Test
    fun `validateColumnsExist fails on an unknown column in ORDER BY`() {
        assertFailsWith<IllegalStateException> {
            SqlValidator.validateColumnsExist(
                "findAll",
                parse("select id, name, email from users order by created_at"),
                "users",
                userColumnSet
            )
        }
    }

    @Test
    fun `validateColumnsExist fails on an unknown column in GROUP BY`() {
        assertFailsWith<IllegalStateException> {
            SqlValidator.validateColumnsExist(
                "countAll",
                parse("select count(*) from users group by bogus"),
                "users",
                userColumnSet
            )
        }
    }

    @Test
    fun `validateColumnsExist is case-insensitive`() {
        SqlValidator.validateColumnsExist("findAll", parse("select ID, NAME, EMAIL from users"), "users", userColumnSet)
    }

    @Test
    fun `validateColumnsExist resolves table-aliased columns`() {
        SqlValidator.validateColumnsExist(
            "findAll",
            parse("select u.id, u.name, u.email from users u where u.email = :email"),
            "users",
            userColumnSet
        )
        val ex = assertFailsWith<IllegalStateException> {
            SqlValidator.validateColumnsExist("findAll", parse("select u.bogus from users u"), "users", userColumnSet)
        }
        assertThat(ex.message!!).contains("bogus")
    }

    @Test
    fun `validateColumnsExist skips a query with joins`() {
        SqlValidator.validateColumnsExist(
            "findAll",
            parse("select bogus from users u join orders o on u.id = o.user_id"),
            "users",
            userColumnSet
        )
    }

    @Test
    fun `validateColumnsExist skips a query against a different table`() {
        SqlValidator.validateColumnsExist("findAll", parse("select bogus from orders"), "users", userColumnSet)
    }

    @Test
    fun `validateColumnsExist skips a subselect FROM clause`() {
        SqlValidator.validateColumnsExist(
            "findAll",
            parse("select bogus from (select 1 as bogus from orders) t"),
            "users",
            userColumnSet
        )
    }

    @Test
    fun `validateColumnsExist validates a DELETE WHERE clause`() {
        SqlValidator.validateColumnsExist(
            "deleteByEmail",
            parse("delete from users where email = :email"),
            "users",
            userColumnSet
        )
        val ex = assertFailsWith<IllegalStateException> {
            SqlValidator.validateColumnsExist(
                "deleteByBogus",
                parse("delete from users where bogus = :bogus"),
                "users",
                userColumnSet
            )
        }
        assertThat(ex.message!!).contains("bogus")
    }

    @Test
    fun `validateColumnsExist validates an UPDATE SET and WHERE clause`() {
        SqlValidator.validateColumnsExist(
            "execute",
            parse("update users set name = :name where id = :id"),
            "users",
            userColumnSet
        )
        // Unknown column in SET.
        assertFailsWith<IllegalStateException> {
            SqlValidator.validateColumnsExist(
                "execute",
                parse("update users set bogus = :bogus where id = :id"),
                "users",
                userColumnSet
            )
        }
        // Unknown column in WHERE.
        assertFailsWith<IllegalStateException> {
            SqlValidator.validateColumnsExist(
                "execute",
                parse("update users set name = :name where bogus = :bogus"),
                "users",
                userColumnSet
            )
        }
    }

    // =============================================================================================
    // expandSelectStar (optimization)
    // =============================================================================================

    @Test
    fun `expandSelectStar rewrites SELECT star into explicit columns`() {
        val sql = "select * from users"
        val out = SqlValidator.expandSelectStar(parse(sql), sql, "users", userColumns)
        assertThat(out).contains("id, name, email")
        assertThat(out).doesNotContain("*")
    }

    @Test
    fun `expandSelectStar qualifies columns with the table alias`() {
        val sql = "select * from users u"
        val out = SqlValidator.expandSelectStar(parse(sql), sql, "users", userColumns)
        assertThat(out).contains("u.id, u.name, u.email")
    }

    @Test
    fun `expandSelectStar leaves count star untouched`() {
        val sql = "select count(*) from users"
        assertThat(SqlValidator.expandSelectStar(parse(sql), sql, "users", userColumns)).isEqualTo(sql)
    }

    @Test
    fun `expandSelectStar leaves an explicit column list untouched`() {
        val sql = "select id from users"
        assertThat(SqlValidator.expandSelectStar(parse(sql), sql, "users", userColumns)).isEqualTo(sql)
    }

    @Test
    fun `expandSelectStar leaves a join untouched`() {
        val sql = "select * from users u join orders o on u.id = o.user_id"
        assertThat(SqlValidator.expandSelectStar(parse(sql), sql, "users", userColumns)).isEqualTo(sql)
    }

    @Test
    fun `expandSelectStar leaves a different table untouched`() {
        val sql = "select * from orders"
        assertThat(SqlValidator.expandSelectStar(parse(sql), sql, "users", userColumns)).isEqualTo(sql)
    }

    @Test
    fun `expandSelectStar is a no-op when there are no columns`() {
        val sql = "select * from users"
        assertThat(SqlValidator.expandSelectStar(parse(sql), sql, "users", emptyList())).isEqualTo(sql)
    }

    // =============================================================================================
    // withRowLimit (optimization)
    // =============================================================================================

    @Test
    fun `withRowLimit appends a LIMIT to a select without one`() {
        val sql = "select * from users where id = :id"
        assertThat(SqlValidator.withRowLimit(parse(sql), sql, 2)).contains("LIMIT 2")
    }

    @Test
    fun `withRowLimit leaves a query that already has a LIMIT untouched`() {
        val sql = "select * from users limit 5"
        assertThat(SqlValidator.withRowLimit(parse(sql), sql, 2)).isEqualTo(sql)
    }

    @Test
    fun `withRowLimit is a no-op for a non-plain-select`() {
        val sql = "delete from users where id = :id"
        assertThat(SqlValidator.withRowLimit(parse(sql), sql, 2)).isEqualTo(sql)
    }

    // =============================================================================================
    // toExistsQuery (optimization)
    // =============================================================================================

    @Test
    fun `toExistsQuery wraps a select over the entity table`() {
        val sql = "select * from users where email = :email"
        val out = SqlValidator.toExistsQuery(parse(sql), sql, "users")
        assertThat(out).contains("SELECT EXISTS(")
        assertThat(out).contains("SELECT 1 FROM users")
        assertThat(out).contains("WHERE email = :email")
    }

    @Test
    fun `toExistsQuery leaves a select over a different table untouched`() {
        val sql = "select 1 from orders where id = :id"
        assertThat(SqlValidator.toExistsQuery(parse(sql), sql, "users")).isEqualTo(sql)
    }

    @Test
    fun `toExistsQuery is a no-op for a non-plain-select`() {
        val sql = "delete from users where id = :id"
        assertThat(SqlValidator.toExistsQuery(parse(sql), sql, "users")).isEqualTo(sql)
    }

    // =============================================================================================
    // reject-grouping-in-scalar
    // =============================================================================================

    @Test
    fun `rejectGroupingInScalar fails on GROUP BY`() {
        val ex = assertFailsWith<IllegalStateException> {
            SqlValidator.rejectGroupingInScalar("countByName", parse("select count(*) from users group by name"))
        }
        assertThat(ex.message!!).contains("GROUP BY")
    }

    @Test
    fun `rejectGroupingInScalar fails on HAVING`() {
        val ex = assertFailsWith<IllegalStateException> {
            SqlValidator.rejectGroupingInScalar("countAll", parse("select count(*) from users having count(*) > 0"))
        }
        assertThat(ex.message!!).contains("HAVING")
    }

    @Test
    fun `rejectGroupingInScalar passes on a plain scalar select`() {
        SqlValidator.rejectGroupingInScalar("countByEmail", parse("select count(*) from users where email = :email"))
    }

    @Test
    fun `rejectGroupingInScalar ignores non-plain-select statements`() {
        SqlValidator.rejectGroupingInScalar("deleteById", parse("delete from users where id = :id"))
    }

    // =============================================================================================
    // expand-returning-star
    // =============================================================================================

    @Test
    fun `expandReturningStar rewrites INSERT RETURNING star into explicit columns`() {
        val sql = "insert into users (name, email) values ('a', 'b') returning *"
        val out = SqlValidator.expandReturningStar(parse(sql), sql, "users", userColumns)
        assertThat(out).contains("RETURNING id, name, email")
        assertThat(out).doesNotContain("RETURNING *")
    }

    @Test
    fun `expandReturningStar rewrites UPDATE RETURNING star into explicit columns`() {
        val sql = "update users set name = 'a' where id = :id returning *"
        val out = SqlValidator.expandReturningStar(parse(sql), sql, "users", userColumns)
        assertThat(out).contains("id, name, email")
        assertThat(out).doesNotContain("RETURNING *")
    }

    @Test
    fun `expandReturningStar leaves an explicit RETURNING list untouched`() {
        val sql = "insert into users (name, email) values ('a', 'b') returning id"
        assertThat(SqlValidator.expandReturningStar(parse(sql), sql, "users", userColumns)).isEqualTo(sql)
    }

    @Test
    fun `expandReturningStar leaves a write against a different table untouched`() {
        val sql = "insert into audit (name) values ('a') returning *"
        assertThat(SqlValidator.expandReturningStar(parse(sql), sql, "users", userColumns)).isEqualTo(sql)
    }

    @Test
    fun `expandReturningStar leaves a plain select untouched`() {
        val sql = "select * from users where id = :id"
        assertThat(SqlValidator.expandReturningStar(parse(sql), sql, "users", userColumns)).isEqualTo(sql)
    }

    // =============================================================================================
    // drop-redundant-order-by
    // =============================================================================================

    @Test
    fun `dropOrderBy strips an ORDER BY clause`() {
        val sql = "select 1 from users where email = :email order by name desc"
        assertThat(SqlValidator.dropOrderBy(parse(sql), sql)).doesNotContain("ORDER BY")
    }

    @Test
    fun `dropOrderBy is a no-op when there is no ORDER BY`() {
        val sql = "select 1 from users where email = :email"
        assertThat(SqlValidator.dropOrderBy(parse(sql), sql)).isEqualTo(sql)
    }
}
