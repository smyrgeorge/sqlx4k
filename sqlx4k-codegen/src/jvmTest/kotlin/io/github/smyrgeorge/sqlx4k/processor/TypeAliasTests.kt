package io.github.smyrgeorge.sqlx4k.processor

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isTrue
import io.github.smyrgeorge.sqlx4k.ValueEncoderRegistry
import io.github.smyrgeorge.sqlx4k.processor.test.generated.AliasedAutoRowMapper
import io.github.smyrgeorge.sqlx4k.processor.test.generated.AliasedCrudRepositoryImpl
import io.github.smyrgeorge.sqlx4k.processor.util.Aliased
import io.github.smyrgeorge.sqlx4k.processor.util.MockQueryExecutor
import io.github.smyrgeorge.sqlx4k.processor.util.MockQueryExecutor.Companion.row
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
}
