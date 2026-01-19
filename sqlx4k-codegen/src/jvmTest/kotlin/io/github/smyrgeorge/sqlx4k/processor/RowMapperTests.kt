package io.github.smyrgeorge.sqlx4k.processor

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import io.github.smyrgeorge.sqlx4k.ResultSet
import io.github.smyrgeorge.sqlx4k.ValueEncoderRegistry
import io.github.smyrgeorge.sqlx4k.processor.util.Article
import io.github.smyrgeorge.sqlx4k.processor.util.Comment
import io.github.smyrgeorge.sqlx4k.processor.util.Customer
import io.github.smyrgeorge.sqlx4k.processor.util.Order
import io.github.smyrgeorge.sqlx4k.processor.util.Product
import io.github.smyrgeorge.sqlx4k.processor.util.Tag
import io.github.smyrgeorge.sqlx4k.processor.util.User
import io.github.smyrgeorge.sqlx4k.processor.test.generated.ArticleAutoRowMapper
import io.github.smyrgeorge.sqlx4k.processor.test.generated.CommentAutoRowMapper
import io.github.smyrgeorge.sqlx4k.processor.test.generated.CustomerAutoRowMapper
import io.github.smyrgeorge.sqlx4k.processor.test.generated.OrderAutoRowMapper
import io.github.smyrgeorge.sqlx4k.processor.test.generated.ProductAutoRowMapper
import io.github.smyrgeorge.sqlx4k.processor.test.generated.TagAutoRowMapper
import io.github.smyrgeorge.sqlx4k.processor.test.generated.UserAutoRowMapper
import kotlinx.datetime.LocalDateTime
import kotlin.test.Test

/**
 * Tests for generated RowMapper implementations.
 */
class RowMapperTests {

    private val emptyRegistry = ValueEncoderRegistry.EMPTY

    /**
     * Helper function to create a ResultSet.Row.Column.
     */
    private fun column(ordinal: Int, name: String, type: String, value: String?): ResultSet.Row.Column =
        ResultSet.Row.Column(ordinal, name, type, value)

    /**
     * Helper function to create a ResultSet.Row from columns.
     */
    private fun row(vararg columns: ResultSet.Row.Column): ResultSet.Row =
        ResultSet.Row(columns.toList())

    // User entity: Basic mapping with Long, String, String

    @Test
    fun `UserAutoRowMapper maps all columns correctly`() {
        val testRow = row(
            column(0, "id", "INT8", "123"),
            column(1, "name", "TEXT", "Alice"),
            column(2, "email", "TEXT", "alice@example.com")
        )

        val result = UserAutoRowMapper.map(testRow, emptyRegistry)

        assertThat(result.id).isEqualTo(123L)
        assertThat(result.name).isEqualTo("Alice")
        assertThat(result.email).isEqualTo("alice@example.com")
    }

    @Test
    fun `UserAutoRowMapper maps zero id correctly`() {
        val testRow = row(
            column(0, "id", "INT8", "0"),
            column(1, "name", "TEXT", "Test"),
            column(2, "email", "TEXT", "test@test.com")
        )

        val result = UserAutoRowMapper.map(testRow, emptyRegistry)

        assertThat(result.id).isEqualTo(0L)
    }

    @Test
    fun `UserAutoRowMapper maps negative id correctly`() {
        val testRow = row(
            column(0, "id", "INT8", "-1"),
            column(1, "name", "TEXT", "Test"),
            column(2, "email", "TEXT", "test@test.com")
        )

        val result = UserAutoRowMapper.map(testRow, emptyRegistry)

        assertThat(result.id).isEqualTo(-1L)
    }

    @Test
    fun `UserAutoRowMapper maps large id correctly`() {
        val testRow = row(
            column(0, "id", "INT8", "${Long.MAX_VALUE}"),
            column(1, "name", "TEXT", "Test"),
            column(2, "email", "TEXT", "test@test.com")
        )

        val result = UserAutoRowMapper.map(testRow, emptyRegistry)

        assertThat(result.id).isEqualTo(Long.MAX_VALUE)
    }

