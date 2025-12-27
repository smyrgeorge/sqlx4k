package io.github.smyrgeorge.sqlx4k

import assertk.all
import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.doesNotContain
import kotlin.test.Test

class StatementCollectionsTests {

    @Test
    fun `Bind list to positional parameter`() {
        val sql = "select * from sqlx4k where id IN ?"
        val list = listOf(1, 2, 3, 4, 5)
        val res = Statement.create(sql)
            .bind(0, list)
            .render()

        assertThat(res).contains("id IN (1, 2, 3, 4, 5)")
    }

    @Test
    fun `Bind list to named parameter`() {
        val sql = "select * from sqlx4k where id IN :ids"
        val list = listOf(10, 20, 30, 40, 50)
        val res = Statement.create(sql)
            .bind("ids", list)
            .render()

        assertThat(res).contains("id IN (10, 20, 30, 40, 50)")
    }

    @Test
    fun `Bind empty list to parameter`() {
        val sql = "select * from sqlx4k where id IN ?"
        val emptyList = emptyList<Int>()
        val res = Statement.create(sql)
            .bind(0, emptyList)
            .render()

        assertThat(res).contains("id IN ()")
    }

    @Test
    fun `Bind set to positional parameter`() {
        val sql = "select * from sqlx4k where name IN ?"
        val set = setOf("apple", "banana", "cherry")
        val res = Statement.create(sql)
            .bind(0, set)
            .render()

        assertThat(res).contains("name IN ('apple', 'banana', 'cherry')")
    }

    @Test
    fun `Bind set to named parameter`() {
        val sql = "select * from sqlx4k where category IN :categories"
        val set = setOf("fruit", "vegetable", "dairy")
        val res = Statement.create(sql)
            .bind("categories", set)
            .render()

        assertThat(res).contains("category IN ('fruit', 'vegetable', 'dairy')")
    }

    @Test
    fun `Bind array to positional parameter`() {
        val sql = "select * from sqlx4k where code IN ?"
        val array = arrayOf("A001", "B002", "C003")
        val res = Statement.create(sql)
            .bind(0, array)
            .render()

        assertThat(res).contains("code IN ('A001', 'B002', 'C003')")
    }

    @Test
    fun `Bind array to named parameter`() {
        val sql = "select * from sqlx4k where status IN :statuses"
        val array = arrayOf("PENDING", "ACTIVE", "COMPLETED")
        val res = Statement.create(sql)
            .bind("statuses", array)
            .render()

        assertThat(res).contains("status IN ('PENDING', 'ACTIVE', 'COMPLETED')")
    }

    @Test
    fun `Bind primitive array to parameter`() {
        val sql = "select * from sqlx4k where quantity IN ?"
        val intArray = intArrayOf(5, 10, 15, 20)
        val res = Statement.create(sql)
            .bind(0, intArray)
            .render()

        assertThat(res).contains("quantity IN (5, 10, 15, 20)")
    }

    @Test
    fun `Bind list of custom objects with custom encoder`() {
        // Custom data class
        data class Product(val id: Int, val name: String)

        // Custom encoder for Product that extracts the id
        class ProductEncoder : ValueEncoder<Product> {
            override fun encode(value: Product): Any {
                return value.id
            }
        }

        // Register the encoder
        val encoders = ValueEncoderRegistry().register(ProductEncoder())

        // List of products
        val products = listOf(
            Product(1, "Laptop"),
            Product(2, "Phone"),
            Product(3, "Tablet")
        )

        val sql = "select * from products where product_id IN :productIds"
        val res = Statement.create(sql)
            .bind("productIds", products)
            .render(encoders)

        assertThat(res).contains("product_id IN (1, 2, 3)")
    }

    @Test
    fun `Multiple collection parameters in one query`() {
        val sql = "select * from sqlx4k where id IN ? AND category IN ? AND status = ?"
        val res = Statement.create(sql)
            .bind(0, listOf(1, 2, 3))
            .bind(1, setOf("A", "B", "C"))
            .bind(2, "ACTIVE")
            .render()

        assertThat(res).all {
            contains("id IN (1, 2, 3)")
            contains("category IN ('A', 'B', 'C')")
            contains("status = 'ACTIVE'")
        }
    }

    @Test
    fun `Mixed collection and scalar parameters with named parameters`() {
        val sql = "select * from sqlx4k where id IN :ids AND category = :category"
        val res = Statement.create(sql)
            .bind("ids", listOf(100, 200, 300))
            .bind("category", "electronics")
            .render()

        assertThat(res).all {
            contains("id IN (100, 200, 300)")
            contains("category = 'electronics'")
        }
    }

    @Test
    fun `List with SQL injection attempts is properly escaped`() {
        val sql = "SELECT * FROM users WHERE username IN ?"
        val maliciousInputs = listOf(
            "admin",
            "user' OR '1'='1",
            "guest'; DROP TABLE users; --"
        )

        val res = Statement.create(sql)
            .bind(0, maliciousInputs)
            .render()

        // Verify each element is properly escaped
        assertThat(res).all {
            contains("username IN ('admin', 'user'' OR ''1''=''1', 'guest''; DROP TABLE users; --')")
            // Ensure the rendered SQL doesn't contain unescaped quotes
            doesNotContain("user' OR '1'='1")
            doesNotContain("guest'; DROP TABLE users; --")
        }
    }

