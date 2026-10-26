package io.github.smyrgeorge.sqlx4k.processor

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isTrue
import io.github.smyrgeorge.sqlx4k.ResultSet
import io.github.smyrgeorge.sqlx4k.ValueEncoder
import io.github.smyrgeorge.sqlx4k.ValueEncoderRegistry
import io.github.smyrgeorge.sqlx4k.impl.extensions.asDouble
import io.github.smyrgeorge.sqlx4k.processor.test.generated.AliasedAutoRowMapper
import io.github.smyrgeorge.sqlx4k.processor.test.generated.AliasedCentsEntityAutoRowMapper
import io.github.smyrgeorge.sqlx4k.processor.test.generated.AliasedConvertedAutoRowMapper
import io.github.smyrgeorge.sqlx4k.processor.test.generated.AliasedCrudRepositoryImpl
import io.github.smyrgeorge.sqlx4k.processor.test.generated.AliasedDistanceEntityAutoRowMapper
import io.github.smyrgeorge.sqlx4k.processor.util.Aliased
import io.github.smyrgeorge.sqlx4k.processor.util.AliasedCentsEntity
import io.github.smyrgeorge.sqlx4k.processor.util.AliasedConverted
import io.github.smyrgeorge.sqlx4k.processor.util.AliasedDistance
import io.github.smyrgeorge.sqlx4k.processor.util.AliasedDistanceEntity
import io.github.smyrgeorge.sqlx4k.processor.util.Measured
import io.github.smyrgeorge.sqlx4k.processor.util.MockQueryExecutor
import io.github.smyrgeorge.sqlx4k.processor.util.MockQueryExecutor.Companion.row
import io.github.smyrgeorge.sqlx4k.processor.util.Money
import kotlin.test.Test
import kotlinx.coroutines.test.runTest

/**
 * Properties declared through type aliases (`typealias AliasedKey = Long`) are mapped by the aliased types.
 */
class TypeAliasTests {

    @Test
    fun `the row mapper decodes aliased properties`() {
        val row = row("id" to "7", "name" to "Alice", "note" to null)
        assertThat(AliasedAutoRowMapper.map(row, ValueEncoderRegistry.EMPTY))
            .isEqualTo(Aliased(id = 7, name = "Alice", note = null))
    }

    @Test
    fun `save inserts an entity whose aliased id is zero`() = runTest {
        val executor = MockQueryExecutor()
        executor.setFetchAllRows(row("id" to "1"))

        val result = AliasedCrudRepositoryImpl.save(executor, Aliased(id = 0, name = "Bob", note = "n"))

        assertThat(result.getOrThrow()).isEqualTo(Aliased(id = 1, name = "Bob", note = "n"))
        assertThat(executor.hasExecuted("insert into aliased")).isTrue()
    }

    @Test
    fun `the row mapper decodes a converted property declared through an alias`() {
        val row = row("id" to "1", "price" to "9.5:EUR")
        assertThat(AliasedConvertedAutoRowMapper.map(row, ValueEncoderRegistry.EMPTY))
            .isEqualTo(AliasedConverted(id = 1, price = Money(9.5, "EUR")))
    }

    @Test
    fun `the row mapper decodes a converted property declared through an alias of a primitive`() {
        val row = row("id" to "1", "amount" to "12.05")
        assertThat(AliasedCentsEntityAutoRowMapper.map(row, ValueEncoderRegistry.EMPTY))
            .isEqualTo(AliasedCentsEntity(id = 1, amount = 1205))
    }

    @Test
    fun `the row mapper decodes a property declared through an alias of a generic type`() {
        val registry = ValueEncoderRegistry().register<AliasedDistance>(
            object : ValueEncoder<AliasedDistance> {
                override fun encode(value: AliasedDistance): Any = value.amount
                override fun decode(value: ResultSet.Row.Column): AliasedDistance = Measured(value.asDouble())
            }
        )
        val row = row("id" to "1", "distance" to "2.5", "detour" to null)
        assertThat(AliasedDistanceEntityAutoRowMapper.map(row, registry))
            .isEqualTo(AliasedDistanceEntity(id = 1, distance = Measured(2.5), detour = null))
    }
}
