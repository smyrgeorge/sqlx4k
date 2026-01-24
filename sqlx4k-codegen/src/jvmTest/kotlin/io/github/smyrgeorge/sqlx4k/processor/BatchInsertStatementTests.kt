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
import io.github.smyrgeorge.sqlx4k.processor.test.generated.insert
import kotlinx.datetime.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertFailsWith

/**
 * Tests for generated batch INSERT statements.
 * Batch insert is only supported for PostgreSQL and SQLite dialects.
 */
class BatchInsertStatementTests {

    // User entity: @Id without insert=true (auto-generated ID)

    @Test
    fun `User batch insert excludes id column`() {
        val users = listOf(
            User(id = 0, name = "Alice", email = "alice@example.com"),
            User(id = 0, name = "Bob", email = "bob@example.com")
        )
        val sql = users.insert().render()

        assertThat(sql).doesNotContain("id,")
        assertThat(sql).doesNotContain(", id)")
        assertThat(sql).doesNotContain("(id,")
    }

    @Test
    fun `User batch insert includes name and email columns`() {
        val users = listOf(
            User(id = 0, name = "Alice", email = "alice@example.com"),
            User(id = 0, name = "Bob", email = "bob@example.com")
        )
        val sql = users.insert().render()

        assertThat(sql).contains("name")
        assertThat(sql).contains("email")
    }

    @Test
    fun `User batch insert has RETURNING clause with id`() {
        val users = listOf(
            User(id = 0, name = "Alice", email = "alice@example.com"),
            User(id = 0, name = "Bob", email = "bob@example.com")
        )
        val sql = users.insert().render()

        assertThat(sql).contains("returning id")
    }

    @Test
    fun `User batch insert renders multiple value rows`() {
        val users = listOf(
            User(id = 0, name = "Alice", email = "alice@example.com"),
            User(id = 0, name = "Bob", email = "bob@example.com"),
            User(id = 0, name = "Charlie", email = "charlie@example.com")
        )
        val sql = users.insert().render()

        assertThat(sql).contains("'Alice'")
        assertThat(sql).contains("'Bob'")
        assertThat(sql).contains("'Charlie'")
        assertThat(sql).contains("'alice@example.com'")
        assertThat(sql).contains("'bob@example.com'")
        assertThat(sql).contains("'charlie@example.com'")
    }

    @Test
    fun `User batch insert targets correct table`() {
        val users = listOf(
            User(id = 0, name = "Test", email = "test@test.com")
        )
        val sql = users.insert().render()

        assertThat(sql).contains("insert into users")
    }

    @Test
    fun `User batch insert with single item works`() {
        val users = listOf(
            User(id = 0, name = "Single", email = "single@test.com")
        )
        val sql = users.insert().render()

        assertThat(sql).contains("'Single'")
        assertThat(sql).contains("'single@test.com'")
    }

    @Test
    fun `User batch insert fails on empty collection`() {
        val users = emptyList<User>()

        assertFailsWith<IllegalArgumentException> {
            users.insert()
        }
    }

    // Product entity: @Id(insert = true) (application-generated ID)

    @Test
    fun `Product batch insert includes id column`() {
        val products = listOf(
            Product(id = "uuid-1", name = "Widget", price = 9.99),
            Product(id = "uuid-2", name = "Gadget", price = 19.99)
        )
        val sql = products.insert().render()

        assertThat(sql).contains("(id,")
    }

    @Test
    fun `Product batch insert renders multiple id values`() {
        val products = listOf(
            Product(id = "uuid-1", name = "Widget", price = 9.99),
            Product(id = "uuid-2", name = "Gadget", price = 19.99)
        )
        val sql = products.insert().render()

        assertThat(sql).contains("'uuid-1'")
        assertThat(sql).contains("'uuid-2'")
    }

    @Test
    fun `Product batch insert targets correct table`() {
        val products = listOf(
            Product(id = "abc", name = "Test", price = 1.0)
        )
        val sql = products.insert().render()

        assertThat(sql).contains("insert into products")
    }

    // Article entity: @Column(insert = false, update = false) on timestamps

    @Test
    fun `Article batch insert excludes createdAt and updatedAt from INSERT columns`() {
        val articles = listOf(
            Article(
                id = 0,
                title = "Title 1",
                content = "Content 1",
                createdAt = LocalDateTime(2024, 1, 1, 0, 0),
                updatedAt = LocalDateTime(2024, 1, 1, 0, 0)
            ),
            Article(
                id = 0,
                title = "Title 2",
                content = "Content 2",
                createdAt = LocalDateTime(2024, 1, 1, 0, 0),
                updatedAt = LocalDateTime(2024, 1, 1, 0, 0)
            )
        )
        val sql = articles.insert().render()

        assertThat(sql).contains("insert into articles(title, content)")
        assertThat(sql).contains("returning id, created_at, updated_at")
    }

