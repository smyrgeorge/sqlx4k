package io.github.smyrgeorge.sqlx4k.processor

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.doesNotContain
import io.github.smyrgeorge.sqlx4k.processor.util.Article
import io.github.smyrgeorge.sqlx4k.processor.util.Customer
import io.github.smyrgeorge.sqlx4k.processor.util.Order
import io.github.smyrgeorge.sqlx4k.processor.util.Product
import io.github.smyrgeorge.sqlx4k.processor.util.Tag
import io.github.smyrgeorge.sqlx4k.processor.util.User
import io.github.smyrgeorge.sqlx4k.processor.test.generated.update
import kotlinx.datetime.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertFailsWith

/**
 * Tests for generated batch UPDATE statements.
 * Batch update is only supported for PostgreSQL dialect (uses FROM VALUES syntax).
 */
class BatchUpdateStatementTests {

    // User entity: Basic batch update with @Id

    @Test
    fun `User batch update uses FROM VALUES syntax`() {
        val users = listOf(
            User(id = 1, name = "Alice Updated", email = "alice@updated.com"),
            User(id = 2, name = "Bob Updated", email = "bob@updated.com")
        )
        val sql = users.update().render()

        assertThat(sql).contains("update users as t")
        assertThat(sql).contains("from (values")
        assertThat(sql).contains(") as v(")
    }

    @Test
    fun `User batch update excludes id from SET clause`() {
        val users = listOf(
            User(id = 1, name = "Alice", email = "alice@example.com"),
            User(id = 2, name = "Bob", email = "bob@example.com")
        )
        val sql = users.update().render()

        // id should not be in SET clause (but it IS in WHERE clause as t.id = v.id)
        assertThat(sql).doesNotContain("set id =")
        assertThat(sql).doesNotContain("id = v.id,") // id should not be updated in SET
    }

    @Test
    fun `User batch update includes id in WHERE clause`() {
        val users = listOf(
            User(id = 42, name = "Test1", email = "test1@test.com"),
            User(id = 43, name = "Test2", email = "test2@test.com")
        )
        val sql = users.update().render()

        assertThat(sql).contains("where t.id = v.id")
    }

    @Test
    fun `User batch update includes name and email in SET clause`() {
        val users = listOf(
            User(id = 1, name = "Charlie", email = "charlie@test.com"),
            User(id = 2, name = "Diana", email = "diana@test.com")
        )
        val sql = users.update().render()

        assertThat(sql).contains("name = v.name")
        assertThat(sql).contains("email = v.email")
    }

    @Test
    fun `User batch update has RETURNING clause with id`() {
        val users = listOf(
            User(id = 1, name = "Test", email = "test@test.com")
        )
        val sql = users.update().render()

        assertThat(sql).contains("returning t.id")
    }

    @Test
    fun `User batch update targets correct table`() {
        val users = listOf(
            User(id = 1, name = "Test", email = "test@test.com")
        )
        val sql = users.update().render()

        assertThat(sql).contains("update users as t")
    }

    @Test
    fun `User batch update renders multiple values`() {
        val users = listOf(
            User(id = 1, name = "Alice", email = "alice@example.com"),
            User(id = 2, name = "Bob", email = "bob@example.com"),
            User(id = 3, name = "Charlie", email = "charlie@example.com")
        )
        val sql = users.update().render()

        assertThat(sql).contains("1")
        assertThat(sql).contains("2")
        assertThat(sql).contains("3")
        assertThat(sql).contains("'Alice'")
        assertThat(sql).contains("'Bob'")
        assertThat(sql).contains("'Charlie'")
    }

    @Test
    fun `User batch update with single item works`() {
        val users = listOf(
            User(id = 99, name = "Single", email = "single@test.com")
        )
        val sql = users.update().render()

        assertThat(sql).contains("99")
        assertThat(sql).contains("'Single'")
    }

    @Test
    fun `User batch update fails on empty collection`() {
        val users = emptyList<User>()

        assertFailsWith<IllegalArgumentException> {
            users.update()
        }
    }

    // Product entity: String @Id

    @Test
    fun `Product batch update uses id in WHERE clause`() {
        val products = listOf(
            Product(id = "uuid-1", name = "Widget", price = 19.99),
            Product(id = "uuid-2", name = "Gadget", price = 29.99)
        )
        val sql = products.update().render()

        assertThat(sql).contains("where t.id = v.id")
    }

