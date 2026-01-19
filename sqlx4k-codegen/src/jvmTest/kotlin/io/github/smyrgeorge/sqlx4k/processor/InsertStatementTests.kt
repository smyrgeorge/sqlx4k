package io.github.smyrgeorge.sqlx4k.processor

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.doesNotContain
import io.github.smyrgeorge.sqlx4k.processor.util.Article
import io.github.smyrgeorge.sqlx4k.processor.util.Comment
import io.github.smyrgeorge.sqlx4k.processor.util.Customer
import io.github.smyrgeorge.sqlx4k.processor.util.Order
import io.github.smyrgeorge.sqlx4k.processor.util.Product
import io.github.smyrgeorge.sqlx4k.processor.util.Tag
import io.github.smyrgeorge.sqlx4k.processor.util.User
import io.github.smyrgeorge.sqlx4k.processor.test.generated.insert
import kotlinx.datetime.LocalDateTime
import kotlin.test.Test

/**
 * Tests for generated INSERT statements.
 */
class InsertStatementTests {

    // User entity: @Id without insert=true (auto-generated ID)

    @Test
    fun `User insert excludes id column`() {
        val user = User(id = 0, name = "Alice", email = "alice@example.com")
        val sql = user.insert().render()

        assertThat(sql).doesNotContain("id,")
        assertThat(sql).doesNotContain(", id)")
        assertThat(sql).doesNotContain("(id,")
    }

    @Test
    fun `User insert includes name and email columns`() {
        val user = User(id = 0, name = "Alice", email = "alice@example.com")
        val sql = user.insert().render()

        assertThat(sql).contains("name")
        assertThat(sql).contains("email")
    }

    @Test
    fun `User insert has RETURNING clause with id`() {
        val user = User(id = 0, name = "Alice", email = "alice@example.com")
        val sql = user.insert().render()

        assertThat(sql).contains("returning id")
    }

    @Test
    fun `User insert renders values correctly`() {
        val user = User(id = 0, name = "Bob", email = "bob@test.com")
        val sql = user.insert().render()

        assertThat(sql).contains("'Bob'")
        assertThat(sql).contains("'bob@test.com'")
    }

    @Test
    fun `User insert targets correct table`() {
        val user = User(id = 0, name = "Test", email = "test@test.com")
        val sql = user.insert().render()

        assertThat(sql).contains("insert into users")
    }

    // Product entity: @Id(insert = true) (application-generated ID)

    @Test
    fun `Product insert includes id column`() {
        val product = Product(id = "uuid-123", name = "Widget", price = 9.99)
        val sql = product.insert().render()

        assertThat(sql).contains("(id,")
    }

    @Test
    fun `Product insert renders id value`() {
        val product = Product(id = "uuid-456", name = "Gadget", price = 19.99)
        val sql = product.insert().render()

        assertThat(sql).contains("'uuid-456'")
    }

    @Test
    fun `Product insert targets correct table`() {
        val product = Product(id = "abc", name = "Test", price = 1.0)
        val sql = product.insert().render()

        assertThat(sql).contains("insert into products")
    }

    // Article entity: @Column(insert = false, update = false) on timestamps

    @Test
    fun `Article insert excludes createdAt and updatedAt from INSERT columns`() {
        val article = Article(
            id = 0,
            title = "Test Title",
            content = "Test content",
            createdAt = LocalDateTime(2024, 1, 1, 0, 0),
            updatedAt = LocalDateTime(2024, 1, 1, 0, 0)
        )
        val sql = article.insert().render()

        // Check that timestamps are not in the INSERT column list (but they should be in RETURNING)
        assertThat(sql).contains("insert into articles(title, content)")
        // Verify they ARE in returning (this is the expected behavior)
        assertThat(sql).contains("returning id, created_at, updated_at")
    }

    @Test
    fun `Article insert includes title and content only`() {
        val article = Article(
            id = 0,
            title = "My Article",
            content = "Article body",
            createdAt = LocalDateTime(2024, 1, 1, 0, 0),
            updatedAt = LocalDateTime(2024, 1, 1, 0, 0)
        )
        val sql = article.insert().render()

        assertThat(sql).contains("(title, content)")
    }

    @Test
    fun `Article insert RETURNING clause includes id and timestamps`() {
        val article = Article(
            id = 0,
            title = "Test",
            content = "Test",
            createdAt = LocalDateTime(2024, 1, 1, 0, 0),
            updatedAt = LocalDateTime(2024, 1, 1, 0, 0)
        )
        val sql = article.insert().render()

        assertThat(sql).contains("returning id, created_at, updated_at")
    }

    @Test
    fun `Article insert targets correct table`() {
        val article = Article(
            id = 0,
            title = "Test",
            content = "Test",
            createdAt = LocalDateTime(2024, 1, 1, 0, 0),
            updatedAt = LocalDateTime(2024, 1, 1, 0, 0)
        )
        val sql = article.insert().render()

        assertThat(sql).contains("insert into articles")
    }

