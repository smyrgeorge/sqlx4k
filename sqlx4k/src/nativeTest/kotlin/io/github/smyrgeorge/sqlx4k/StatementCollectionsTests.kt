package io.github.smyrgeorge.sqlx4k

import assertk.all
import assertk.assertThat
import assertk.assertions.contains
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
        class ProductEncoder : Statement.ValueEncoder<Product> {
            override fun encode(value: Product): Any {
                return value.id
            }
        }

        // Register the encoder
        val encoders = Statement
            .ValueEncoderRegistry()
            .register(Product::class, ProductEncoder())

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
}