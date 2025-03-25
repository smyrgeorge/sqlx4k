package io.github.smyrgeorge.sqlx4k

import assertk.all
import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.doesNotContain
import kotlin.test.Test

class StatementNamedParametersTests {

    @Test
    fun `Named parameter - SQL injection attempt with quotes in string is properly escaped`() {
        val sql = "SELECT * FROM users WHERE username = :username"
        val maliciousInput = "admin' OR '1'='1"

        val res = Statement.create(sql)
            .bind("username", maliciousInput)
            .render()

        // Verify the malicious input is properly escaped
        assertThat(res).all {
            contains("username = 'admin'' OR ''1''=''1'")
            doesNotContain("username = admin' OR '1'='1")
        }
    }

    @Test
    fun `Positional parameter - SQL injection attempt with quotes in string is properly escaped`() {
        val sql = "SELECT * FROM users WHERE username = ?"
        val maliciousInput = "admin' OR '1'='1"

        val res = Statement.create(sql)
            .bind(0, maliciousInput)
            .render()

        // Verify the malicious input is properly escaped
        assertThat(res).all {
            contains("username = 'admin'' OR ''1''=''1'")
            doesNotContain("username = admin' OR '1'='1")
        }
    }

    @Test
    fun `Named parameter - SQL injection attempt with semicolon and multiple statements`() {
        val sql = "SELECT * FROM users WHERE id = :id"
        val maliciousInput = "1; DROP TABLE users; --"

        val res = Statement.create(sql)
            .bind("id", maliciousInput)
            .render()

        // Verify the input is handled as a string literal, not executed as separate commands
        assertThat(res).contains("id = '1; DROP TABLE users; --'")
    }

    @Test
    fun `Positional parameter - SQL injection with UNION attack is properly escaped`() {
        val sql = "SELECT * FROM products WHERE category = ?"
        val maliciousInput = "1' UNION SELECT username, password FROM users; --"

        val res = Statement.create(sql)
            .bind(0, maliciousInput)
            .render()

        // Verify the UNION attack is escaped
        assertThat(res).contains("category = '1'' UNION SELECT username, password FROM users; --'")
    }

    @Test
    fun `Named parameter - SQL injection with comment attempt is properly escaped`() {
        val sql = "SELECT * FROM users WHERE username = :username AND password = :password"
        val maliciousUsername = "admin' -- "
        val password = "anything"

        val res = Statement.create(sql)
            .bind("username", maliciousUsername)
            .bind("password", password)
            .render()

        // Verify comment attempt is escaped
        assertThat(res).all {
            contains("username = 'admin'' -- '")
            contains("password = 'anything'")
        }
    }

    @Test
    fun `SQL injection attempt with Unicode escape sequence`() {
        val sql = "SELECT * FROM users WHERE username = :username"
        val maliciousInput = "admin\\u0027 OR 1=1"

        val res = Statement.create(sql)
            .bind("username", maliciousInput)
            .render()

        // Verify that Unicode escape sequences are treated as literal strings
        assertThat(res).contains("username = 'admin\\u0027 OR 1=1'")
    }

    @Test
    fun `Null parameter handling prevents SQL injection`() {
        val sql = "SELECT * FROM users WHERE username IS NULL OR username = :username"

        val res = Statement.create(sql)
            .bind("username", null)
            .render()

        // Verify null is rendered correctly as NULL without quotes
        assertThat(res).contains("username IS NULL OR username = null")
    }

    @Test
    fun `SQL injection attempt with special characters and boolean expressions`() {
        val sql = "SELECT * FROM users WHERE active = :active"
        val maliciousInput = "1 OR 1=1"

        // This tests how non-string types are handled
        val res = Statement.create(sql)
            .bind("active", maliciousInput)
            .render()

        // The input should be treated as a string since it's not a boolean
        assertThat(res).contains("active = '1 OR 1=1'")
    }

    @Test
    fun `SQL injection attempt with encoded newline characters`() {
        val sql = "SELECT * FROM users WHERE username = :username"
        val maliciousInput = "admin\nOR 1=1"

        val res = Statement.create(sql)
            .bind("username", maliciousInput)
            .render()

        // Verify newline characters don't break the SQL statement
        assertThat(res).contains("username = 'admin\nOR 1=1'")
    }

    @Test
    fun `Multiple injection attempts in different parameters are all properly escaped`() {
        val sql = "SELECT * FROM users WHERE username = :username AND role = :role"
        val maliciousUsername = "admin' --"
        val maliciousRole = "user' OR '1'='1"

        val res = Statement.create(sql)
            .bind("username", maliciousUsername)
            .bind("role", maliciousRole)
            .render()

        assertThat(res).all {
            contains("username = 'admin'' --'")
            contains("role = 'user'' OR ''1''=''1'")
        }
    }

    @Test
    fun `Parameter binding prevents injecting into table or column names`() {
        val sql = "SELECT * FROM :tableName WHERE id = :id"

        // Attempting to inject a table name should not work as expected
        // The parameter will be quoted as a string
        val res = Statement.create(sql)
            .bind("tableName", "users; DROP TABLE secrets; --")
            .bind("id", 1)
            .render()

        assertThat(res).contains("FROM 'users; DROP TABLE secrets; --'")
    }
}