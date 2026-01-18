package io.github.smyrgeorge.sqlx4k.processor.test

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.doesNotContain
import assertk.assertions.isEqualTo
import io.github.smyrgeorge.sqlx4k.processor.test.entities.Article
import io.github.smyrgeorge.sqlx4k.processor.test.entities.Customer
import io.github.smyrgeorge.sqlx4k.processor.test.entities.Order
import io.github.smyrgeorge.sqlx4k.processor.test.entities.Product
import io.github.smyrgeorge.sqlx4k.processor.test.entities.Tag
import io.github.smyrgeorge.sqlx4k.processor.test.entities.User
import io.github.smyrgeorge.sqlx4k.processor.test.generated.delete
import kotlinx.datetime.LocalDateTime
import kotlin.test.Test

/**
 * Tests for generated DELETE statements.
 */
class DeleteStatementTests {

    // User entity: Basic delete with Long @Id

    @Test
    fun `User delete uses id in WHERE clause`() {
        val user = User(id = 123, name = "Alice", email = "alice@example.com")
        val sql = user.delete().render()

        assertThat(sql).contains("where id = 123")
    }

    @Test
    fun `User delete targets correct table`() {
        val user = User(id = 1, name = "Test", email = "test@test.com")
        val sql = user.delete().render()

        assertThat(sql).contains("delete from users")
    }

    @Test
    fun `User delete does not have RETURNING clause`() {
        val user = User(id = 1, name = "Test", email = "test@test.com")
        val sql = user.delete().render()

        assertThat(sql).doesNotContain("returning")
    }

    @Test
    fun `User delete does not include other columns`() {
        val user = User(id = 1, name = "Should not appear", email = "should@not.appear")
        val sql = user.delete().render()

        assertThat(sql).doesNotContain("name")
        assertThat(sql).doesNotContain("email")
        assertThat(sql).doesNotContain("Should not appear")
    }

    @Test
    fun `User delete is a simple statement`() {
        val user = User(id = 42, name = "Test", email = "test@test.com")
        val sql = user.delete().render()

        assertThat(sql).isEqualTo("delete from users where id = 42;")
    }

    // Product entity: @Id with String type

    @Test
    fun `Product delete uses string id in WHERE clause`() {
        val product = Product(id = "uuid-123", name = "Widget", price = 9.99)
        val sql = product.delete().render()

        assertThat(sql).contains("where id = 'uuid-123'")
    }

    @Test
    fun `Product delete targets correct table`() {
        val product = Product(id = "abc", name = "Test", price = 1.0)
        val sql = product.delete().render()

        assertThat(sql).contains("delete from products")
    }

    @Test
    fun `Product delete is a simple statement`() {
        val product = Product(id = "p-001", name = "Test", price = 1.0)
        val sql = product.delete().render()

        assertThat(sql).isEqualTo("delete from products where id = 'p-001';")
    }

    // Article entity: Delete ignores timestamp columns

    @Test
    fun `Article delete does not include timestamp columns`() {
        val article = Article(
            id = 1,
            title = "Test Title",
            content = "Test content",
            createdAt = LocalDateTime(2024, 1, 1, 0, 0),
            updatedAt = LocalDateTime(2024, 1, 1, 0, 0)
        )
        val sql = article.delete().render()

        assertThat(sql).doesNotContain("created_at")
        assertThat(sql).doesNotContain("updated_at")
        assertThat(sql).doesNotContain("title")
        assertThat(sql).doesNotContain("content")
    }

    @Test
    fun `Article delete uses id in WHERE clause`() {
        val article = Article(
            id = 99,
            title = "Test",
            content = "Test",
            createdAt = LocalDateTime(2024, 1, 1, 0, 0),
            updatedAt = LocalDateTime(2024, 1, 1, 0, 0)
        )
        val sql = article.delete().render()

        assertThat(sql).contains("where id = 99")
    }

    @Test
    fun `Article delete targets correct table`() {
        val article = Article(
            id = 1,
            title = "Test",
            content = "Test",
            createdAt = LocalDateTime(2024, 1, 1, 0, 0),
            updatedAt = LocalDateTime(2024, 1, 1, 0, 0)
        )
        val sql = article.delete().render()

        assertThat(sql).contains("delete from articles")
    }