    @Test
    fun `Product batch update includes name and price in SET clause`() {
        val products = listOf(
            Product(id = "uuid-1", name = "Updated Widget", price = 24.99),
            Product(id = "uuid-2", name = "Updated Gadget", price = 34.99)
        )
        val sql = products.update().render()

        assertThat(sql).contains("name = v.name")
        assertThat(sql).contains("price = v.price")
    }

    @Test
    fun `Product batch update renders string ids correctly`() {
        val products = listOf(
            Product(id = "uuid-abc", name = "Test1", price = 1.0),
            Product(id = "uuid-def", name = "Test2", price = 2.0)
        )
        val sql = products.update().render()

        assertThat(sql).contains("'uuid-abc'")
        assertThat(sql).contains("'uuid-def'")
    }

    @Test
    fun `Product batch update targets correct table`() {
        val products = listOf(
            Product(id = "abc", name = "Test", price = 1.0)
        )
        val sql = products.update().render()

        assertThat(sql).contains("update products as t")
    }

    // Article entity: @Column(insert = false, update = false) on timestamps

    @Test
    fun `Article batch update excludes createdAt and updatedAt from SET clause`() {
        val articles = listOf(
            Article(
                id = 1,
                title = "Updated Title 1",
                content = "Updated content 1",
                createdAt = LocalDateTime(2024, 1, 1, 0, 0),
                updatedAt = LocalDateTime(2024, 1, 1, 0, 0)
            ),
            Article(
                id = 2,
                title = "Updated Title 2",
                content = "Updated content 2",
                createdAt = LocalDateTime(2024, 1, 1, 0, 0),
                updatedAt = LocalDateTime(2024, 1, 1, 0, 0)
            )
        )
        val sql = articles.update().render()

        assertThat(sql).doesNotContain("created_at = v.created_at")
        assertThat(sql).doesNotContain("updated_at = v.updated_at")
    }

    @Test
    fun `Article batch update includes title and content in SET clause`() {
        val articles = listOf(
            Article(
                id = 1,
                title = "New Title",
                content = "New content",
                createdAt = LocalDateTime(2024, 1, 1, 0, 0),
                updatedAt = LocalDateTime(2024, 1, 1, 0, 0)
            )
        )
        val sql = articles.update().render()

        assertThat(sql).contains("title = v.title")
        assertThat(sql).contains("content = v.content")
    }

    @Test
    fun `Article batch update RETURNING clause includes id and timestamps`() {
        val articles = listOf(
            Article(
                id = 1,
                title = "Test",
                content = "Test",
                createdAt = LocalDateTime(2024, 1, 1, 0, 0),
                updatedAt = LocalDateTime(2024, 1, 1, 0, 0)
            )
        )
        val sql = articles.update().render()

        assertThat(sql).contains("returning t.id, t.created_at, t.updated_at")
    }

    // Order entity: @Column(insert = false, update = false) on version

    @Test
    fun `Order batch update excludes version from SET clause`() {
        val orders = listOf(
            Order(id = 1, customerId = 100, totalAmount = 300.0, version = 5),
            Order(id = 2, customerId = 200, totalAmount = 400.0, version = 3)
        )
        val sql = orders.update().render()

        assertThat(sql).doesNotContain("version = v.version")
    }

    @Test
    fun `Order batch update includes customerId and totalAmount in SET clause`() {
        val orders = listOf(
            Order(id = 1, customerId = 150, totalAmount = 350.0, version = 1),
            Order(id = 2, customerId = 250, totalAmount = 450.0, version = 2)
        )
        val sql = orders.update().render()

        assertThat(sql).contains("customer_id = v.customer_id")
        assertThat(sql).contains("total_amount = v.total_amount")
    }

    @Test
    fun `Order batch update RETURNING clause includes id and version`() {
        val orders = listOf(
            Order(id = 1, customerId = 100, totalAmount = 250.0, version = 0)
        )
        val sql = orders.update().render()

        assertThat(sql).contains("returning t.id, t.version")
    }

    // Customer entity: Tests camelCase to snake_case conversion