    @Test
    fun `UserAutoRowMapper maps empty strings correctly`() {
        val testRow = row(
            column(0, "id", "INT8", "1"),
            column(1, "name", "TEXT", ""),
            column(2, "email", "TEXT", "")
        )

        val result = UserAutoRowMapper.map(testRow, emptyRegistry)

        assertThat(result.name).isEqualTo("")
        assertThat(result.email).isEqualTo("")
    }

    @Test
    fun `UserAutoRowMapper maps special characters correctly`() {
        val testRow = row(
            column(0, "id", "INT8", "1"),
            column(1, "name", "TEXT", "O'Brien"),
            column(2, "email", "TEXT", "o'brien@test.com")
        )

        val result = UserAutoRowMapper.map(testRow, emptyRegistry)

        assertThat(result.name).isEqualTo("O'Brien")
        assertThat(result.email).isEqualTo("o'brien@test.com")
    }

    @Test
    fun `UserAutoRowMapper returns expected User type`() {
        val testRow = row(
            column(0, "id", "INT8", "1"),
            column(1, "name", "TEXT", "Test"),
            column(2, "email", "TEXT", "test@test.com")
        )

        val result = UserAutoRowMapper.map(testRow, emptyRegistry)

        assertThat(result).isEqualTo(User(id = 1, name = "Test", email = "test@test.com"))
    }

    // Product entity: String id and Double price

    @Test
    fun `ProductAutoRowMapper maps all columns correctly`() {
        val testRow = row(
            column(0, "id", "TEXT", "uuid-123"),
            column(1, "name", "TEXT", "Widget"),
            column(2, "price", "FLOAT8", "9.99")
        )

        val result = ProductAutoRowMapper.map(testRow, emptyRegistry)

        assertThat(result.id).isEqualTo("uuid-123")
        assertThat(result.name).isEqualTo("Widget")
        assertThat(result.price).isEqualTo(9.99)
    }

    @Test
    fun `ProductAutoRowMapper maps zero price correctly`() {
        val testRow = row(
            column(0, "id", "TEXT", "p1"),
            column(1, "name", "TEXT", "Free Item"),
            column(2, "price", "FLOAT8", "0.0")
        )

        val result = ProductAutoRowMapper.map(testRow, emptyRegistry)

        assertThat(result.price).isEqualTo(0.0)
    }

    @Test
    fun `ProductAutoRowMapper maps large price correctly`() {
        val testRow = row(
            column(0, "id", "TEXT", "p1"),
            column(1, "name", "TEXT", "Expensive"),
            column(2, "price", "FLOAT8", "999999.99")
        )

        val result = ProductAutoRowMapper.map(testRow, emptyRegistry)

        assertThat(result.price).isEqualTo(999999.99)
    }

    @Test
    fun `ProductAutoRowMapper returns expected Product type`() {
        val testRow = row(
            column(0, "id", "TEXT", "abc"),
            column(1, "name", "TEXT", "Test"),
            column(2, "price", "FLOAT8", "1.5")
        )

        val result = ProductAutoRowMapper.map(testRow, emptyRegistry)

        assertThat(result).isEqualTo(Product(id = "abc", name = "Test", price = 1.5))
    }

    // Article entity: LocalDateTime columns

    @Test
    fun `ArticleAutoRowMapper maps all columns correctly`() {
        val testRow = row(
            column(0, "id", "INT8", "1"),
            column(1, "title", "TEXT", "My Article"),
            column(2, "content", "TEXT", "Article body text"),
            column(3, "created_at", "TIMESTAMP", "2024-06-15 10:30:00"),
            column(4, "updated_at", "TIMESTAMP", "2024-06-15 12:45:30")
        )

        val result = ArticleAutoRowMapper.map(testRow, emptyRegistry)

        assertThat(result.id).isEqualTo(1L)
        assertThat(result.title).isEqualTo("My Article")
        assertThat(result.content).isEqualTo("Article body text")
        assertThat(result.createdAt).isEqualTo(LocalDateTime(2024, 6, 15, 10, 30, 0))
        assertThat(result.updatedAt).isEqualTo(LocalDateTime(2024, 6, 15, 12, 45, 30))
    }

