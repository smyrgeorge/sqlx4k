package io.github.smyrgeorge.sqlx4k.processor

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.containsExactly
import assertk.assertions.isEqualTo
import assertk.assertions.isSuccess
import assertk.assertions.isTrue
import io.github.smyrgeorge.sqlx4k.processor.util.HooksTracker
import io.github.smyrgeorge.sqlx4k.processor.util.MockQueryExecutor
import io.github.smyrgeorge.sqlx4k.processor.util.MockQueryExecutor.Companion.row
import io.github.smyrgeorge.sqlx4k.processor.util.MockQueryExecutor.Companion.userRow
import io.github.smyrgeorge.sqlx4k.processor.util.User
import io.github.smyrgeorge.sqlx4k.processor.test.generated.UserRepositoryWithHooksImpl
import kotlinx.coroutines.test.runTest
import kotlin.test.BeforeTest
import kotlin.test.Test

/**
 * Comprehensive tests for CrudRepository hooks functionality.
 *
 * These tests verify that all supported hooks work correctly:
 * - preInsertHook: Called before entity insertion, can modify the entity
 * - afterInsertHook: Called after entity insertion, can modify the result
 * - preUpdateHook: Called before entity update, can modify the entity
 * - afterUpdateHook: Called after entity update, can modify the result
 * - preDeleteHook: Called before entity deletion, can modify the entity
 * - afterDeleteHook: Called after entity deletion
 * - aroundQuery: Wraps query execution for cross-cutting concerns
 */
class CrudRepositoryHooksTests {

    private lateinit var mockExecutor: MockQueryExecutor
    private val repository = UserRepositoryWithHooksImpl

    @BeforeTest
    fun setup() {
        mockExecutor = MockQueryExecutor()
        HooksTracker.reset()
    }

    // ==================== preInsertHook Tests ====================

    @Test
    fun `preInsertHook is called before insert`() = runTest {
        mockExecutor.setFetchAllRows(row("id" to "1"))

        val user = User(id = 0, name = "Alice", email = "alice@example.com")
        repository.insert(mockExecutor, user)

        assertThat(HooksTracker.hooksCalled).contains("preInsert:Alice")
    }

    @Test
    fun `preInsertHook can modify entity before insert`() = runTest {
        mockExecutor.setFetchAllRows(row("id" to "1"))

        val user = User(id = 0, name = "Alice", email = "alice@example.com")
        repository.insert(mockExecutor, user)

        // Hook modifies name to add "_preInsert" suffix
        val sql = mockExecutor.lastExecutedStatement()
        assertThat(sql!!).contains("'Alice_preInsert'")
    }

    @Test
    fun `preInsertHook is called in correct order with afterInsertHook`() = runTest {
        mockExecutor.setFetchAllRows(row("id" to "1"))

        val user = User(id = 0, name = "Alice", email = "alice@example.com")
        repository.insert(mockExecutor, user)

        // preInsert should be called before afterInsert
        val preInsertIndex = HooksTracker.hooksCalled.indexOf("preInsert:Alice")
        val afterInsertIndex = HooksTracker.hooksCalled.indexOfFirst { it.startsWith("afterInsert:") }
        assertThat(preInsertIndex < afterInsertIndex).isTrue()
    }

    // ==================== afterInsertHook Tests ====================

    @Test
    fun `afterInsertHook is called after insert`() = runTest {
        mockExecutor.setFetchAllRows(row("id" to "1"))

        val user = User(id = 0, name = "Alice", email = "alice@example.com")
        repository.insert(mockExecutor, user)

        assertThat(HooksTracker.hooksCalled.any { it.startsWith("afterInsert:") }).isTrue()
    }

    @Test
    fun `afterInsertHook can modify entity after insert`() = runTest {
        mockExecutor.setFetchAllRows(row("id" to "1"))

        val user = User(id = 0, name = "Alice", email = "alice@example.com")
        val result = repository.insert(mockExecutor, user)

        assertThat(result).isSuccess()
        // Hook modifies name: Alice -> Alice_preInsert (in preInsert) -> Alice_preInsert_afterInsert (in afterInsert)
        assertThat(result.getOrNull()?.name).isEqualTo("Alice_preInsert_afterInsert")
    }