    @Test
    fun `Article batch insert renders multiple articles`() {
        val articles = listOf(
            Article(
                id = 0,
                title = "First Article",
                content = "First content",
                createdAt = LocalDateTime(2024, 1, 1, 0, 0),
                updatedAt = LocalDateTime(2024, 1, 1, 0, 0)
            ),
            Article(
                id = 0,
                title = "Second Article",
                content = "Second content",
                createdAt = LocalDateTime(2024, 1, 1, 0, 0),
                updatedAt = LocalDateTime(2024, 1, 1, 0, 0)
            )
        )
        val sql = articles.insert().render()

        assertThat(sql).contains("'First Article'")
        assertThat(sql).contains("'Second Article'")
    }

    // Order entity: @Column(insert = false, update = false) on version

    @Test
    fun `Order batch insert excludes version from INSERT columns`() {
        val orders = listOf(
            Order(id = 0, customerId = 100, totalAmount = 250.0, version = 0),
            Order(id = 0, customerId = 200, totalAmount = 350.0, version = 0)
        )
        val sql = orders.insert().render()

        assertThat(sql).contains("insert into orders(customer_id, total_amount)")
        assertThat(sql).contains("returning id, version")
    }

    @Test
    fun `Order batch insert renders multiple orders`() {
        val orders = listOf(
            Order(id = 0, customerId = 100, totalAmount = 250.0, version = 0),
            Order(id = 0, customerId = 200, totalAmount = 350.0, version = 0)
        )
        val sql = orders.insert().render()

        assertThat(sql).contains("100")
        assertThat(sql).contains("200")
        assertThat(sql).contains("250.0")
        assertThat(sql).contains("350.0")
    }

    // Customer entity: Tests camelCase to snake_case conversion

    @Test
    fun `Customer batch insert converts camelCase to snake_case`() {
        val customers = listOf(
            Customer(
                id = 0,
                firstName = "John",
                lastName = "Doe",
                email = "john@example.com",
                phoneNumber = "555-1234",
                isActive = true
            ),
            Customer(
                id = 0,
                firstName = "Jane",
                lastName = "Smith",
                email = "jane@example.com",
                phoneNumber = "555-5678",
                isActive = false
            )
        )
        val sql = customers.insert().render()

        assertThat(sql).contains("first_name")
        assertThat(sql).contains("last_name")
        assertThat(sql).contains("phone_number")
        assertThat(sql).contains("is_active")

        assertThat(sql).doesNotContain("firstName")
        assertThat(sql).doesNotContain("lastName")
        assertThat(sql).doesNotContain("phoneNumber")
        assertThat(sql).doesNotContain("isActive")
    }

    @Test
    fun `Customer batch insert renders null values`() {
        val customers = listOf(
            Customer(
                id = 0,
                firstName = "No",
                lastName = "Phone",
                email = "no@phone.com",
                phoneNumber = null,
                isActive = false
            )
        )
        val sql = customers.insert().render()

        assertThat(sql).contains("null")
    }

    // Tag entity: Simple entity with Int id

    @Test
    fun `Tag batch insert excludes id column`() {
        val tags = listOf(
            Tag(id = 0, name = "kotlin"),
            Tag(id = 0, name = "java"),
            Tag(id = 0, name = "scala")
        )
        val sql = tags.insert().render()

        assertThat(sql).contains("insert into tags(name)")
    }

    @Test
    fun `Tag batch insert renders multiple values`() {
        val tags = listOf(
            Tag(id = 0, name = "kotlin"),
            Tag(id = 0, name = "java"),
            Tag(id = 0, name = "scala")
        )
        val sql = tags.insert().render()

        assertThat(sql).contains("'kotlin'")
        assertThat(sql).contains("'java'")
        assertThat(sql).contains("'scala'")
    }

    @Test
    fun `Tag batch insert RETURNING clause includes id`() {
        val tags = listOf(
            Tag(id = 0, name = "test1"),
            Tag(id = 0, name = "test2")
        )
        val sql = tags.insert().render()

        assertThat(sql).contains("returning id")
    }

    // Edge cases

    @Test
    fun `batch insert handles special characters in strings`() {
        val users = listOf(
            User(id = 0, name = "O'Brien", email = "o'brien@test.com"),
            User(id = 0, name = "O'Connor", email = "o'connor@test.com")
        )
        val sql = users.insert().render()

        assertThat(sql).contains("O''Brien")
        assertThat(sql).contains("O''Connor")
    }

    @Test
    fun `batch insert handles empty strings`() {
        val users = listOf(
            User(id = 0, name = "", email = ""),
            User(id = 0, name = "Valid", email = "valid@test.com")
        )
        val sql = users.insert().render()

        assertThat(sql).contains("''")
    }

    @Test
    fun `batch insert handles numeric values correctly`() {
        val products = listOf(
            Product(id = "p1", name = "Test1", price = 123.456),
            Product(id = "p2", name = "Test2", price = 789.012)
        )
        val sql = products.insert().render()

        assertThat(sql).contains("123.456")
        assertThat(sql).contains("789.012")
    }

    @Test
    fun `batch insert works with Iterable interface`() {
        val usersSet: Set<User> = setOf(
            User(id = 0, name = "SetUser1", email = "set1@test.com"),
            User(id = 0, name = "SetUser2", email = "set2@test.com")
        )
        val sql = usersSet.insert().render()

        assertThat(sql).contains("insert into users")
    }
}
