package io.github.smyrgeorge.sqlx4k.processor

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.containsExactlyInAnyOrder
import assertk.assertions.hasSize
import assertk.assertions.isEqualTo
import io.github.smyrgeorge.sqlx4k.processor.test.generated.InMemoryUserContextRepositoryWithHooks
import io.github.smyrgeorge.sqlx4k.processor.test.generated.InMemoryUserRepositoryWithHooks
import io.github.smyrgeorge.sqlx4k.processor.util.HooksTracker
import io.github.smyrgeorge.sqlx4k.processor.util.MockQueryExecutor
import io.github.smyrgeorge.sqlx4k.processor.util.User
import kotlinx.coroutines.test.runTest
import kotlin.test.BeforeTest
import kotlin.test.Test

/**
 * Verifies the generated in-memory doubles honor the overridden [CrudRepositoryHooks] the same way
 * the real generated repositories do.
 */
class InMemoryRepositoryHooksTests {

    private lateinit var ctx: MockQueryExecutor
    private lateinit var repo: InMemoryUserRepositoryWithHooks

    @BeforeTest
    fun setup() {
        ctx = MockQueryExecutor()
        repo = InMemoryUserRepositoryWithHooks()
        HooksTracker.reset()
    }

    @Test
    fun `insert runs preInsert, aroundQuery, afterInsert in order`() = runTest {
        val result = repo.insert(ctx, User(id = 0, name = "Alice", email = "a@example.com"))

        // The returned entity went through pre- then after-insert.
        assertThat(result.getOrThrow().id).isEqualTo(1L)
        assertThat(result.getOrThrow().name).isEqualTo("Alice_preInsert_afterInsert")
        // The stored entity reflects only the pre-insert transformation.
        assertThat(repo.findAllStored().single().name).isEqualTo("Alice_preInsert")
        assertThat(HooksTracker.calls).containsExactly(
            "preInsert:Alice",
            "aroundQuery:insert",
            "afterInsert:Alice_preInsert",
        )
    }

    @Test
    fun `update runs preUpdate, aroundQuery, afterUpdate`() = runTest {
        val inserted = repo.insert(ctx, User(id = 0, name = "Alice", email = "a@example.com")).getOrThrow()
        HooksTracker.reset()

        val result = repo.update(ctx, inserted.copy(name = "Bob"))
        assertThat(result.getOrThrow().name).isEqualTo("Bob_preUpdate_afterUpdate")
        assertThat(repo.findAllStored().single().name).isEqualTo("Bob_preUpdate")
        assertThat(HooksTracker.calls).containsExactly(
            "preUpdate:Bob",
            "aroundQuery:update",
            "afterUpdate:Bob_preUpdate",
        )
    }

    @Test
    fun `delete runs preDelete, aroundQuery, afterDelete`() = runTest {
        val inserted = repo.insert(ctx, User(id = 0, name = "Alice", email = "a@example.com")).getOrThrow()
        HooksTracker.reset()

        // `inserted` is the value returned by insert(), i.e. after both insert hooks ran.
        val result = repo.delete(ctx, inserted)
        assertThat(result.isSuccess).isEqualTo(true)
        assertThat(repo.findAllStored()).hasSize(0)
        assertThat(HooksTracker.calls).containsExactly(
            "preDelete:Alice_preInsert_afterInsert",
            "aroundQuery:delete",
            "afterDelete:Alice_preInsert_afterInsert",
        )
    }

    @Test
    fun `batchInsert applies pre and after hooks per entity and wraps aroundQuery`() = runTest {
        val result = repo.batchInsert(
            ctx,
            listOf(
                User(id = 0, name = "Alice", email = "a@example.com"),
                User(id = 0, name = "Bob", email = "b@example.com"),
            )
        )
        assertThat(result.getOrThrow().map { it.name })
            .containsExactlyInAnyOrder("Alice_preInsert_afterInsert", "Bob_preInsert_afterInsert")
        assertThat(repo.findAllStored().map { it.name })
            .containsExactlyInAnyOrder("Alice_preInsert", "Bob_preInsert")
        assertThat(HooksTracker.calls).containsExactly(
            "preInsert:Alice",
            "preInsert:Bob",
            "aroundQuery:batchInsert",
            "afterInsert:Alice_preInsert",
            "afterInsert:Bob_preInsert",
        )
    }

    @Test
    fun `derived query methods are wrapped in aroundQuery`() = runTest {
        repo.insert(ctx, User(id = 0, name = "Alice", email = "a@example.com"))
        HooksTracker.reset()

        assertThat(repo.findAll(ctx).getOrThrow()).hasSize(1)
        assertThat(repo.findOneById(ctx, 1L).getOrThrow()?.name).isEqualTo("Alice_preInsert")
        assertThat(HooksTracker.calls).containsExactly(
            "aroundQuery:findAll",
            "aroundQuery:findOneById",
        )
    }

    @Test
    fun `save delegates to insert and applies its hooks`() = runTest {
        val saved = repo.save(ctx, User(id = 0, name = "Alice", email = "a@example.com")).getOrThrow()
        assertThat(saved.name).isEqualTo("Alice_preInsert_afterInsert")
        assertThat(HooksTracker.calls).containsExactly(
            "preInsert:Alice",
            "aroundQuery:insert",
            "afterInsert:Alice_preInsert",
        )
    }

    @Test
    fun `context-parameter repository honors hooks via with(context)`() = runTest {
        val cRepo = InMemoryUserContextRepositoryWithHooks()
        with(ctx) {
            val inserted = cRepo.insert(User(id = 0, name = "Alice", email = "a@example.com")).getOrThrow()
            assertThat(inserted.name).isEqualTo("Alice_preInsert_afterInsert")
            assertThat(cRepo.findAll().getOrThrow().single().name).isEqualTo("Alice_preInsert")
        }
        assertThat(HooksTracker.calls).containsExactly(
            "preInsert:Alice",
            "aroundQuery:insert",
            "afterInsert:Alice_preInsert",
            "aroundQuery:findAll",
        )
    }
}