    @Test
    fun `Customer batch update converts camelCase to snake_case in SET clause`() {
        val customers = listOf(
            Customer(
                id = 1,
                firstName = "John",
                lastName = "Doe",
                email = "john@example.com",
                phoneNumber = "555-1234",
                isActive = true
            ),
            Customer(
                id = 2,
                firstName = "Jane",
                lastName = "Smith",
                email = "jane@example.com",
                phoneNumber = "555-5678",
                isActive = false
            )
        )
        val sql = customers.update().render()

        assertThat(sql).contains("first_name = v.first_name")
        assertThat(sql).contains("last_name = v.last_name")
        assertThat(sql).contains("phone_number = v.phone_number")
        assertThat(sql).contains("is_active = v.is_active")

        assertThat(sql).doesNotContain("firstName")
        assertThat(sql).doesNotContain("lastName")
        assertThat(sql).doesNotContain("phoneNumber")
        assertThat(sql).doesNotContain("isActive")
    }

    @Test
    fun `Customer batch update handles null phoneNumber`() {
        val customers = listOf(
            Customer(
                id = 1,
                firstName = "No",
                lastName = "Phone",
                email = "no@phone.com",
                phoneNumber = null,
                isActive = false
            )
        )
        val sql = customers.update().render()

        assertThat(sql).contains("null")
    }

    // Tag entity: Simple entity

    @Test
    fun `Tag batch update includes name in SET clause`() {
        val tags = listOf(
            Tag(id = 1, name = "updated-tag-1"),
            Tag(id = 2, name = "updated-tag-2")
        )
        val sql = tags.update().render()

        assertThat(sql).contains("name = v.name")
    }

    @Test
    fun `Tag batch update uses id in WHERE clause`() {
        val tags = listOf(
            Tag(id = 10, name = "test1"),
            Tag(id = 20, name = "test2")
        )
        val sql = tags.update().render()

        assertThat(sql).contains("where t.id = v.id")
    }

    @Test
    fun `Tag batch update renders multiple values`() {
        val tags = listOf(
            Tag(id = 1, name = "kotlin"),
            Tag(id = 2, name = "java"),
            Tag(id = 3, name = "scala")
        )
        val sql = tags.update().render()

        assertThat(sql).contains("'kotlin'")
        assertThat(sql).contains("'java'")
        assertThat(sql).contains("'scala'")
    }

    @Test
    fun `Tag batch update RETURNING clause includes id`() {
        val tags = listOf(
            Tag(id = 1, name = "test")
        )
        val sql = tags.update().render()

        assertThat(sql).contains("returning t.id")
    }

    // Edge cases

    @Test
    fun `batch update handles special characters in strings`() {
        val users = listOf(
            User(id = 1, name = "O'Brien", email = "o'brien@test.com"),
            User(id = 2, name = "O'Connor", email = "o'connor@test.com")
        )
        val sql = users.update().render()

        assertThat(sql).contains("O''Brien")
        assertThat(sql).contains("O''Connor")
    }

    @Test
    fun `batch update handles empty strings`() {
        val users = listOf(
            User(id = 1, name = "", email = ""),
            User(id = 2, name = "Valid", email = "valid@test.com")
        )
        val sql = users.update().render()

        assertThat(sql).contains("''")
    }

    @Test
    fun `batch update handles numeric values correctly`() {
        val products = listOf(
            Product(id = "p1", name = "Test1", price = 999.99),
            Product(id = "p2", name = "Test2", price = 888.88)
        )
        val sql = products.update().render()

        assertThat(sql).contains("999.99")
        assertThat(sql).contains("888.88")
    }

    @Test
    fun `batch update handles zero id value`() {
        val users = listOf(
            User(id = 0, name = "Test", email = "test@test.com")
        )
        val sql = users.update().render()

        assertThat(sql).contains("0")
    }

    @Test
    fun `batch update handles negative id value`() {
        val users = listOf(
            User(id = -1, name = "Test1", email = "test1@test.com"),
            User(id = -2, name = "Test2", email = "test2@test.com")
        )
        val sql = users.update().render()

        assertThat(sql).contains("-1")
        assertThat(sql).contains("-2")
    }

    @Test
    fun `batch update works with Iterable interface`() {
        val usersSet: Set<User> = setOf(
            User(id = 1, name = "SetUser1", email = "set1@test.com"),
            User(id = 2, name = "SetUser2", email = "set2@test.com")
        )
        val sql = usersSet.update().render()

        assertThat(sql).contains("update users as t")
    }
}