    @Test
    fun `afterInsertHook receives entity with generated id`() = runTest {
        mockExecutor.setFetchAllRows(row("id" to "42"))

        val user = User(id = 0, name = "Alice", email = "alice@example.com")
        val result = repository.insert(mockExecutor, user)

        assertThat(result).isSuccess()
        // afterInsertHook should have received entity with id=42
        assertThat(result.getOrNull()?.id).isEqualTo(42L)
    }

    // ==================== preUpdateHook Tests ====================

    @Test
    fun `preUpdateHook is called before update`() = runTest {
        mockExecutor.setFetchAllRows(row("id" to "1"))

        val user = User(id = 1, name = "Alice", email = "alice@example.com")
        repository.update(mockExecutor, user)

        assertThat(HooksTracker.hooksCalled).contains("preUpdate:Alice")
    }

    @Test
    fun `preUpdateHook can modify entity before update`() = runTest {
        mockExecutor.setFetchAllRows(row("id" to "1"))

        val user = User(id = 1, name = "Alice", email = "alice@example.com")
        repository.update(mockExecutor, user)

        // Hook modifies name to add "_preUpdate" suffix
        val sql = mockExecutor.lastExecutedStatement()
        assertThat(sql!!).contains("'Alice_preUpdate'")
    }

    @Test
    fun `preUpdateHook is called in correct order with afterUpdateHook`() = runTest {
        mockExecutor.setFetchAllRows(row("id" to "1"))

        val user = User(id = 1, name = "Alice", email = "alice@example.com")
        repository.update(mockExecutor, user)

        // preUpdate should be called before afterUpdate
        val preUpdateIndex = HooksTracker.hooksCalled.indexOf("preUpdate:Alice")
        val afterUpdateIndex = HooksTracker.hooksCalled.indexOfFirst { it.startsWith("afterUpdate:") }
        assertThat(preUpdateIndex < afterUpdateIndex).isTrue()
    }

    // ==================== afterUpdateHook Tests ====================

    @Test
    fun `afterUpdateHook is called after update`() = runTest {
        mockExecutor.setFetchAllRows(row("id" to "1"))

        val user = User(id = 1, name = "Alice", email = "alice@example.com")
        repository.update(mockExecutor, user)

        assertThat(HooksTracker.hooksCalled.any { it.startsWith("afterUpdate:") }).isTrue()
    }

    @Test
    fun `afterUpdateHook can modify entity after update`() = runTest {
        mockExecutor.setFetchAllRows(row("id" to "1"))

        val user = User(id = 1, name = "Alice", email = "alice@example.com")
        val result = repository.update(mockExecutor, user)

        assertThat(result).isSuccess()
        // Hook modifies name: Alice -> Alice_preUpdate (in preUpdate) -> Alice_preUpdate_afterUpdate (in afterUpdate)
        assertThat(result.getOrNull()?.name).isEqualTo("Alice_preUpdate_afterUpdate")
    }

    // ==================== preDeleteHook Tests ====================

    @Test
    fun `preDeleteHook is called before delete`() = runTest {
        mockExecutor.setExecuteResponse(Result.success(1L))

        val user = User(id = 1, name = "Alice", email = "alice@example.com")
        repository.delete(mockExecutor, user)

        assertThat(HooksTracker.hooksCalled).contains("preDelete:Alice")
    }

    @Test
    fun `preDeleteHook can access entity before delete`() = runTest {
        mockExecutor.setExecuteResponse(Result.success(1L))

        val user = User(id = 1, name = "Alice", email = "alice@example.com")
        repository.delete(mockExecutor, user)

        // Hook is called with original entity
        assertThat(HooksTracker.hooksCalled).contains("preDelete:Alice")
    }

