package io.github.smyrgeorge.sqlx4k.processor.test

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import io.github.smyrgeorge.sqlx4k.ResultSet
import io.github.smyrgeorge.sqlx4k.SQLError
import io.github.smyrgeorge.sqlx4k.ValueEncoder
import io.github.smyrgeorge.sqlx4k.ValueEncoderRegistry
import io.github.smyrgeorge.sqlx4k.processor.test.entities.GeoPoint
import io.github.smyrgeorge.sqlx4k.processor.test.entities.Invoice
import io.github.smyrgeorge.sqlx4k.processor.test.entities.Money
import io.github.smyrgeorge.sqlx4k.processor.test.entities.Payment
import io.github.smyrgeorge.sqlx4k.processor.test.entities.Store
import io.github.smyrgeorge.sqlx4k.processor.test.entities.Transaction
import io.github.smyrgeorge.sqlx4k.processor.test.generated.InvoiceAutoRowMapper
import io.github.smyrgeorge.sqlx4k.processor.test.generated.PaymentAutoRowMapper
import io.github.smyrgeorge.sqlx4k.processor.test.generated.StoreAutoRowMapper
import io.github.smyrgeorge.sqlx4k.processor.test.generated.TransactionAutoRowMapper
import io.github.smyrgeorge.sqlx4k.processor.test.generated.delete
import io.github.smyrgeorge.sqlx4k.processor.test.generated.insert
import io.github.smyrgeorge.sqlx4k.processor.test.generated.update
import kotlin.test.Test
import kotlin.test.assertFailsWith

/**
 * Tests for entities with custom types using ValueEncoderRegistry.
 */
class CustomTypeTests {

    // Custom ValueEncoder for Money type
    class MoneyEncoder : ValueEncoder<Money> {
        override fun encode(value: Money): Any = value.toString() // "100.50:USD"

        override fun decode(value: ResultSet.Row.Column): Money =
            Money.parse(value.asString())
    }

    // Custom ValueEncoder for GeoPoint type
    class GeoPointEncoder : ValueEncoder<GeoPoint> {
        override fun encode(value: GeoPoint): Any = value.toString() // "40.7128,-74.0060"

        override fun decode(value: ResultSet.Row.Column): GeoPoint =
            GeoPoint.parse(value.asString())
    }

    // Create a registry with both encoders
    private val registry = ValueEncoderRegistry()
        .register(MoneyEncoder())
        .register(GeoPointEncoder())

    // Helper functions
    private fun column(ordinal: Int, name: String, type: String, value: String?): ResultSet.Row.Column =
        ResultSet.Row.Column(ordinal, name, type, value)

    private fun row(vararg columns: ResultSet.Row.Column): ResultSet.Row =
        ResultSet.Row(columns.toList())

    // ===========================================
    // INSERT Statement Tests with Custom Types
    // ===========================================

    @Test
    fun `Invoice insert renders custom Money type correctly`() {
        val invoice = Invoice(
            id = 0,
            description = "Test Invoice",
            totalAmount = Money(100.50, "USD")
        )

        val sql = invoice.insert().render(registry)

        assertThat(sql).contains("insert into invoices")
        assertThat(sql).contains("description")
        assertThat(sql).contains("total_amount")
        assertThat(sql).contains("'Test Invoice'")
        assertThat(sql).contains("'100.5:USD'")
    }

    @Test
    fun `Invoice insert with zero amount`() {
        val invoice = Invoice(
            id = 0,
            description = "Free Item",
            totalAmount = Money(0.0, "EUR")
        )

        val sql = invoice.insert().render(registry)

        assertThat(sql).contains("'0.0:EUR'")
    }

    @Test
    fun `Invoice insert with large amount`() {
        val invoice = Invoice(
            id = 0,
            description = "Big Purchase",
            totalAmount = Money(999999.99, "GBP")
        )

        val sql = invoice.insert().render(registry)

        assertThat(sql).contains("'999999.99:GBP'")
    }