    @Test
    fun `ArticleAutoRowMapper maps timestamps with microseconds`() {
        val testRow = row(
            column(0, "id", "INT8", "1"),
            column(1, "title", "TEXT", "Test"),
            column(2, "content", "TEXT", "Content"),
            column(3, "created_at", "TIMESTAMP", "2024-01-01 00:00:00.123456"),
            column(4, "updated_at", "TIMESTAMP", "2024-12-31 23:59:59.999999")
        )

        val result = ArticleAutoRowMapper.map(testRow, emptyRegistry)

        assertThat(result.createdAt).isEqualTo(LocalDateTime(2024, 1, 1, 0, 0, 0, 123456000))
        assertThat(result.updatedAt).isEqualTo(LocalDateTime(2024, 12, 31, 23, 59, 59, 999999000))
    }

    @Test
    fun `ArticleAutoRowMapper returns expected Article type`() {
        val testRow = row(
            column(0, "id", "INT8", "42"),
            column(1, "title", "TEXT", "Title"),
            column(2, "content", "TEXT", "Body"),
            column(3, "created_at", "TIMESTAMP", "2024-01-01 00:00:00"),
            column(4, "updated_at", "TIMESTAMP", "2024-01-02 00:00:00")
        )

        val result = ArticleAutoRowMapper.map(testRow, emptyRegistry)

        assertThat(result).isEqualTo(
            Article(
                id = 42,
                title = "Title",
                content = "Body",
                createdAt = LocalDateTime(2024, 1, 1, 0, 0, 0),
                updatedAt = LocalDateTime(2024, 1, 2, 0, 0, 0)
            )
        )
    }

    // Order entity: Int version column

    @Test
    fun `OrderAutoRowMapper maps all columns correctly`() {
        val testRow = row(
            column(0, "id", "INT8", "100"),
            column(1, "customer_id", "INT8", "42"),
            column(2, "total_amount", "FLOAT8", "250.50"),
            column(3, "version", "INT4", "5")
        )

        val result = OrderAutoRowMapper.map(testRow, emptyRegistry)

        assertThat(result.id).isEqualTo(100L)
        assertThat(result.customerId).isEqualTo(42L)
        assertThat(result.totalAmount).isEqualTo(250.50)
        assertThat(result.version).isEqualTo(5)
    }

    @Test
    fun `OrderAutoRowMapper maps zero version correctly`() {
        val testRow = row(
            column(0, "id", "INT8", "1"),
            column(1, "customer_id", "INT8", "1"),
            column(2, "total_amount", "FLOAT8", "10.0"),
            column(3, "version", "INT4", "0")
        )

        val result = OrderAutoRowMapper.map(testRow, emptyRegistry)

        assertThat(result.version).isEqualTo(0)
    }

    @Test
    fun `OrderAutoRowMapper returns expected Order type`() {
        val testRow = row(
            column(0, "id", "INT8", "1"),
            column(1, "customer_id", "INT8", "2"),
            column(2, "total_amount", "FLOAT8", "100.0"),
            column(3, "version", "INT4", "1")
        )

        val result = OrderAutoRowMapper.map(testRow, emptyRegistry)

        assertThat(result).isEqualTo(Order(id = 1, customerId = 2, totalAmount = 100.0, version = 1))
    }

    // Comment entity: Tests snake_case column mapping

    @Test
    fun `CommentAutoRowMapper maps snake_case columns correctly`() {
        val testRow = row(
            column(0, "id", "INT8", "1"),
            column(1, "post_id", "INT8", "10"),
            column(2, "author_name", "TEXT", "John Doe"),
            column(3, "content", "TEXT", "Great article!"),
            column(4, "created_at", "TIMESTAMP", "2024-06-15 10:30:00")
        )

        val result = CommentAutoRowMapper.map(testRow, emptyRegistry)

        assertThat(result.id).isEqualTo(1L)
        assertThat(result.postId).isEqualTo(10L)
        assertThat(result.authorName).isEqualTo("John Doe")
        assertThat(result.content).isEqualTo("Great article!")
        assertThat(result.createdAt).isEqualTo(LocalDateTime(2024, 6, 15, 10, 30, 0))
    }

