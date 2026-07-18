package io.github.smyrgeorge.sqlx4k.processor

import assertk.assertThat
import assertk.assertions.containsExactlyInAnyOrder
import assertk.assertions.hasSize
import assertk.assertions.isEqualTo
import assertk.assertions.isFailure
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import assertk.assertions.isSuccess
import io.github.smyrgeorge.sqlx4k.SQLError
import io.github.smyrgeorge.sqlx4k.processor.test.generated.InMemoryUserContextCrudRepository
import io.github.smyrgeorge.sqlx4k.processor.test.generated.InMemoryUserCrudRepository
import io.github.smyrgeorge.sqlx4k.processor.util.MockQueryExecutor
import io.github.smyrgeorge.sqlx4k.processor.util.User
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFailsWith

/**
 * End-to-end tests for the generated in-memory repository test doubles.
 *
 * A double is generated automatically for every `@Repository` in this test source set (the codegen is
 * registered on `kspJvmTest`), so `InMemoryUserCrudRepository` and friends are available here.
 */
class InMemoryRepositoryTests {

    private lateinit var ctx: MockQueryExecutor
    private lateinit var repo: InMemoryUserCrudRepository

    @BeforeTest
    fun setup() {
        ctx = MockQueryExecutor()
        repo = InMemoryUserCrudRepository()
    }

    // ==================== CRUD ====================

    @Test
    fun `insert assigns an auto-generated id`() = runTest {
        val a = repo.insert(ctx, User(id = 0, name = "Alice", email = "alice@example.com"))
        val b = repo.insert(ctx, User(id = 0, name = "Bob", email = "bob@example.com"))

        assertThat(a).isSuccess()
        assertThat(a.getOrNull()?.id).isEqualTo(1L)
        assertThat(b.getOrNull()?.id).isEqualTo(2L)
        assertThat(repo.findAllStored()).hasSize(2)
    }

    @Test
    fun `update replaces an existing entity`() = runTest {
        val inserted = repo.insert(ctx, User(id = 0, name = "Alice", email = "alice@example.com")).getOrThrow()
        val result = repo.update(ctx, inserted.copy(name = "Alicia"))

        assertThat(result).isSuccess()
        assertThat(result.getOrNull()?.name).isEqualTo("Alicia")
        assertThat(repo.findAllStored().single().name).isEqualTo("Alicia")
    }

    @Test
    fun `update of a missing entity fails`() = runTest {
        val result = repo.update(ctx, User(id = 99, name = "Ghost", email = "ghost@example.com"))
        assertThat(result).isFailure()
    }

    @Test
    fun `delete removes an existing entity`() = runTest {
        val inserted = repo.insert(ctx, User(id = 0, name = "Alice", email = "alice@example.com")).getOrThrow()
        assertThat(repo.delete(ctx, inserted)).isSuccess()
        assertThat(repo.findAllStored()).hasSize(0)
    }

    @Test
    fun `delete of a missing entity fails`() = runTest {
        val result = repo.delete(ctx, User(id = 99, name = "Ghost", email = "ghost@example.com"))
        assertThat(result).isFailure()
    }

    @Test
    fun `save inserts when id is zero and updates otherwise`() = runTest {
        val saved = repo.save(ctx, User(id = 0, name = "Alice", email = "alice@example.com")).getOrThrow()
        assertThat(saved.id).isEqualTo(1L)

        val updated = repo.save(ctx, saved.copy(name = "Alicia")).getOrThrow()
        assertThat(updated.name).isEqualTo("Alicia")
        assertThat(repo.findAllStored()).hasSize(1)
    }

    @Test
    fun `batchInsert assigns ids to every entity`() = runTest {
        val result = repo.batchInsert(
            ctx,
            listOf(
                User(id = 0, name = "Alice", email = "alice@example.com"),
                User(id = 0, name = "Bob", email = "bob@example.com"),
            )
        )
        assertThat(result).isSuccess()
        assertThat(result.getOrThrow().map { it.id }).containsExactlyInAnyOrder(1L, 2L)
    }

    @Test
    fun `batchUpdate updates every entity`() = runTest {
        val a = repo.insert(ctx, User(id = 0, name = "Alice", email = "alice@example.com")).getOrThrow()
        val b = repo.insert(ctx, User(id = 0, name = "Bob", email = "bob@example.com")).getOrThrow()

        val result = repo.batchUpdate(ctx, listOf(a.copy(name = "A"), b.copy(name = "B")))
        assertThat(result).isSuccess()
        assertThat(repo.findAllStored().map { it.name }).containsExactlyInAnyOrder("A", "B")
    }

    // ==================== Derived @Query methods ====================

    @Test
    fun `findAll returns all stored entities`() = runTest {
        repo.insert(ctx, User(id = 0, name = "Alice", email = "alice@example.com"))
        repo.insert(ctx, User(id = 0, name = "Bob", email = "bob@example.com"))
        assertThat(repo.findAll(ctx).getOrThrow()).hasSize(2)
    }

    @Test
    fun `countAll counts stored entities`() = runTest {
        repo.insert(ctx, User(id = 0, name = "Alice", email = "alice@example.com"))
        assertThat(repo.countAll(ctx).getOrThrow()).isEqualTo(1L)
    }

    @Test
    fun `findOneById returns the matching entity or null`() = runTest {
        val a = repo.insert(ctx, User(id = 0, name = "Alice", email = "alice@example.com")).getOrThrow()
        assertThat(repo.findOneById(ctx, a.id).getOrThrow()).isNotNull()
        assertThat(repo.findOneById(ctx, 999L).getOrThrow()).isNull()
    }