    @Test
    fun `preDeleteHook is called in correct order with afterDeleteHook`() = runTest {
        mockExecutor.setExecuteResponse(Result.success(1L))

        val user = User(id = 1, name = "Alice", email = "alice@example.com")
        repository.delete(mockExecutor, user)

        // preDelete should be called before afterDelete
        val preDeleteIndex = HooksTracker.hooksCalled.indexOf("preDelete:Alice")
        val afterDeleteIndex = HooksTracker.hooksCalled.indexOf("afterDelete:Alice")
        assertThat(preDeleteIndex < afterDeleteIndex).isTrue()
    }

    // ==================== afterDeleteHook Tests ====================

    @Test
    fun `afterDeleteHook is called after delete`() = runTest {
        mockExecutor.setExecuteResponse(Result.success(1L))

        val user = User(id = 1, name = "Alice", email = "alice@example.com")
        repository.delete(mockExecutor, user)

        assertThat(HooksTracker.hooksCalled).contains("afterDelete:Alice")
    }

    @Test
    fun `afterDeleteHook receives original entity`() = runTest {
        mockExecutor.setExecuteResponse(Result.success(1L))

        val user = User(id = 1, name = "Alice", email = "alice@example.com")
        repository.delete(mockExecutor, user)

        // Hook receives the original entity (preDeleteHook doesn't modify it in our test)
        assertThat(HooksTracker.hooksCalled).contains("afterDelete:Alice")
    }

    // ==================== aroundQuery Hook Tests ====================

    @Test
    fun `aroundQuery hook is called for insert`() = runTest {
        mockExecutor.setFetchAllRows(row("id" to "1"))

        val user = User(id = 0, name = "Alice", email = "alice@example.com")
        repository.insert(mockExecutor, user)

        assertThat(HooksTracker.hooksCalled).contains("aroundQuery:insert")
    }

    @Test
    fun `aroundQuery hook is called for update`() = runTest {
        mockExecutor.setFetchAllRows(row("id" to "1"))

        val user = User(id = 1, name = "Alice", email = "alice@example.com")
        repository.update(mockExecutor, user)

        assertThat(HooksTracker.hooksCalled).contains("aroundQuery:update")
    }

    @Test
    fun `aroundQuery hook is called for delete`() = runTest {
        mockExecutor.setExecuteResponse(Result.success(1L))

        val user = User(id = 1, name = "Alice", email = "alice@example.com")
        repository.delete(mockExecutor, user)

        assertThat(HooksTracker.hooksCalled).contains("aroundQuery:delete")
    }

    @Test
    fun `aroundQuery hook is called for custom query methods`() = runTest {
        mockExecutor.setFetchAllRows(userRow(1, "Alice", "alice@example.com"))

        repository.findOneById(mockExecutor, 1)

        assertThat(HooksTracker.hooksCalled).contains("aroundQuery:findOneById")
    }

    @Test
    fun `aroundQuery hook is called for findAll query`() = runTest {
        mockExecutor.setFetchAllRows(
            userRow(1, "Alice", "alice@example.com"),
            userRow(2, "Bob", "bob@example.com")
        )

        repository.findAll(mockExecutor)

        assertThat(HooksTracker.hooksCalled).contains("aroundQuery:findAll")
    }

    @Test
    fun `aroundQuery hook receives correct method name`() = runTest {
        mockExecutor.setFetchAllRows(userRow(1, "Alice", "alice@example.com"))

        repository.findOneById(mockExecutor, 1)

        // Check that the hook was called with the exact method name
        val aroundQueryCalls = HooksTracker.hooksCalled.filter { it.startsWith("aroundQuery:") }
        assertThat(aroundQueryCalls).containsExactly("aroundQuery:findOneById")
    }

    // ==================== Save Method Hook Integration Tests ====================

    @Test
    fun `save calls insert hooks when id is zero`() = runTest {
        mockExecutor.setFetchAllRows(row("id" to "1"))

        val user = User(id = 0, name = "Alice", email = "alice@example.com")
        repository.save(mockExecutor, user)

        assertThat(HooksTracker.hooksCalled).contains("preInsert:Alice")
        assertThat(HooksTracker.hooksCalled.any { it.startsWith("afterInsert:") }).isTrue()
        assertThat(HooksTracker.hooksCalled).contains("aroundQuery:insert")
    }

