package io.github.smyrgeorge.sqlx4k.processor

import assertk.assertThat
import assertk.assertions.containsExactlyInAnyOrder
import assertk.assertions.hasSize
import assertk.assertions.isEqualTo
import assertk.assertions.isFailure
import assertk.assertions.isSuccess
import io.github.smyrgeorge.sqlx4k.SQLError
import io.github.smyrgeorge.sqlx4k.processor.test.generated.InMemoryDocumentCrudRepository
import io.github.smyrgeorge.sqlx4k.processor.util.Document
import io.github.smyrgeorge.sqlx4k.processor.util.MockQueryExecutor
import kotlinx.coroutines.test.runTest
import kotlin.test.BeforeTest
import kotlin.test.Test

/**
 * Optimistic-locking behavior of the generated in-memory doubles for `@Version`-ed entities.
 */
class InMemoryOptimisticLockTests {

    private lateinit var ctx: MockQueryExecutor
    private lateinit var repo: InMemoryDocumentCrudRepository

    @BeforeTest
    fun setup() {
        ctx = MockQueryExecutor()
        repo = InMemoryDocumentCrudRepository()
    }

    private fun Result<*>.code(): SQLError.Code = (exceptionOrNull() as SQLError).code

    @Test
    fun `insert stores the given version as-is`() = runTest {
        val inserted = repo.insert(ctx, Document(id = 0, title = "A")).getOrThrow()

        assertThat(inserted.id).isEqualTo(1L)
        assertThat(inserted.version).isEqualTo(0L)
    }

    @Test
    fun `update increments the version and returns the stored entity`() = runTest {
        val inserted = repo.insert(ctx, Document(id = 0, title = "A")).getOrThrow()

        val result = repo.update(ctx, inserted.copy(title = "B"))

        assertThat(result).isSuccess()
        assertThat(result.getOrThrow().version).isEqualTo(1L)
        assertThat(repo.findAllStored().single()).isEqualTo(Document(id = 1, title = "B", version = 1))
    }

    @Test
    fun `update with a stale version fails with OptimisticLockFailed and changes nothing`() = runTest {
        val inserted = repo.insert(ctx, Document(id = 0, title = "A")).getOrThrow()
        repo.update(ctx, inserted.copy(title = "B")).getOrThrow() // version 0 -> 1

        val stale = repo.update(ctx, inserted.copy(title = "C")) // still carries version 0

        assertThat(stale).isFailure()
        assertThat(stale.code()).isEqualTo(SQLError.Code.OptimisticLockFailed)
        assertThat(repo.findAllStored().single().title).isEqualTo("B")
    }

    @Test
    fun `update of a missing entity fails with OptimisticLockFailed`() = runTest {
        val result = repo.update(ctx, Document(id = 99, title = "Ghost"))

        assertThat(result).isFailure()
        assertThat(result.code()).isEqualTo(SQLError.Code.OptimisticLockFailed)
    }

    @Test
    fun `delete with the current version removes the entity`() = runTest {
        val inserted = repo.insert(ctx, Document(id = 0, title = "A")).getOrThrow()
        val updated = repo.update(ctx, inserted.copy(title = "B")).getOrThrow()

        assertThat(repo.delete(ctx, updated)).isSuccess()
        assertThat(repo.findAllStored()).hasSize(0)
    }

    @Test
    fun `delete with a stale version fails with OptimisticLockFailed and keeps the entity`() = runTest {
        val inserted = repo.insert(ctx, Document(id = 0, title = "A")).getOrThrow()
        repo.update(ctx, inserted.copy(title = "B")).getOrThrow() // version 0 -> 1

        val result = repo.delete(ctx, inserted) // version 0

        assertThat(result).isFailure()
        assertThat(result.code()).isEqualTo(SQLError.Code.OptimisticLockFailed)
        assertThat(repo.findAllStored()).hasSize(1)
    }

    @Test
    fun `batchUpdate increments every version`() = runTest {
        val a = repo.insert(ctx, Document(id = 0, title = "A")).getOrThrow()
        val b = repo.insert(ctx, Document(id = 0, title = "B")).getOrThrow()

        val result = repo.batchUpdate(ctx, listOf(a.copy(title = "A2"), b.copy(title = "B2")))

        assertThat(result).isSuccess()
        assertThat(result.getOrThrow().map { it.version }).containsExactlyInAnyOrder(1L, 1L)
        assertThat(repo.findAllStored().map { it.title }).containsExactlyInAnyOrder("A2", "B2")
    }

    @Test
    fun `batchUpdate with a stale entity fails with OptimisticLockFailed`() = runTest {
        val a = repo.insert(ctx, Document(id = 0, title = "A")).getOrThrow()
        val b = repo.insert(ctx, Document(id = 0, title = "B")).getOrThrow()
        repo.update(ctx, b.copy(title = "B1")).getOrThrow() // b is now at version 1

        val result = repo.batchUpdate(ctx, listOf(a.copy(title = "A2"), b.copy(title = "B2")))

        assertThat(result).isFailure()
        assertThat(result.code()).isEqualTo(SQLError.Code.OptimisticLockFailed)
    }

    @Test
    fun `save of an existing entity honors the version`() = runTest {
        val inserted = repo.save(ctx, Document(id = 0, title = "A")).getOrThrow()
        val updated = repo.save(ctx, inserted.copy(title = "B")).getOrThrow()

        assertThat(updated.version).isEqualTo(1L)
        assertThat(repo.save(ctx, inserted.copy(title = "C")).code()).isEqualTo(SQLError.Code.OptimisticLockFailed)
    }
}