    @Test
    fun `Named parameter with list of SQL injection attempts is properly escaped`() {
        val sql = "SELECT * FROM products WHERE category IN :categories"
        val maliciousInputs = listOf(
            "electronics",
            "furniture' UNION SELECT username, password FROM users; --",
            "toys'); DELETE FROM products; --"
        )

        val res = Statement.create(sql)
            .bind("categories", maliciousInputs)
            .render()

        // Verify each element is properly escaped
        assertThat(res).all {
            contains("category IN ('electronics', 'furniture'' UNION SELECT username, password FROM users; --', 'toys''); DELETE FROM products; --')")
            // Ensure the rendered SQL doesn't contain unescaped quotes
            doesNotContain("furniture' UNION SELECT")
            doesNotContain("toys'); DELETE FROM")
        }
    }

    @Test
    fun `Array with SQL injection attempts is properly escaped`() {
        val sql = "SELECT * FROM logs WHERE level IN ?"
        val maliciousInputs = arrayOf(
            "INFO",
            "ERROR' OR '1'='1",
            "DEBUG'; DROP TABLE logs; --"
        )

        val res = Statement.create(sql)
            .bind(0, maliciousInputs)
            .render()

        // Verify each element is properly escaped
        assertThat(res).all {
            contains("level IN ('INFO', 'ERROR'' OR ''1''=''1', 'DEBUG''; DROP TABLE logs; --')")
            // Ensure the rendered SQL doesn't contain unescaped quotes
            doesNotContain("ERROR' OR '1'='1")
            doesNotContain("DEBUG'; DROP TABLE logs; --")
        }
    }

    @Test
    fun `Set with SQL injection attempts is properly escaped`() {
        val sql = "SELECT * FROM permissions WHERE role IN :roles"
        val maliciousInputs = setOf(
            "admin",
            "user' OR admin='true",
            "guest'; TRUNCATE TABLE permissions; --"
        )

        val res = Statement.create(sql)
            .bind("roles", maliciousInputs)
            .render()

        // Verify each element is properly escaped
        // Note: Set order is not guaranteed, so we check for individual escaped strings
        assertThat(res).all {
            contains("'admin'")
            contains("'user'' OR admin=''true'")
            contains("'guest''; TRUNCATE TABLE permissions; --'")
            // Ensure the rendered SQL doesn't contain unescaped quotes
            doesNotContain("user' OR admin='true")
            doesNotContain("guest'; TRUNCATE TABLE permissions; --")
        }
    }

    @Test
    fun `Empty collection with SQL injection in query is properly handled`() {
        // This tests that even if the collection is empty, the SQL is still properly formed
        // and doesn't allow for injection in the surrounding query
        val sql = "SELECT * FROM users WHERE username IN ? -- ' OR '1'='1"
        val emptyList = emptyList<String>()

        val res = Statement.create(sql)
            .bind(0, emptyList)
            .render()

        // The comment and attempted injection should be preserved as-is
        assertThat(res).contains("username IN () -- ' OR '1'='1")
    }

    @Test
    fun `Nested collections with SQL injection attempts are properly escaped`() {
        val sql = "SELECT * FROM matrix WHERE row IN ? AND column IN ?"
        val maliciousRows = listOf(
            "1",
            "2' OR '1'='1",
            "3'; DROP TABLE matrix; --"
        )
        val maliciousColumns = listOf(
            "A",
            "B' UNION SELECT username, password FROM users; --",
            "C'); DELETE FROM matrix; --"
        )

        val res = Statement.create(sql)
            .bind(0, maliciousRows)
            .bind(1, maliciousColumns)
            .render()

        // Verify each element in both collections is properly escaped
        assertThat(res).all {
            contains("row IN ('1', '2'' OR ''1''=''1', '3''; DROP TABLE matrix; --')")
            contains("column IN ('A', 'B'' UNION SELECT username, password FROM users; --', 'C''); DELETE FROM matrix; --')")
            // Ensure the rendered SQL doesn't contain unescaped quotes
            doesNotContain("2' OR '1'='1")
            doesNotContain("3'; DROP TABLE matrix; --")
            doesNotContain("B' UNION SELECT")
            doesNotContain("C'); DELETE FROM")
        }
    }

    @Test
    fun `Collection with mixed types including SQL injection attempts`() {
        val sql = "SELECT * FROM mixed WHERE value IN ?"
        // This list contains different types, including strings with SQL injection attempts
        val mixedValues = listOf(
            1,
            "text' OR 1=1; --",
            true,
            null,
            "normal text"
        )

        val res = Statement.create(sql)
            .bind(0, mixedValues)
            .render()

        // Verify each element is properly handled according to its type
        assertThat(res).all {
            contains("value IN (1, 'text'' OR 1=1; --', true, null, 'normal text')")
            // Ensure the string with injection attempt is properly escaped
            doesNotContain("text' OR 1=1; --")
        }
    }
}