    @Test
    fun `CommentAutoRowMapper returns expected Comment type`() {
        val testRow = row(
            column(0, "id", "INT8", "5"),
            column(1, "post_id", "INT8", "20"),
            column(2, "author_name", "TEXT", "Jane"),
            column(3, "content", "TEXT", "Nice!"),
            column(4, "created_at", "TIMESTAMP", "2024-03-15 08:00:00")
        )

        val result = CommentAutoRowMapper.map(testRow, emptyRegistry)

        assertThat(result).isEqualTo(
            Comment(
                id = 5,
                postId = 20,
                authorName = "Jane",
                content = "Nice!",
                createdAt = LocalDateTime(2024, 3, 15, 8, 0, 0)
            )
        )
    }

    // Customer entity: Nullable column and Boolean

    @Test
    fun `CustomerAutoRowMapper maps all columns including nullable`() {
        val testRow = row(
            column(0, "id", "INT8", "1"),
            column(1, "first_name", "TEXT", "John"),
            column(2, "last_name", "TEXT", "Doe"),
            column(3, "email", "TEXT", "john@example.com"),
            column(4, "phone_number", "TEXT", "555-1234"),
            column(5, "is_active", "BOOL", "true")
        )

        val result = CustomerAutoRowMapper.map(testRow, emptyRegistry)

        assertThat(result.id).isEqualTo(1L)
        assertThat(result.firstName).isEqualTo("John")
        assertThat(result.lastName).isEqualTo("Doe")
        assertThat(result.email).isEqualTo("john@example.com")
        assertThat(result.phoneNumber).isEqualTo("555-1234")
        assertThat(result.isActive).isEqualTo(true)
    }

    @Test
    fun `CustomerAutoRowMapper maps null phoneNumber correctly`() {
        val testRow = row(
            column(0, "id", "INT8", "1"),
            column(1, "first_name", "TEXT", "Jane"),
            column(2, "last_name", "TEXT", "Smith"),
            column(3, "email", "TEXT", "jane@example.com"),
            column(4, "phone_number", "TEXT", null),
            column(5, "is_active", "BOOL", "false")
        )

        val result = CustomerAutoRowMapper.map(testRow, emptyRegistry)

        assertThat(result.phoneNumber).isNull()
        assertThat(result.isActive).isEqualTo(false)
    }

    @Test
    fun `CustomerAutoRowMapper maps boolean true variations`() {
        val testRow = row(
            column(0, "id", "INT8", "1"),
            column(1, "first_name", "TEXT", "Test"),
            column(2, "last_name", "TEXT", "User"),
            column(3, "email", "TEXT", "test@test.com"),
            column(4, "phone_number", "TEXT", null),
            column(5, "is_active", "BOOL", "t")  // PostgreSQL style
        )

        val result = CustomerAutoRowMapper.map(testRow, emptyRegistry)

        assertThat(result.isActive).isEqualTo(true)
    }

    @Test
    fun `CustomerAutoRowMapper maps boolean false variations`() {
        val testRow = row(
            column(0, "id", "INT8", "1"),
            column(1, "first_name", "TEXT", "Test"),
            column(2, "last_name", "TEXT", "User"),
            column(3, "email", "TEXT", "test@test.com"),
            column(4, "phone_number", "TEXT", null),
            column(5, "is_active", "BOOL", "f")  // PostgreSQL style
        )

        val result = CustomerAutoRowMapper.map(testRow, emptyRegistry)

        assertThat(result.isActive).isEqualTo(false)
    }

    @Test
    fun `CustomerAutoRowMapper maps boolean 1 and 0`() {
        // Test with "1"
        val activeRow = row(
            column(0, "id", "INT8", "1"),
            column(1, "first_name", "TEXT", "Test"),
            column(2, "last_name", "TEXT", "User"),
            column(3, "email", "TEXT", "test@test.com"),
            column(4, "phone_number", "TEXT", null),
            column(5, "is_active", "BOOL", "1")
        )
        assertThat(CustomerAutoRowMapper.map(activeRow, emptyRegistry).isActive).isEqualTo(true)

        // Test with "0"
        val inactiveRow = row(
            column(0, "id", "INT8", "1"),
            column(1, "first_name", "TEXT", "Test"),
            column(2, "last_name", "TEXT", "User"),
            column(3, "email", "TEXT", "test@test.com"),
            column(4, "phone_number", "TEXT", null),
            column(5, "is_active", "BOOL", "0")
        )
        assertThat(CustomerAutoRowMapper.map(inactiveRow, emptyRegistry).isActive).isEqualTo(false)
    }