    @Test
    fun `save calls update hooks when id is non-zero`() = runTest {
        mockExecutor.setFetchAllRows(row("id" to "5"))

        val user = User(id = 5, name = "Alice", email = "alice@example.com")
        repository.save(mockExecutor, user)

        assertThat(HooksTracker.hooksCalled).contains("preUpdate:Alice")
        assertThat(HooksTracker.hooksCalled.any { it.startsWith("afterUpdate:") }).isTrue()
        assertThat(HooksTracker.hooksCalled).contains("aroundQuery:update")
    }

    // ==================== Hook Execution Order Tests ====================

    @Test
    fun `insert hooks are called in correct order`() = runTest {
        mockExecutor.setFetchAllRows(row("id" to "1"))

        val user = User(id = 0, name = "Alice", email = "alice@example.com")
        repository.insert(mockExecutor, user)

        // Expected order: preInsert -> aroundQuery -> afterInsert
        val expectedOrder = listOf("preInsert:Alice", "aroundQuery:insert")
        val actualOrder = HooksTracker.hooksCalled.filter {
            it == "preInsert:Alice" || it == "aroundQuery:insert"
        }
        assertThat(actualOrder).isEqualTo(expectedOrder)

        // afterInsert should be last
        assertThat(HooksTracker.hooksCalled.last().startsWith("afterInsert:")).isTrue()
    }

    @Test
    fun `update hooks are called in correct order`() = runTest {
        mockExecutor.setFetchAllRows(row("id" to "1"))

        val user = User(id = 1, name = "Alice", email = "alice@example.com")
        repository.update(mockExecutor, user)

        // Expected order: preUpdate -> aroundQuery -> afterUpdate
        val expectedOrder = listOf("preUpdate:Alice", "aroundQuery:update")
        val actualOrder = HooksTracker.hooksCalled.filter {
            it == "preUpdate:Alice" || it == "aroundQuery:update"
        }
        assertThat(actualOrder).isEqualTo(expectedOrder)

        // afterUpdate should be last
        assertThat(HooksTracker.hooksCalled.last().startsWith("afterUpdate:")).isTrue()
    }

    @Test
    fun `delete hooks are called in correct order`() = runTest {
        mockExecutor.setExecuteResponse(Result.success(1L))

        val user = User(id = 1, name = "Alice", email = "alice@example.com")
        repository.delete(mockExecutor, user)

        // Expected order: preDelete -> aroundQuery -> afterDelete
        assertThat(HooksTracker.hooksCalled).containsExactly(
            "preDelete:Alice",
            "aroundQuery:delete",
            "afterDelete:Alice"
        )
    }

    // ==================== Multiple Operations Test ====================

    @Test
    fun `hooks are called correctly for multiple operations in sequence`() = runTest {
        HooksTracker.reset()

        // Insert
        mockExecutor.setFetchAllRows(row("id" to "1"))
        val user1 = User(id = 0, name = "Alice", email = "alice@example.com")
        repository.insert(mockExecutor, user1)

        // Update
        mockExecutor.reset()
        mockExecutor.setFetchAllRows(row("id" to "1"))
        val user2 = User(id = 1, name = "Alice", email = "alice@example.com")
        repository.update(mockExecutor, user2)

        // Delete
        mockExecutor.reset()
        mockExecutor.setExecuteResponse(Result.success(1L))
        repository.delete(mockExecutor, user2)

        // Verify all hooks were called
        assertThat(HooksTracker.hooksCalled).contains("preInsert:Alice")
        assertThat(HooksTracker.hooksCalled).contains("preUpdate:Alice")
        assertThat(HooksTracker.hooksCalled).contains("preDelete:Alice")
        assertThat(HooksTracker.hooksCalled.any { it.startsWith("afterInsert:") }).isTrue()
        assertThat(HooksTracker.hooksCalled.any { it.startsWith("afterUpdate:") }).isTrue()
        assertThat(HooksTracker.hooksCalled).contains("afterDelete:Alice")
    }
}