    @Test
    fun `Store insert with non-null GeoPoint`() {
        val store = Store(
            id = 0,
            name = "NYC Store",
            location = GeoPoint(40.7128, -74.0060)
        )

        val sql = store.insert().render(registry)

        assertThat(sql).contains("insert into stores")
        assertThat(sql).contains("'NYC Store'")
        assertThat(sql).contains("'40.7128,-74.006'")
    }

    @Test
    fun `Store insert with null GeoPoint`() {
        val store = Store(
            id = 0,
            name = "Online Store",
            location = null
        )

        val sql = store.insert().render(registry)

        assertThat(sql).contains("insert into stores")
        assertThat(sql).contains("'Online Store'")
        assertThat(sql).contains("null")
    }

    @Test
    fun `Transaction insert with multiple custom types`() {
        val transaction = Transaction(
            id = 0,
            fromAmount = Money(100.0, "USD"),
            toAmount = Money(85.0, "EUR"),
            exchangeLocation = GeoPoint(51.5074, -0.1278)
        )

        val sql = transaction.insert().render(registry)

        assertThat(sql).contains("insert into transactions")
        assertThat(sql).contains("'100.0:USD'")
        assertThat(sql).contains("'85.0:EUR'")
        assertThat(sql).contains("'51.5074,-0.1278'")
    }

    @Test
    fun `Transaction insert with null location`() {
        val transaction = Transaction(
            id = 0,
            fromAmount = Money(50.0, "USD"),
            toAmount = Money(45.0, "CAD"),
            exchangeLocation = null
        )

        val sql = transaction.insert().render(registry)

        assertThat(sql).contains("'50.0:USD'")
        assertThat(sql).contains("'45.0:CAD'")
        assertThat(sql).contains("null")
    }

    @Test
    fun `Payment insert excludes processedAmount column`() {
        val payment = Payment(
            id = 0,
            amount = Money(75.0, "USD"),
            processedAmount = Money(74.50, "USD") // Should be excluded
        )

        val sql = payment.insert().render(registry)

        assertThat(sql).contains("insert into payments(amount)")
        assertThat(sql).contains("'75.0:USD'")
        // processedAmount should NOT be in the insert
    }

    // ===========================================
    // UPDATE Statement Tests with Custom Types
    // ===========================================

    @Test
    fun `Invoice update renders custom Money type correctly`() {
        val invoice = Invoice(
            id = 42,
            description = "Updated Invoice",
            totalAmount = Money(200.75, "EUR")
        )

        val sql = invoice.update().render(registry)

        assertThat(sql).contains("update invoices")
        assertThat(sql).contains("set description = 'Updated Invoice', total_amount = '200.75:EUR'")
        assertThat(sql).contains("where id = 42")
    }

    @Test
    fun `Store update with GeoPoint`() {
        val store = Store(
            id = 10,
            name = "Relocated Store",
            location = GeoPoint(34.0522, -118.2437)
        )

        val sql = store.update().render(registry)

        assertThat(sql).contains("update stores")
        assertThat(sql).contains("name = 'Relocated Store'")
        assertThat(sql).contains("'34.0522,-118.2437'")
        assertThat(sql).contains("where id = 10")
    }

    @Test
    fun `Store update setting location to null`() {
        val store = Store(
            id = 5,
            name = "Now Online",
            location = null
        )

        val sql = store.update().render(registry)

        assertThat(sql).contains("location = null")
    }

    @Test
    fun `Transaction update with multiple custom types`() {
        val transaction = Transaction(
            id = 100,
            fromAmount = Money(500.0, "GBP"),
            toAmount = Money(600.0, "USD"),
            exchangeLocation = GeoPoint(48.8566, 2.3522)
        )

        val sql = transaction.update().render(registry)

        assertThat(sql).contains("update transactions")
        assertThat(sql).contains("'500.0:GBP'")
        assertThat(sql).contains("'600.0:USD'")
        assertThat(sql).contains("'48.8566,2.3522'")
        assertThat(sql).contains("where id = 100")
    }

