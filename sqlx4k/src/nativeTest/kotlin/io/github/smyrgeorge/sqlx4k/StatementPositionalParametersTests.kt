package io.github.smyrgeorge.sqlx4k

import assertk.all
import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.doesNotContain
import assertk.assertions.isEqualTo
import kotlin.test.Test
import kotlin.test.assertFailsWith

class StatementPositionalParametersTests {

    @Test
    fun `Positional parameters - multiple SQL injection attempts are properly escaped`() {
        val sql = "SELECT * FROM users WHERE username = ? AND password = ?"
        val maliciousUsername = "admin' -- "
        val maliciousPassword = "' OR '1'='1"

        val res = Statement.create(sql)
            .bind(0, maliciousUsername)
            .bind(1, maliciousPassword)
            .render()

        assertThat(res).all {
            contains("username = 'admin'' -- '")
            contains("password = ''' OR ''1''=''1'")
            // Ensure the rendered SQL doesn't contain unescaped quotes that could break out of string literals
            doesNotContain("username = 'admin' -- ")
            doesNotContain("password = '' OR '1'='1")
        }
    }

    @Test
    fun `Positional parameters - handling numeric parameters with injection attempts`() {
        val sql = "SELECT * FROM orders WHERE id = ? AND user_id = ?"
        val maliciousId = "1 OR 1=1"
        val userId = 42

        val res = Statement.create(sql)
            .bind(0, maliciousId)
            .bind(1, userId)
            .render()

        assertThat(res).all {
            // String parameter should be quoted
            contains("id = '1 OR 1=1'")
            // Numeric parameter should be rendered as is
            contains("user_id = 42")
        }
    }

    @Test
    fun `Positional parameters - prevent LIKE wildcard injection`() {
        val sql = "SELECT * FROM products WHERE name LIKE ?"
        val maliciousPattern = "%'; DELETE FROM products; --"

        val res = Statement.create(sql)
            .bind(0, maliciousPattern)
            .render()

        assertThat(res).all {
            contains("name LIKE '%''; DELETE FROM products; --'")
            doesNotContain("name LIKE '%'; DELETE FROM products; --")
        }
    }

    @Test
    fun `Positional parameters - stack of multiple parameters with various types`() {
        val sql = "INSERT INTO users (username, age, is_admin, created_at) VALUES (?, ?, ?, ?)"
        val maliciousUsername = "admin'); INSERT INTO users VALUES ('hacker"
        val age = 25
        val isAdmin = false
        val createdAt = "2023-01-01"

        val res = Statement.create(sql)
            .bind(0, maliciousUsername)
            .bind(1, age)
            .bind(2, isAdmin)
            .bind(3, createdAt)
            .render()

        assertThat(res).all {
            contains("VALUES ('admin''); INSERT INTO users VALUES (''hacker'")
            contains("25")
            contains("false")
            contains("'2023-01-01'")
        }
    }

    @Test
    fun `Positional parameters - out of order binding with SQL injection attempts`() {
        val sql = "SELECT * FROM logs WHERE level = ? AND message LIKE ? AND timestamp > ?"
        val maliciousMessage = "%'; DROP TABLE logs; --"

        val res = Statement.create(sql)
            .bind(2, "2023-01-01")
            .bind(0, "ERROR")
            .bind(1, maliciousMessage)
            .render()

        assertThat(res).all {
            contains("level = 'ERROR'")
            contains("message LIKE '%''; DROP TABLE logs; --'")
            contains("timestamp > '2023-01-01'")
        }
    }

    @Test
    fun `Positional parameters - special characters in SQL identifiers`() {
        val sql = "SELECT * FROM users WHERE username = ? AND \"user-data\".\"last-login\" > ?"
        val maliciousUsername = "admin\"; SELECT * FROM passwords; --"

        val res = Statement.create(sql)
            .bind(0, maliciousUsername)
            .bind(1, "2023-01-01")
            .render()

        assertThat(res).all {
            contains("username = 'admin\"; SELECT * FROM passwords; --'")
            contains("\"user-data\".\"last-login\" > '2023-01-01'")
        }
    }