    @Test
    fun `CustomerAutoRowMapper returns expected Customer type`() {
        val testRow = row(
            column(0, "id", "INT8", "99"),
            column(1, "first_name", "TEXT", "Alice"),
            column(2, "last_name", "TEXT", "Wonder"),
            column(3, "email", "TEXT", "alice@wonder.com"),
            column(4, "phone_number", "TEXT", null),
            column(5, "is_active", "BOOL", "true")
        )

        val result = CustomerAutoRowMapper.map(testRow, emptyRegistry)

        assertThat(result).isEqualTo(
            Customer(
                id = 99,
                firstName = "Alice",
                lastName = "Wonder",
                email = "alice@wonder.com",
                phoneNumber = null,
                isActive = true
            )
        )
    }

    // Tag entity: Int id

    @Test
    fun `TagAutoRowMapper maps all columns correctly`() {
        val testRow = row(
            column(0, "id", "INT4", "42"),
            column(1, "name", "TEXT", "kotlin")
        )

        val result = TagAutoRowMapper.map(testRow, emptyRegistry)

        assertThat(result.id).isEqualTo(42)
        assertThat(result.name).isEqualTo("kotlin")
    }

    @Test
    fun `TagAutoRowMapper returns expected Tag type`() {
        val testRow = row(
            column(0, "id", "INT4", "1"),
            column(1, "name", "TEXT", "java")
        )

        val result = TagAutoRowMapper.map(testRow, emptyRegistry)

        assertThat(result).isEqualTo(Tag(id = 1, name = "java"))
    }

    // Edge cases

    @Test
    fun `RowMapper handles unicode characters`() {
        val testRow = row(
            column(0, "id", "INT8", "1"),
            column(1, "name", "TEXT", "日本語テスト"),
            column(2, "email", "TEXT", "test@例え.jp")
        )

        val result = UserAutoRowMapper.map(testRow, emptyRegistry)

        assertThat(result.name).isEqualTo("日本語テスト")
        assertThat(result.email).isEqualTo("test@例え.jp")
    }

    @Test
    fun `RowMapper handles emoji in strings`() {
        val testRow = row(
            column(0, "id", "INT8", "1"),
            column(1, "name", "TEXT", "Test 🎉"),
            column(2, "email", "TEXT", "emoji@test.com")
        )

        val result = UserAutoRowMapper.map(testRow, emptyRegistry)

        assertThat(result.name).isEqualTo("Test 🎉")
    }

    @Test
    fun `RowMapper handles multiline strings`() {
        val testRow = row(
            column(0, "id", "INT8", "1"),
            column(1, "title", "TEXT", "Title"),
            column(2, "content", "TEXT", "Line 1\nLine 2\nLine 3"),
            column(3, "created_at", "TIMESTAMP", "2024-01-01 00:00:00"),
            column(4, "updated_at", "TIMESTAMP", "2024-01-01 00:00:00")
        )

        val result = ArticleAutoRowMapper.map(testRow, emptyRegistry)

        assertThat(result.content).isEqualTo("Line 1\nLine 2\nLine 3")
    }

    @Test
    fun `RowMapper handles very long strings`() {
        val longString = "a".repeat(10000)
        val testRow = row(
            column(0, "id", "INT8", "1"),
            column(1, "name", "TEXT", longString),
            column(2, "email", "TEXT", "test@test.com")
        )

        val result = UserAutoRowMapper.map(testRow, emptyRegistry)

        assertThat(result.name).isEqualTo(longString)
        assertThat(result.name.length).isEqualTo(10000)
    }

    @Test
    fun `RowMapper handles decimal precision`() {
        val testRow = row(
            column(0, "id", "TEXT", "p1"),
            column(1, "name", "TEXT", "Precise"),
            column(2, "price", "FLOAT8", "123.456789012345")
        )

        val result = ProductAutoRowMapper.map(testRow, emptyRegistry)

        assertThat(result.price).isEqualTo(123.456789012345)
    }
}