    @Test
    fun `Payment update excludes processedAmount column`() {
        val payment = Payment(
            id = 25,
            amount = Money(150.0, "USD"),
            processedAmount = Money(149.0, "USD")
        )

        val sql = payment.update().render(registry)

        assertThat(sql).contains("update payments set amount = '150.0:USD' where id = 25")
    }

    // ===========================================
    // DELETE Statement Tests with Custom Types
    // ===========================================

    @Test
    fun `Invoice delete does not include custom type in query`() {
        val invoice = Invoice(
            id = 99,
            description = "To Delete",
            totalAmount = Money(50.0, "USD")
        )

        val sql = invoice.delete().render(registry)

        assertThat(sql).isEqualTo("delete from invoices where id = 99;")
    }

    @Test
    fun `Store delete does not include custom type in query`() {
        val store = Store(
            id = 77,
            name = "Closing Store",
            location = GeoPoint(0.0, 0.0)
        )

        val sql = store.delete().render(registry)

        assertThat(sql).isEqualTo("delete from stores where id = 77;")
    }

    // ===========================================
    // RowMapper Tests with Custom Types
    // ===========================================

    @Test
    fun `InvoiceAutoRowMapper maps custom Money type`() {
        val testRow = row(
            column(0, "id", "INT8", "1"),
            column(1, "description", "TEXT", "Test Invoice"),
            column(2, "total_amount", "TEXT", "99.99:USD")
        )

        val result = InvoiceAutoRowMapper.map(testRow, registry)

        assertThat(result.id).isEqualTo(1L)
        assertThat(result.description).isEqualTo("Test Invoice")
        assertThat(result.totalAmount).isEqualTo(Money(99.99, "USD"))
    }

    @Test
    fun `InvoiceAutoRowMapper maps zero Money amount`() {
        val testRow = row(
            column(0, "id", "INT8", "2"),
            column(1, "description", "TEXT", "Free"),
            column(2, "total_amount", "TEXT", "0.0:EUR")
        )

        val result = InvoiceAutoRowMapper.map(testRow, registry)

        assertThat(result.totalAmount).isEqualTo(Money(0.0, "EUR"))
    }

    @Test
    fun `InvoiceAutoRowMapper maps different currencies`() {
        val currencies = listOf("USD", "EUR", "GBP", "JPY", "CAD")
        currencies.forEach { currency ->
            val testRow = row(
                column(0, "id", "INT8", "1"),
                column(1, "description", "TEXT", "Multi-currency"),
                column(2, "total_amount", "TEXT", "100.0:$currency")
            )

            val result = InvoiceAutoRowMapper.map(testRow, registry)

            assertThat(result.totalAmount.currency).isEqualTo(currency)
        }
    }

    @Test
    fun `StoreAutoRowMapper maps non-null GeoPoint`() {
        val testRow = row(
            column(0, "id", "INT8", "1"),
            column(1, "name", "TEXT", "NYC Store"),
            column(2, "location", "TEXT", "40.7128,-74.006")
        )

        val result = StoreAutoRowMapper.map(testRow, registry)

        assertThat(result.id).isEqualTo(1L)
        assertThat(result.name).isEqualTo("NYC Store")
        assertThat(result.location).isEqualTo(GeoPoint(40.7128, -74.006))
    }

    @Test
    fun `StoreAutoRowMapper maps null GeoPoint`() {
        val testRow = row(
            column(0, "id", "INT8", "2"),
            column(1, "name", "TEXT", "Online Store"),
            column(2, "location", "TEXT", null)
        )

        val result = StoreAutoRowMapper.map(testRow, registry)

        assertThat(result.id).isEqualTo(2L)
        assertThat(result.name).isEqualTo("Online Store")
        assertThat(result.location).isNull()
    }