    @Test
    fun `Positional parameters - long sequence of parameters with injection attempts`() {
        val sql =
            "SELECT * FROM log_entries WHERE id = ? OR level = ? OR message LIKE ? OR timestamp > ? OR user_id = ?"

        val res = Statement.create(sql)
            .bind(0, "1; DROP TABLE logs")
            .bind(1, "ERROR' OR '1'='1")
            .bind(2, "%' UNION SELECT * FROM users; --")
            .bind(3, "2023-01-01' OR '1'='1")
            .bind(4, "5' OR 1=1; --")
            .render()

        assertThat(res).all {
            contains("id = '1; DROP TABLE logs'")
            contains("level = 'ERROR'' OR ''1''=''1'")
            contains("message LIKE '%'' UNION SELECT * FROM users; --'")
            contains("timestamp > '2023-01-01'' OR ''1''=''1'")
            contains("user_id = '5'' OR 1=1; --'")
        }
    }

    @Test
    fun `Positional parameters - mixed with literal SQL containing questionmarks`() {
        val sql = "SELECT * FROM users WHERE username = ? AND status = '?' AND role = ?"
        val maliciousUsername = "admin' OR status='active"
        val role = "user' OR 1=1; --"

        val res = Statement.create(sql)
            .bind(0, maliciousUsername)
            .bind(1, role)
            .render()

        assertThat(res).all {
            contains("username = 'admin'' OR status=''active'")
            // The literal question mark should remain unchanged
            contains("status = '?'")
            contains("role = 'user'' OR 1=1; --'")
        }
    }

    @Test
    fun `Positional parameters - boundary values and edge cases`() {
        val sql = "SELECT * FROM users WHERE id BETWEEN ? AND ? OR username = ?"

        val res = Statement.create(sql)
            .bind(0, "0 OR 1=1")
            .bind(1, "100; DROP TABLE users")
            .bind(2, "'; SELECT * FROM secrets; --")
            .render()

        assertThat(res).all {
            contains("id BETWEEN '0 OR 1=1' AND '100; DROP TABLE users'")
            contains("username = '''; SELECT * FROM secrets; --'")
        }
    }

    @Test
    fun `Positional parameters - null handling in queries`() {
        val sql = "SELECT * FROM users WHERE id = ? OR username = ?"
        val statement = Statement.create(sql)
            .bind(0, 123)
            .bind(1, null)

        val rendered = statement.render()

        assertThat(rendered).isEqualTo("SELECT * FROM users WHERE id = 123 OR username = null")
    }

    @Test
    fun `Positional parameters - index out of bounds exception`() {
        val sql = "SELECT * FROM users WHERE id = ?"
        val statement = Statement.create(sql)

        val exception = assertFailsWith<SQLError> {
            statement.bind(1, "value") // Index 1 is out of bounds (should be 0)
        }

        assertThat(exception.code).isEqualTo(SQLError.Code.PositionalParameterOutOfBounds)
    }

    @Test
    fun `Positional parameters - boolean values handling`() {
        val sql = "SELECT * FROM users WHERE is_active = ? AND is_verified = ?"
        val statement = Statement.create(sql)
            .bind(0, true)
            .bind(1, false)

        val rendered = statement.render()

        assertThat(rendered).isEqualTo("SELECT * FROM users WHERE is_active = true AND is_verified = false")
    }

    @Test
    fun `Positional parameters - handling empty strings`() {
        val sql = "SELECT * FROM users WHERE name = ? OR bio = ?"
        val statement = Statement.create(sql)
            .bind(0, "")
            .bind(1, "   ")

        val rendered = statement.render()

        assertThat(rendered).isEqualTo("SELECT * FROM users WHERE name = '' OR bio = '   '")
    }

    @Test
    fun `Positional parameters - binding the same index multiple times should override`() {
        val sql = "SELECT * FROM users WHERE id = ?"
        val statement = Statement.create(sql)
            .bind(0, 123)
            .bind(0, 456) // Should override the previous value

        val rendered = statement.render()

        assertThat(rendered).isEqualTo("SELECT * FROM users WHERE id = 456")
    }
}