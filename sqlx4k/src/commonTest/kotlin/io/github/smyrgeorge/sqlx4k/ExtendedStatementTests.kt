package io.github.smyrgeorge.sqlx4k

import assertk.all
import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.doesNotContain
import assertk.assertions.isEqualTo
import io.github.smyrgeorge.sqlx4k.impl.statement.ExtendedStatement
import kotlin.test.Test
import kotlin.test.assertFailsWith

class ExtendedStatementTests {

    @Test
    fun `Basic test for PostgreSQL-style positional parameters with integers`() {
        val sql = "SELECT * FROM users WHERE id > $1 AND id < $2"
        val res = ExtendedStatement(sql)
            .bind(0, 65)
            .bind(1, 66)
            .render()

        assertThat(res).all {
            contains("id > 65")
            contains("id < 66")
        }
    }

    @Test
    fun `Basic test for PostgreSQL-style positional parameters with strings`() {
        val sql = "SELECT * FROM users WHERE username = $1"
        val res = ExtendedStatement(sql)
            .bind(0, "test_user")
            .render()

        assertThat(res).contains("username = 'test_user'")
    }

    @Test
    fun `Binding PostgreSQL-style positional parameters out of order`() {
        val sql = "SELECT * FROM users WHERE id > $1 AND id < $2"
        val res = ExtendedStatement(sql)
            .bind(1, 66)
            .bind(0, 65)
            .render()

        assertThat(res).all {
            contains("id > 65")
            contains("id < 66")
        }
    }

    @Test
    fun `PostgreSQL-style positional parameters with null values`() {
        val sql = "SELECT * FROM users WHERE id = $1 OR username = $2"
        val res = ExtendedStatement(sql)
            .bind(0, 123)
            .bind(1, null)
            .render()

        assertThat(res).isEqualTo("SELECT * FROM users WHERE id = 123 OR username = null")
    }

    @Test
    fun `PostgreSQL-style positional parameters with boolean values`() {
        val sql = "SELECT * FROM users WHERE is_active = $1 AND is_verified = $2"
        val res = ExtendedStatement(sql)
            .bind(0, true)
            .bind(1, false)
            .render()

        assertThat(res).isEqualTo("SELECT * FROM users WHERE is_active = true AND is_verified = false")
    }

    @Test
    fun `PostgreSQL-style positional parameters with empty strings`() {
        val sql = "SELECT * FROM users WHERE name = $1 OR bio = $2"
        val res = ExtendedStatement(sql)
            .bind(0, "")
            .bind(1, "   ")
            .render()

        assertThat(res).isEqualTo("SELECT * FROM users WHERE name = '' OR bio = '   '")
    }

    @Test
    fun `PostgreSQL-style positional parameters - binding the same index multiple times should override`() {
        val sql = "SELECT * FROM users WHERE id = $1"
        val res = ExtendedStatement(sql)
            .bind(0, 123)
            .bind(0, 456) // Should override the previous value
            .render()

        assertThat(res).isEqualTo("SELECT * FROM users WHERE id = 456")
    }

    @Test
    fun `PostgreSQL-style positional parameters - index out of bounds exception`() {
        val sql = "SELECT * FROM users WHERE id = $1"
        val statement = ExtendedStatement(sql)

        val exception = assertFailsWith<SQLError> {
            statement.bind(1, "value") // Index 1 is out of bounds (should be 0)
        }

        assertThat(exception.code).isEqualTo(SQLError.Code.PositionalParameterOutOfBounds)
    }

    @Test
    fun `PostgreSQL-style positional parameters - missing parameter value exception`() {
        val sql = "SELECT * FROM users WHERE id = $1 AND username = $2"
        val statement = ExtendedStatement(sql)
            .bind(0, 123)
        // Not binding the second parameter

        val exception = assertFailsWith<SQLError> {
            statement.render()
        }

        assertThat(exception.code).isEqualTo(SQLError.Code.PositionalParameterValueNotSupplied)
    }

    @Test
    fun `PostgreSQL-style positional parameters - SQL injection prevention`() {
        val sql = "SELECT * FROM users WHERE username = $1"
        val maliciousUsername = "admin'; DROP TABLE users; --"

        val res = ExtendedStatement(sql)
            .bind(0, maliciousUsername)
            .render()

        assertThat(res).all {
            contains("username = 'admin''; DROP TABLE users; --'")
            doesNotContain("username = 'admin'; DROP TABLE users; --'")
        }
    }