    @Test
    fun `StoreAutoRowMapper maps negative coordinates`() {
        val testRow = row(
            column(0, "id", "INT8", "3"),
            column(1, "name", "TEXT", "Southern Store"),
            column(2, "location", "TEXT", "-33.8688,151.2093")
        )

        val result = StoreAutoRowMapper.map(testRow, registry)

        assertThat(result.location).isEqualTo(GeoPoint(-33.8688, 151.2093))
    }

    @Test
    fun `TransactionAutoRowMapper maps multiple custom types`() {
        val testRow = row(
            column(0, "id", "INT8", "1"),
            column(1, "from_amount", "TEXT", "100.0:USD"),
            column(2, "to_amount", "TEXT", "85.0:EUR"),
            column(3, "exchange_location", "TEXT", "51.5074,-0.1278")
        )

        val result = TransactionAutoRowMapper.map(testRow, registry)

        assertThat(result.id).isEqualTo(1L)
        assertThat(result.fromAmount).isEqualTo(Money(100.0, "USD"))
        assertThat(result.toAmount).isEqualTo(Money(85.0, "EUR"))
        assertThat(result.exchangeLocation).isEqualTo(GeoPoint(51.5074, -0.1278))
    }

    @Test
    fun `TransactionAutoRowMapper maps null exchange location`() {
        val testRow = row(
            column(0, "id", "INT8", "2"),
            column(1, "from_amount", "TEXT", "50.0:USD"),
            column(2, "to_amount", "TEXT", "45.0:CAD"),
            column(3, "exchange_location", "TEXT", null)
        )

        val result = TransactionAutoRowMapper.map(testRow, registry)

        assertThat(result.fromAmount).isEqualTo(Money(50.0, "USD"))
        assertThat(result.toAmount).isEqualTo(Money(45.0, "CAD"))
        assertThat(result.exchangeLocation).isNull()
    }

    @Test
    fun `PaymentAutoRowMapper maps both custom type columns`() {
        val testRow = row(
            column(0, "id", "INT8", "1"),
            column(1, "amount", "TEXT", "100.0:USD"),
            column(2, "processed_amount", "TEXT", "99.0:USD")
        )

        val result = PaymentAutoRowMapper.map(testRow, registry)

        assertThat(result.id).isEqualTo(1L)
        assertThat(result.amount).isEqualTo(Money(100.0, "USD"))
        assertThat(result.processedAmount).isEqualTo(Money(99.0, "USD"))
    }

    @Test
    fun `PaymentAutoRowMapper maps null processedAmount`() {
        val testRow = row(
            column(0, "id", "INT8", "2"),
            column(1, "amount", "TEXT", "75.0:EUR"),
            column(2, "processed_amount", "TEXT", null)
        )

        val result = PaymentAutoRowMapper.map(testRow, registry)

        assertThat(result.amount).isEqualTo(Money(75.0, "EUR"))
        assertThat(result.processedAmount).isNull()
    }

    // ===========================================
    // Error Handling Tests
    // ===========================================

    @Test
    fun `RowMapper fails without encoder in registry`() {
        val emptyRegistry = ValueEncoderRegistry.EMPTY

        val testRow = row(
            column(0, "id", "INT8", "1"),
            column(1, "description", "TEXT", "Test"),
            column(2, "total_amount", "TEXT", "100.0:USD")
        )

        val exception = assertFailsWith<SQLError> {
            InvoiceAutoRowMapper.map(testRow, emptyRegistry)
        }

        assertThat(exception.code).isEqualTo(SQLError.Code.MissingValueConverter)
        assertThat(exception.message!!).contains("No decoder found for type")
        assertThat(exception.message!!).contains("Money")
    }

