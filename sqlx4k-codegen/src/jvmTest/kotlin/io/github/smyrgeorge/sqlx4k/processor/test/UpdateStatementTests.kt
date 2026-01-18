package io.github.smyrgeorge.sqlx4k.processor.test

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.doesNotContain
import io.github.smyrgeorge.sqlx4k.processor.test.entities.Article
import io.github.smyrgeorge.sqlx4k.processor.test.entities.Comment
import io.github.smyrgeorge.sqlx4k.processor.test.entities.Customer
import io.github.smyrgeorge.sqlx4k.processor.test.entities.Order
import io.github.smyrgeorge.sqlx4k.processor.test.entities.Product
import io.github.smyrgeorge.sqlx4k.processor.test.entities.Tag
import io.github.smyrgeorge.sqlx4k.processor.test.entities.User
import io.github.smyrgeorge.sqlx4k.processor.test.generated.update
import kotlinx.datetime.LocalDateTime
import kotlin.test.Test

/**
 * Tests for generated UPDATE statements.
 */
class UpdateStatementTests {

    // User entity: Basic update with @Id

    @Test
    fun `User update excludes id from SET clause`() {
        val user = User(id = 123, name = "Alice", email = "alice@example.com")
        val sql = user.update().render()

        // id should be in WHERE, not in SET
        assertThat(sql).doesNotContain("set id =")
        assertThat(sql).doesNotContain("set name = 'Alice', id =")
    }

    @Test
    fun `User update includes id in WHERE clause`() {
        val user = User(id = 42, name = "Bob", email = "bob@test.com")
        val sql = user.update().render()

        assertThat(sql).contains("where id = 42")
    }

    @Test
    fun `User update includes name and email in SET clause`() {
        val user = User(id = 1, name = "Charlie", email = "charlie@test.com")
        val sql = user.update().render()

        assertThat(sql).contains("set name = 'Charlie', email = 'charlie@test.com'")
    }

    @Test
    fun `User update has RETURNING clause with id`() {
        val user = User(id = 1, name = "Test", email = "test@test.com")
        val sql = user.update().render()

        assertThat(sql).contains("returning id")
    }

    @Test
    fun `User update targets correct table`() {
        val user = User(id = 1, name = "Test", email = "test@test.com")
        val sql = user.update().render()

        assertThat(sql).contains("update users")
    }

    // Product entity: @Id(insert = true) behaves same for update

    @Test
    fun `Product update uses id in WHERE clause`() {
        val product = Product(id = "uuid-123", name = "Widget", price = 19.99)
        val sql = product.update().render()

        assertThat(sql).contains("where id = 'uuid-123'")
    }

    @Test
    fun `Product update includes name and price in SET clause`() {
        val product = Product(id = "uuid-456", name = "Gadget", price = 29.99)
        val sql = product.update().render()

        assertThat(sql).contains("set name = 'Gadget', price = 29.99")
    }

    @Test
    fun `Product update targets correct table`() {
        val product = Product(id = "abc", name = "Test", price = 1.0)
        val sql = product.update().render()

        assertThat(sql).contains("update products")
    }

    // Article entity: @Column(insert = false, update = false) on timestamps

    @Test
    fun `Article update excludes createdAt and updatedAt from SET clause`() {
        val article = Article(
            id = 1,
            title = "Updated Title",
            content = "Updated content",
            createdAt = LocalDateTime(2024, 1, 1, 0, 0),
            updatedAt = LocalDateTime(2024, 1, 1, 0, 0)
        )
        val sql = article.update().render()

        assertThat(sql).doesNotContain("created_at =")
        assertThat(sql).doesNotContain("updated_at =")
    }

    @Test
    fun `Article update includes title and content in SET clause`() {
        val article = Article(
            id = 1,
            title = "New Title",
            content = "New content",
            createdAt = LocalDateTime(2024, 1, 1, 0, 0),
            updatedAt = LocalDateTime(2024, 1, 1, 0, 0)
        )
        val sql = article.update().render()

        assertThat(sql).contains("set title = 'New Title', content = 'New content'")
    }

    @Test
    fun `Article update RETURNING clause includes id and timestamps`() {
        val article = Article(
            id = 1,
            title = "Test",
            content = "Test",
            createdAt = LocalDateTime(2024, 1, 1, 0, 0),
            updatedAt = LocalDateTime(2024, 1, 1, 0, 0)
        )
        val sql = article.update().render()

        assertThat(sql).contains("returning id, created_at, updated_at")
    }

    @Test
    fun `Article update targets correct table`() {
        val article = Article(
            id = 1,
            title = "Test",
            content = "Test",
            createdAt = LocalDateTime(2024, 1, 1, 0, 0),
            updatedAt = LocalDateTime(2024, 1, 1, 0, 0)
        )
        val sql = article.update().render()

        assertThat(sql).contains("update articles")
    }

    // Order entity: @Column(insert = false, update = false) on version

    @Test
    fun `Order update excludes version from SET clause`() {
        val order = Order(id = 1, customerId = 100, totalAmount = 300.0, version = 5)
        val sql = order.update().render()

        assertThat(sql).doesNotContain("version =")
    }