    @Test
    fun `PostgreSQL-style positional parameters - multiple parameters with various types`() {
        val sql = "INSERT INTO users (username, age, is_admin, created_at) VALUES ($1, $2, $3, $4)"
        val username = "test_user"
        val age = 25
        val isAdmin = false
        val createdAt = "2023-01-01"

        val res = ExtendedStatement(sql)
            .bind(0, username)
            .bind(1, age)
            .bind(2, isAdmin)
            .bind(3, createdAt)
            .render()

        assertThat(res).all {
            contains("VALUES ('test_user'")
            contains("25")
            contains("false")
            contains("'2023-01-01'")
        }
    }

    @Test
    fun `PostgreSQL-style positional parameters - with custom value encoder`() {
        class CustomType(val value: String)
        class CustomTypeEncoder : ValueEncoder<CustomType> {
            override fun encode(value: CustomType): Any = value.value
        }

        val encoders = ValueEncoderRegistry()
            .register(CustomType::class, CustomTypeEncoder())

        val sql = "SELECT * FROM data WHERE custom_field = $1"
        val customValue = CustomType("custom_value")

        val res = ExtendedStatement(sql)
            .bind(0, customValue)
            .render(encoders)

        assertThat(res).contains("custom_field = 'custom_value'")
    }

    @Test
    fun `PostgreSQL-style positional parameters with special characters in strings`() {
        val sql = "SELECT * FROM users WHERE message = $1"
        val res = ExtendedStatement(sql)
            .bind(0, "Message with 'quotes' and \"double quotes\" and \\ backslashes")
            .render()

        assertThat(res).contains("message = 'Message with ''quotes'' and \"double quotes\" and \\ backslashes'")
    }

    @Test
    fun `PostgreSQL-style positional parameters with decimal numbers`() {
        val sql = "SELECT * FROM products WHERE price > $1 AND weight < $2"
        val res = ExtendedStatement(sql)
            .bind(0, 19.99)
            .bind(1, 2.5)
            .render()

        assertThat(res).all {
            contains("price > 19.99")
            contains("weight < 2.5")
        }
    }

    @Test
    fun `PostgreSQL-style positional parameters with large numbers`() {
        val sql = "SELECT * FROM metrics WHERE value = $1"
        val res = ExtendedStatement(sql)
            .bind(0, 9223372036854775807L) // Long.MAX_VALUE
            .render()

        assertThat(res).contains("value = 9223372036854775807")
    }

    @Test
    fun `PostgreSQL-style positional parameters with repeated parameters`() {
        val sql = "SELECT * FROM users WHERE id = $1 OR parent_id = $1"
        val res = ExtendedStatement(sql)
            .bind(0, 123)
            .render()

        assertThat(res).isEqualTo("SELECT * FROM users WHERE id = 123 OR parent_id = 123")
    }

    @Test
    fun `PostgreSQL-style positional parameters with consecutive parameters`() {
        val sql = "SELECT * FROM users WHERE id IN ($1, $2, $3, $4, $5)"
        val res = ExtendedStatement(sql)
            .bind(0, 1)
            .bind(1, 2)
            .bind(2, 3)
            .bind(3, 4)
            .bind(4, 5)
            .render()

        assertThat(res).isEqualTo("SELECT * FROM users WHERE id IN (1, 2, 3, 4, 5)")
    }

    @Test
    fun `PostgreSQL-style positional parameters with dates or timestamps`() {
        val sql = "SELECT * FROM events WHERE created_at > $1"
        val res = ExtendedStatement(sql)
            .bind(0, "2023-06-15T14:30:00Z")
            .render()

        assertThat(res).contains("created_at > '2023-06-15T14:30:00Z'")
    }

    @Test
    fun `PostgreSQL-style positional parameters inside complex expressions`() {
        val sql = "SELECT * FROM users WHERE (age BETWEEN $1 AND $2) OR (salary > $3 AND department = $4)"
        val res = ExtendedStatement(sql)
            .bind(0, 25)
            .bind(1, 45)
            .bind(2, 50000)
            .bind(3, "Engineering")
            .render()

        assertThat(res).all {
            contains("age BETWEEN 25 AND 45")
            contains("salary > 50000")
            contains("department = 'Engineering'")
        }
    }

    @Test
    fun `PostgreSQL-style positional parameters - render without binding`() {
        val sql = "SELECT * FROM users WHERE id = $1"
        val statement = ExtendedStatement(sql)

        val exception = assertFailsWith<SQLError> {
            statement.render() // No parameters bound
        }

        assertThat(exception.code).isEqualTo(SQLError.Code.PositionalParameterValueNotSupplied)
    }
}