    @Test
    fun `RowMapper fails with registry missing specific encoder`() {
        // Registry with only MoneyEncoder, missing GeoPointEncoder
        val partialRegistry = ValueEncoderRegistry()
            .register(MoneyEncoder())

        val testRow = row(
            column(0, "id", "INT8", "1"),
            column(1, "name", "TEXT", "Store"),
            column(2, "location", "TEXT", "40.0,-74.0")
        )

        val exception = assertFailsWith<SQLError> {
            StoreAutoRowMapper.map(testRow, partialRegistry)
        }

        assertThat(exception.code).isEqualTo(SQLError.Code.MissingValueConverter)
        assertThat(exception.message!!).contains("No decoder found for type")
        assertThat(exception.message!!).contains("GeoPoint")
    }

    // ===========================================
    // Round-trip Tests (Insert -> Map)
    // ===========================================

    @Test
    fun `Invoice round-trip preserves data`() {
        val original = Invoice(
            id = 0,
            description = "Round-trip Test",
            totalAmount = Money(123.45, "USD")
        )

        // Simulate what gets inserted
        val insertSql = original.insert().render(registry)
        assertThat(insertSql).contains("'123.45:USD'")

        // Simulate what comes back from DB
        val dbRow = row(
            column(0, "id", "INT8", "1"),
            column(1, "description", "TEXT", "Round-trip Test"),
            column(2, "total_amount", "TEXT", "123.45:USD")
        )

        val mapped = InvoiceAutoRowMapper.map(dbRow, registry)

        assertThat(mapped.description).isEqualTo(original.description)
        assertThat(mapped.totalAmount).isEqualTo(original.totalAmount)
    }

    @Test
    fun `Store round-trip with location preserves data`() {
        val original = Store(
            id = 0,
            name = "Test Store",
            location = GeoPoint(40.7128, -74.0060)
        )

        // Simulate insert
        val insertSql = original.insert().render(registry)
        assertThat(insertSql).contains("'40.7128,-74.006'")

        // Simulate DB response
        val dbRow = row(
            column(0, "id", "INT8", "1"),
            column(1, "name", "TEXT", "Test Store"),
            column(2, "location", "TEXT", "40.7128,-74.006")
        )

        val mapped = StoreAutoRowMapper.map(dbRow, registry)

        assertThat(mapped.name).isEqualTo(original.name)
        assertThat(mapped.location).isEqualTo(original.location)
    }

    @Test
    fun `Store round-trip with null location preserves data`() {
        val original = Store(
            id = 0,
            name = "Online Store",
            location = null
        )

        // Simulate insert
        val insertSql = original.insert().render(registry)
        assertThat(insertSql).contains("null")

        // Simulate DB response
        val dbRow = row(
            column(0, "id", "INT8", "1"),
            column(1, "name", "TEXT", "Online Store"),
            column(2, "location", "TEXT", null)
        )

        val mapped = StoreAutoRowMapper.map(dbRow, registry)

        assertThat(mapped.name).isEqualTo(original.name)
        assertThat(mapped.location).isNull()
    }

    @Test
    fun `Transaction round-trip preserves all custom types`() {
        val original = Transaction(
            id = 0,
            fromAmount = Money(1000.0, "USD"),
            toAmount = Money(850.0, "EUR"),
            exchangeLocation = GeoPoint(48.8566, 2.3522)
        )

        // Simulate insert
        val insertSql = original.insert().render(registry)
        assertThat(insertSql).contains("'1000.0:USD'")
        assertThat(insertSql).contains("'850.0:EUR'")
        assertThat(insertSql).contains("'48.8566,2.3522'")

        // Simulate DB response
        val dbRow = row(
            column(0, "id", "INT8", "1"),
            column(1, "from_amount", "TEXT", "1000.0:USD"),
            column(2, "to_amount", "TEXT", "850.0:EUR"),
            column(3, "exchange_location", "TEXT", "48.8566,2.3522")
        )

        val mapped = TransactionAutoRowMapper.map(dbRow, registry)

        assertThat(mapped.fromAmount).isEqualTo(original.fromAmount)
        assertThat(mapped.toAmount).isEqualTo(original.toAmount)
        assertThat(mapped.exchangeLocation).isEqualTo(original.exchangeLocation)
    }
}