    // Order entity: @Column(insert = false, update = false) on version

    @Test
    fun `Order insert excludes version from INSERT columns`() {
        val order = Order(id = 0, customerId = 100, totalAmount = 250.0, version = 0)
        val sql = order.insert().render()

        // Check that version is not in the INSERT column list (but should be in RETURNING)
        assertThat(sql).contains("insert into orders(customer_id, total_amount)")
        // Verify version IS in returning (this is the expected behavior)
        assertThat(sql).contains("returning id, version")
    }

    @Test
    fun `Order insert includes customerId and totalAmount`() {
        val order = Order(id = 0, customerId = 100, totalAmount = 250.0, version = 0)
        val sql = order.insert().render()

        assertThat(sql).contains("customer_id")
        assertThat(sql).contains("total_amount")
    }

    @Test
    fun `Order insert RETURNING clause includes id and version`() {
        val order = Order(id = 0, customerId = 100, totalAmount = 250.0, version = 0)
        val sql = order.insert().render()

        assertThat(sql).contains("returning id, version")
    }

    // Comment entity: @Column(update = false) on createdAt (but insert = true by default)

    @Test
    fun `Comment insert includes createdAt column`() {
        val comment = Comment(
            id = 0,
            postId = 1,
            authorName = "John",
            content = "Great post!",
            createdAt = LocalDateTime(2024, 6, 15, 10, 30)
        )
        val sql = comment.insert().render()

        assertThat(sql).contains("created_at")
    }

    @Test
    fun `Comment insert renders all insertable columns`() {
        val comment = Comment(
            id = 0,
            postId = 42,
            authorName = "Jane",
            content = "Nice!",
            createdAt = LocalDateTime(2024, 6, 15, 10, 30)
        )
        val sql = comment.insert().render()

        assertThat(sql).contains("post_id")
        assertThat(sql).contains("author_name")
        assertThat(sql).contains("content")
        assertThat(sql).contains("created_at")
    }

    // Customer entity: Tests camelCase to snake_case conversion

    @Test
    fun `Customer insert converts camelCase to snake_case`() {
        val customer = Customer(
            id = 0,
            firstName = "John",
            lastName = "Doe",
            email = "john@example.com",
            phoneNumber = "555-1234",
            isActive = true
        )
        val sql = customer.insert().render()

        assertThat(sql).contains("first_name")
        assertThat(sql).contains("last_name")
        assertThat(sql).contains("phone_number")
        assertThat(sql).contains("is_active")

        // Should not contain camelCase versions
        assertThat(sql).doesNotContain("firstName")
        assertThat(sql).doesNotContain("lastName")
        assertThat(sql).doesNotContain("phoneNumber")
        assertThat(sql).doesNotContain("isActive")
    }

    @Test
    fun `Customer insert renders boolean as true or false`() {
        val activeCustomer = Customer(
            id = 0,
            firstName = "Active",
            lastName = "User",
            email = "active@test.com",
            phoneNumber = null,
            isActive = true
        )
        val sql = activeCustomer.insert().render()

        assertThat(sql).contains("true")
    }

    @Test
    fun `Customer insert renders null values`() {
        val customer = Customer(
            id = 0,
            firstName = "No",
            lastName = "Phone",
            email = "no@phone.com",
            phoneNumber = null,
            isActive = false
        )
        val sql = customer.insert().render()

        assertThat(sql).contains("null")
    }

    // Tag entity: Simple entity with Int id

    @Test
    fun `Tag insert excludes id column`() {
        val tag = Tag(id = 0, name = "kotlin")
        val sql = tag.insert().render()

        assertThat(sql).contains("insert into tags(name)")
    }

    @Test
    fun `Tag insert has single placeholder`() {
        val tag = Tag(id = 0, name = "java")
        val sql = tag.insert().render()

        assertThat(sql).contains("'java'")
    }

    @Test
    fun `Tag insert RETURNING clause includes id`() {
        val tag = Tag(id = 0, name = "test")
        val sql = tag.insert().render()

        assertThat(sql).contains("returning id")
    }

    // Edge cases

    @Test
    fun `insert handles special characters in strings`() {
        val user = User(id = 0, name = "O'Brien", email = "o'brien@test.com")
        val sql = user.insert().render()

        // The SQL should escape single quotes
        assertThat(sql).contains("O''Brien")
    }

    @Test
    fun `insert handles empty strings`() {
        val user = User(id = 0, name = "", email = "")
        val sql = user.insert().render()

        assertThat(sql).contains("''")
    }

    @Test
    fun `insert handles numeric values correctly`() {
        val product = Product(id = "p1", name = "Test", price = 123.456)
        val sql = product.insert().render()

        assertThat(sql).contains("123.456")
    }
}