    // Order entity: Delete ignores version column

    @Test
    fun `Order delete does not include version column`() {
        val order = Order(id = 1, customerId = 100, totalAmount = 250.0, version = 5)
        val sql = order.delete().render()

        assertThat(sql).doesNotContain("version")
        assertThat(sql).doesNotContain("customer_id")
        assertThat(sql).doesNotContain("total_amount")
    }

    @Test
    fun `Order delete uses id in WHERE clause`() {
        val order = Order(id = 77, customerId = 100, totalAmount = 250.0, version = 1)
        val sql = order.delete().render()

        assertThat(sql).contains("where id = 77")
    }

    @Test
    fun `Order delete targets correct table`() {
        val order = Order(id = 1, customerId = 100, totalAmount = 250.0, version = 0)
        val sql = order.delete().render()

        assertThat(sql).contains("delete from orders")
    }

    // Customer entity: Tests that camelCase id is converted to snake_case

    @Test
    fun `Customer delete uses id in WHERE clause`() {
        val customer = Customer(
            id = 500,
            firstName = "John",
            lastName = "Doe",
            email = "john@example.com",
            phoneNumber = "555-1234",
            isActive = true
        )
        val sql = customer.delete().render()

        assertThat(sql).contains("where id = 500")
    }

    @Test
    fun `Customer delete does not include other columns`() {
        val customer = Customer(
            id = 1,
            firstName = "Should not",
            lastName = "appear",
            email = "hidden@example.com",
            phoneNumber = null,
            isActive = false
        )
        val sql = customer.delete().render()

        assertThat(sql).doesNotContain("first_name")
        assertThat(sql).doesNotContain("last_name")
        assertThat(sql).doesNotContain("email")
        assertThat(sql).doesNotContain("phone_number")
        assertThat(sql).doesNotContain("is_active")
    }

    @Test
    fun `Customer delete targets correct table`() {
        val customer = Customer(
            id = 1,
            firstName = "Test",
            lastName = "Test",
            email = "test@test.com",
            phoneNumber = null,
            isActive = true
        )
        val sql = customer.delete().render()

        assertThat(sql).contains("delete from customers")
    }

    // Tag entity: Simple entity with Int id

    @Test
    fun `Tag delete uses int id in WHERE clause`() {
        val tag = Tag(id = 42, name = "kotlin")
        val sql = tag.delete().render()

        assertThat(sql).contains("where id = 42")
    }

    @Test
    fun `Tag delete targets correct table`() {
        val tag = Tag(id = 1, name = "test")
        val sql = tag.delete().render()

        assertThat(sql).contains("delete from tags")
    }

    @Test
    fun `Tag delete is a simple statement`() {
        val tag = Tag(id = 7, name = "test")
        val sql = tag.delete().render()

        assertThat(sql).isEqualTo("delete from tags where id = 7;")
    }

    // Edge cases

    @Test
    fun `delete handles zero id value`() {
        val user = User(id = 0, name = "Test", email = "test@test.com")
        val sql = user.delete().render()

        assertThat(sql).contains("where id = 0")
    }

    @Test
    fun `delete handles negative id value`() {
        val user = User(id = -1, name = "Test", email = "test@test.com")
        val sql = user.delete().render()

        assertThat(sql).contains("where id = -1")
    }

    @Test
    fun `delete handles large id value`() {
        val user = User(id = Long.MAX_VALUE, name = "Test", email = "test@test.com")
        val sql = user.delete().render()

        assertThat(sql).contains("where id = ${Long.MAX_VALUE}")
    }

    @Test
    fun `delete handles string id with special characters`() {
        val product = Product(id = "id-with-'quote", name = "Test", price = 1.0)
        val sql = product.delete().render()

        // The SQL should escape single quotes
        assertThat(sql).contains("id-with-''quote")
    }

    @Test
    fun `delete handles empty string id`() {
        val product = Product(id = "", name = "Test", price = 1.0)
        val sql = product.delete().render()

        assertThat(sql).contains("where id = ''")
    }

    @Test
    fun `delete handles string id with spaces`() {
        val product = Product(id = "id with spaces", name = "Test", price = 1.0)
        val sql = product.delete().render()

        assertThat(sql).contains("where id = 'id with spaces'")
    }
}