    @Test
    fun `Order update includes customerId and totalAmount in SET clause`() {
        val order = Order(id = 1, customerId = 200, totalAmount = 450.0, version = 1)
        val sql = order.update().render()

        assertThat(sql).contains("customer_id = 200")
        assertThat(sql).contains("total_amount = 450.0")
    }

    @Test
    fun `Order update RETURNING clause includes id and version`() {
        val order = Order(id = 1, customerId = 100, totalAmount = 250.0, version = 0)
        val sql = order.update().render()

        assertThat(sql).contains("returning id, version")
    }

    // Comment entity: @Column(update = false) on createdAt

    @Test
    fun `Comment update excludes createdAt from SET clause`() {
        val comment = Comment(
            id = 1,
            postId = 10,
            authorName = "John",
            content = "Updated comment",
            createdAt = LocalDateTime(2024, 6, 15, 10, 30)
        )
        val sql = comment.update().render()

        assertThat(sql).doesNotContain("created_at =")
    }

    @Test
    fun `Comment update includes postId, authorName, and content in SET clause`() {
        val comment = Comment(
            id = 1,
            postId = 20,
            authorName = "Jane",
            content = "New content",
            createdAt = LocalDateTime(2024, 6, 15, 10, 30)
        )
        val sql = comment.update().render()

        assertThat(sql).contains("post_id = 20")
        assertThat(sql).contains("author_name = 'Jane'")
        assertThat(sql).contains("content = 'New content'")
    }

    @Test
    fun `Comment update RETURNING clause includes id and createdAt`() {
        val comment = Comment(
            id = 1,
            postId = 1,
            authorName = "Test",
            content = "Test",
            createdAt = LocalDateTime(2024, 6, 15, 10, 30)
        )
        val sql = comment.update().render()

        assertThat(sql).contains("returning id, created_at")
    }

    // Customer entity: Tests camelCase to snake_case conversion

    @Test
    fun `Customer update converts camelCase to snake_case in SET clause`() {
        val customer = Customer(
            id = 1,
            firstName = "John",
            lastName = "Doe",
            email = "john@example.com",
            phoneNumber = "555-1234",
            isActive = true
        )
        val sql = customer.update().render()

        assertThat(sql).contains("first_name = 'John'")
        assertThat(sql).contains("last_name = 'Doe'")
        assertThat(sql).contains("phone_number = '555-1234'")
        assertThat(sql).contains("is_active = true")

        // Should not contain camelCase versions
        assertThat(sql).doesNotContain("firstName")
        assertThat(sql).doesNotContain("lastName")
        assertThat(sql).doesNotContain("phoneNumber")
        assertThat(sql).doesNotContain("isActive")
    }

    @Test
    fun `Customer update handles null phoneNumber`() {
        val customer = Customer(
            id = 1,
            firstName = "No",
            lastName = "Phone",
            email = "no@phone.com",
            phoneNumber = null,
            isActive = false
        )
        val sql = customer.update().render()

        assertThat(sql).contains("phone_number = null")
    }

    // Tag entity: Simple entity

    @Test
    fun `Tag update includes name in SET clause`() {
        val tag = Tag(id = 5, name = "updated-tag")
        val sql = tag.update().render()

        assertThat(sql).contains("set name = 'updated-tag'")
    }

    @Test
    fun `Tag update uses id in WHERE clause`() {
        val tag = Tag(id = 10, name = "test")
        val sql = tag.update().render()

        assertThat(sql).contains("where id = 10")
    }

    @Test
    fun `Tag update RETURNING clause includes id`() {
        val tag = Tag(id = 1, name = "test")
        val sql = tag.update().render()

        assertThat(sql).contains("returning id")
    }

    // Edge cases

    @Test
    fun `update handles special characters in strings`() {
        val user = User(id = 1, name = "O'Brien", email = "o'brien@test.com")
        val sql = user.update().render()

        // The SQL should escape single quotes
        assertThat(sql).contains("O''Brien")
    }

    @Test
    fun `update handles empty strings`() {
        val user = User(id = 1, name = "", email = "")
        val sql = user.update().render()

        assertThat(sql).contains("name = ''")
        assertThat(sql).contains("email = ''")
    }

    @Test
    fun `update handles numeric values correctly`() {
        val product = Product(id = "p1", name = "Test", price = 999.99)
        val sql = product.update().render()

        assertThat(sql).contains("999.99")
    }

    @Test
    fun `update handles zero id value`() {
        val user = User(id = 0, name = "Test", email = "test@test.com")
        val sql = user.update().render()

        assertThat(sql).contains("where id = 0")
    }

    @Test
    fun `update handles negative id value`() {
        val user = User(id = -1, name = "Test", email = "test@test.com")
        val sql = user.update().render()

        assertThat(sql).contains("where id = -1")
    }

    @Test
    fun `update handles large id value`() {
        val user = User(id = Long.MAX_VALUE, name = "Test", email = "test@test.com")
        val sql = user.update().render()

        assertThat(sql).contains("where id = ${Long.MAX_VALUE}")
    }
}
