package io.github.smyrgeorge.sqlx4k.mysql

import io.github.smyrgeorge.sqlx4k.ConnectionPool
import io.github.smyrgeorge.sqlx4k.ValueEncoderRegistry
import kotlin.test.Test

class NativeMySQLPreparedStatementTests {

    private val options = ConnectionPool.Options.builder()
        .maxConnections(2)
        .build()

    private val encoders = ValueEncoderRegistry()
        .register<Money>(MoneyEncoder)
        .register<Tag>(TagEncoder)

    private val db = mySQL(
        url = "mysql://localhost:13306/test",
        username = "mysql",
        password = "mysql",
        options = options,
        encoders = encoders
    )

    private val runner = CommonMySQLPreparedStatementTests(db)

    @Test
    fun `positional params with basic types`() = runner.`positional params with basic types`()

    @Test
    fun `named params with basic types`() = runner.`named params with basic types`()

    @Test
    fun `null parameter binding`() = runner.`null parameter binding`()

    @Test
    fun `datetime and uuid types as parameters`() = runner.`datetime and uuid types as parameters`()

    @Test
    fun `type cast preservation with params`() = runner.`type cast preservation with params`()

    @Test
    fun `reused named parameter`() = runner.`reused named parameter`()

    @Test
    fun `string values with special characters`() = runner.`string values with special characters`()

    @Test
    fun `list expansion with IN clause`() = runner.`list expansion with IN clause`()

    @Test
    fun `set expansion with IN clause`() = runner.`set expansion with IN clause`()

    @Test
    fun `intArray expansion with IN clause`() = runner.`intArray expansion with IN clause`()

    @Test
    fun `longArray expansion with IN clause`() = runner.`longArray expansion with IN clause`()

    @Test
    fun `set expansion with custom types`() = runner.`set expansion with custom types`()

    @Test
    fun `collection expansion mixed with scalar params`() = runner.`collection expansion mixed with scalar params`()

    @Test
    fun `custom type with encoder as positional param`() = runner.`custom type with encoder as positional param`()

    @Test
    fun `custom type with encoder as named param`() = runner.`custom type with encoder as named param`()

    @Test
    fun `custom type in collection expansion`() = runner.`custom type in collection expansion`()

    @Test
    fun `custom type mixed with primitives and collections`() =
        runner.`custom type mixed with primitives and collections`()

    @Test
    fun `reused named param with custom type`() = runner.`reused named param with custom type`()

    @Test
    fun `custom type with type cast`() = runner.`custom type with type cast`()

    @Test
    fun `enum as parameter`() = runner.`enum as parameter`()

    @Test
    fun `char as parameter`() = runner.`char as parameter`()

    @Test
    fun `byte as parameter`() = runner.`byte as parameter`()

    @Test
    fun `batch insert and filtered select with multiple params`() =
        runner.`batch insert and filtered select with multiple params`()
}