    @Test
    fun `findOneByEmail returns the matching entity`() = runTest {
        repo.insert(ctx, User(id = 0, name = "Alice", email = "alice@example.com"))
        val found = repo.findOneByEmail(ctx, "alice@example.com").getOrThrow()
        assertThat(found?.name).isEqualTo("Alice")
    }

    @Test
    fun `findAllByName filters by the name property`() = runTest {
        repo.insert(ctx, User(id = 0, name = "Alice", email = "a@example.com"))
        repo.insert(ctx, User(id = 0, name = "Alice", email = "b@example.com"))
        repo.insert(ctx, User(id = 0, name = "Bob", email = "c@example.com"))
        assertThat(repo.findAllByName(ctx, "Alice").getOrThrow()).hasSize(2)
    }

    @Test
    fun `countByName counts matching entities`() = runTest {
        repo.insert(ctx, User(id = 0, name = "Alice", email = "a@example.com"))
        repo.insert(ctx, User(id = 0, name = "Alice", email = "b@example.com"))
        assertThat(repo.countByName(ctx, "Alice").getOrThrow()).isEqualTo(2L)
    }

    @Test
    fun `deleteByEmail removes matching entities`() = runTest {
        repo.insert(ctx, User(id = 0, name = "Alice", email = "alice@example.com"))
        repo.insert(ctx, User(id = 0, name = "Bob", email = "bob@example.com"))
        assertThat(repo.deleteByEmail(ctx, "alice@example.com").getOrThrow()).isEqualTo(1L)
        assertThat(repo.findAllStored()).hasSize(1)
    }

    // ==================== Predicates derived from the @Query SQL WHERE clause ====================

    @Test
    fun `findAllByEmailNotNull derives an IS NOT NULL predicate from the SQL`() = runTest {
        repo.insert(ctx, User(id = 0, name = "Alice", email = "a@example.com"))
        repo.insert(ctx, User(id = 0, name = "Bob", email = "b@example.com"))
        // WHERE email IS NOT NULL -> it.email != null (email is non-null, so all rows match).
        assertThat(repo.findAllByEmailNotNull(ctx).getOrThrow()).hasSize(2)
    }

    @Test
    fun `executeUpdateName applies the parsed UPDATE SET and WHERE`() = runTest {
        repo.insert(ctx, User(id = 0, name = "Alice", email = "a@example.com"))
        repo.insert(ctx, User(id = 0, name = "Alice", email = "b@example.com"))
        repo.insert(ctx, User(id = 0, name = "Bob", email = "c@example.com"))

        // UPDATE users SET name = :newName WHERE name = :oldName
        val affected = repo.executeUpdateName(ctx, "Alice", "Alicia").getOrThrow()
        assertThat(affected).isEqualTo(2L)
        assertThat(repo.findAllByName(ctx, "Alicia").getOrThrow()).hasSize(2)
        assertThat(repo.findAllByName(ctx, "Alice").getOrThrow()).hasSize(0)
    }

    // ==================== Non-translatable WHERE -> overridable stubs ====================

    @Test
    fun `queries with an unsupported WHERE clause become throwing stubs`() = runTest {
        repo.insert(ctx, User(id = 0, name = "Alice", email = "alice@example.com"))
        // 'WHERE name LIKE :pattern' cannot be translated to an in-memory predicate.
        assertFailsWith<NotImplementedError> { repo.findAllByNameLike(ctx, "A%") }
    }

    @Test
    fun `stubbed methods can be overridden in a subclass`() = runTest {
        val overridden = object : InMemoryUserCrudRepository() {
            override suspend fun findAllByNameLike(
                context: io.github.smyrgeorge.sqlx4k.QueryExecutor,
                pattern: String,
            ): Result<List<User>> = withStore {
                val prefix = pattern.removeSuffix("%")
                runCatching { values.filter { it.name.startsWith(prefix) } }
            }
        }
        overridden.insert(ctx, User(id = 0, name = "Alice", email = "alice@example.com"))
        overridden.insert(ctx, User(id = 0, name = "Bob", email = "bob@example.com"))
        assertThat(overridden.findAllByNameLike(ctx, "Al%").getOrThrow()).hasSize(1)
    }

    // ==================== clear() ====================

    @Test
    fun `clear resets the store and the id sequence`() = runTest {
        repo.insert(ctx, User(id = 0, name = "Alice", email = "alice@example.com"))
        repo.clear()
        assertThat(repo.findAllStored()).hasSize(0)
        // Sequence reset -> next insert starts again from 1.
        assertThat(repo.insert(ctx, User(id = 0, name = "Bob", email = "bob@example.com")).getOrThrow().id)
            .isEqualTo(1L)
    }

    // ==================== Context-parameter style ====================

    @Test
    fun `context-parameter repository works via with(context)`() = runTest {
        val cRepo = InMemoryUserContextCrudRepository()
        with(ctx) {
            val inserted = cRepo.insert(User(id = 0, name = "Alice", email = "alice@example.com")).getOrThrow()
            assertThat(inserted.id).isEqualTo(1L)
            assertThat(cRepo.findAllByName("Alice").getOrThrow()).hasSize(1)
            assertThat(cRepo.findOneById(inserted.id).getOrThrow()).isNotNull()
        }
    }

    // ==================== Thread-safety ====================

    @Test
    fun `concurrent inserts are serialized by the mutex`() = runBlocking(Dispatchers.Default) {
        val local = InMemoryUserCrudRepository()
        val count = 500
        (1..count).map { i ->
            async { local.insert(MockQueryExecutor(), User(id = 0, name = "u$i", email = "u$i@example.com")) }
        }.awaitAll()

        val stored = local.findAllStored()
        assertThat(stored).hasSize(count)
        // Every generated id is unique -> no lost updates under contention.
        assertThat(stored.map { it.id }.toSet()).hasSize(count)
    }
}
