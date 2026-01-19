package io.github.smyrgeorge.sqlx4k.processor

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.containsExactly
import assertk.assertions.isEqualTo
import assertk.assertions.isFailure
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import assertk.assertions.isSuccess
import assertk.assertions.isTrue
import io.github.smyrgeorge.sqlx4k.SQLError
import io.github.smyrgeorge.sqlx4k.processor.util.MockQueryExecutor.Companion.countRow
import io.github.smyrgeorge.sqlx4k.processor.util.MockQueryExecutor.Companion.row
import io.github.smyrgeorge.sqlx4k.processor.util.MockQueryExecutor.Companion.userRow
import io.github.smyrgeorge.sqlx4k.processor.util.User
import io.github.smyrgeorge.sqlx4k.processor.test.generated.UserContextCrudRepositoryImpl
import io.github.smyrgeorge.sqlx4k.processor.util.MockQueryExecutor
import kotlinx.coroutines.test.runTest
import kotlin.test.BeforeTest
import kotlin.test.Test

/**
 * End-to-end tests for generated ContextCrudRepository implementations.
 *
 * These tests verify that the generated repository code using context receivers correctly:
 * - Calls the QueryExecutor with proper SQL statements
 * - Handles successful responses
 * - Handles error conditions
 * - Maps results correctly
 *
 * The key difference from CrudRepository tests is that the QueryExecutor
 * is passed via context receivers instead of explicit parameters.
 */
class ContextCrudRepositoryTests {

    private lateinit var mockExecutor: MockQueryExecutor
    private val repository = UserContextCrudRepositoryImpl

    @BeforeTest
    fun setup() {
        mockExecutor = MockQueryExecutor()
    }

    // ==================== INSERT Tests ====================

    @Test
    fun `insert calls fetchAll with INSERT statement`() = runTest {
        mockExecutor.setFetchAllRows(row("id" to "42"))

        val user = User(id = 0, name = "Alice", email = "alice@example.com")
        with(mockExecutor) {
            repository.insert(user)
        }

        assertThat(mockExecutor.hasExecuted("insert into users")).isTrue()
        assertThat(mockExecutor.hasExecuted("returning id")).isTrue()
    }

    @Test
    fun `insert returns entity with generated id`() = runTest {
        mockExecutor.setFetchAllRows(row("id" to "42"))

        val user = User(id = 0, name = "Alice", email = "alice@example.com")
        val result = with(mockExecutor) {
            repository.insert(user)
        }

        assertThat(result).isSuccess()
        assertThat(result.getOrNull()?.id).isEqualTo(42L)
        assertThat(result.getOrNull()?.name).isEqualTo("Alice")
        assertThat(result.getOrNull()?.email).isEqualTo("alice@example.com")
    }

    @Test
    fun `insert fails when no rows returned`() = runTest {
        mockExecutor.setFetchAllRows() // Empty result

        val user = User(id = 0, name = "Alice", email = "alice@example.com")
        val result = with(mockExecutor) {
            repository.insert(user)
        }

        assertThat(result).isFailure()
        val error = result.exceptionOrNull() as SQLError
        assertThat(error.code).isEqualTo(SQLError.Code.EmptyResultSet)
    }

    @Test
    fun `insert binds name and email parameters`() = runTest {
        mockExecutor.setFetchAllRows(row("id" to "1"))

        val user = User(id = 0, name = "Bob", email = "bob@test.com")
        with(mockExecutor) {
            repository.insert(user)
        }

        val sql = mockExecutor.lastExecutedStatement()
        assertThat(sql).isNotNull()
        assertThat(sql!!).contains("'Bob'")
        assertThat(sql).contains("'bob@test.com'")
    }

    // ==================== UPDATE Tests ====================

    @Test
    fun `update calls fetchAll with UPDATE statement`() = runTest {
        mockExecutor.setFetchAllRows(row("id" to "1"))

        val user = User(id = 1, name = "Alice Updated", email = "alice@example.com")
        with(mockExecutor) {
            repository.update(user)
        }

        assertThat(mockExecutor.hasExecuted("update users")).isTrue()
        assertThat(mockExecutor.hasExecuted("where id")).isTrue()
        assertThat(mockExecutor.hasExecuted("returning id")).isTrue()
    }

    @Test
    fun `update returns updated entity`() = runTest {
        mockExecutor.setFetchAllRows(row("id" to "5"))

        val user = User(id = 5, name = "Updated Name", email = "updated@example.com")
        val result = with(mockExecutor) {
            repository.update(user)
        }

        assertThat(result).isSuccess()
        assertThat(result.getOrNull()?.id).isEqualTo(5L)
    }

    @Test
    fun `update fails when no rows returned`() = runTest {
        mockExecutor.setFetchAllRows() // Empty result

        val user = User(id = 999, name = "Ghost", email = "ghost@example.com")
        val result = with(mockExecutor) {
            repository.update(user)
        }

        assertThat(result).isFailure()
        val error = result.exceptionOrNull() as SQLError
        assertThat(error.code).isEqualTo(SQLError.Code.EmptyResultSet)
    }

    @Test
    fun `update fails when returned id does not match`() = runTest {
        mockExecutor.setFetchAllRows(row("id" to "999")) // Different ID

        val user = User(id = 1, name = "Test", email = "test@example.com")
        val result = with(mockExecutor) {
            repository.update(user)
        }

        assertThat(result).isFailure()
        val error = result.exceptionOrNull() as SQLError
        assertThat(error.code).isEqualTo(SQLError.Code.RowMismatch)
    }

    // ==================== DELETE Tests ====================

    @Test
    fun `delete calls execute with DELETE statement`() = runTest {
        mockExecutor.setExecuteResponse(Result.success(1L))

        val user = User(id = 1, name = "ToDelete", email = "delete@example.com")
        with(mockExecutor) {
            repository.delete(user)
        }

        assertThat(mockExecutor.hasExecuted("delete from users")).isTrue()
        assertThat(mockExecutor.hasExecuted("where id")).isTrue()
    }

    @Test
    fun `delete succeeds when one row affected`() = runTest {
        mockExecutor.setExecuteResponse(Result.success(1L))

        val user = User(id = 1, name = "ToDelete", email = "delete@example.com")
        val result = with(mockExecutor) {
            repository.delete(user)
        }

        assertThat(result).isSuccess()
    }

    @Test
    fun `delete fails when no rows affected`() = runTest {
        mockExecutor.setExecuteResponse(Result.success(0L))

        val user = User(id = 999, name = "NotFound", email = "notfound@example.com")
        val result = with(mockExecutor) {
            repository.delete(user)
        }

        assertThat(result).isFailure()
        val error = result.exceptionOrNull() as SQLError
        assertThat(error.code).isEqualTo(SQLError.Code.EmptyResultSet)
    }

    @Test
    fun `delete fails when multiple rows affected`() = runTest {
        mockExecutor.setExecuteResponse(Result.success(5L)) // More than 1 row

        val user = User(id = 1, name = "Multiple", email = "multiple@example.com")
        val result = with(mockExecutor) {
            repository.delete(user)
        }

        assertThat(result).isFailure()
        val error = result.exceptionOrNull() as SQLError
        assertThat(error.code).isEqualTo(SQLError.Code.RowMismatch)
    }

    // ==================== SAVE Tests ====================

    @Test
    fun `save inserts when id is zero`() = runTest {
        mockExecutor.setFetchAllRows(row("id" to "100"))

        val user = User(id = 0, name = "NewUser", email = "new@example.com")
        val result = with(mockExecutor) {
            repository.save(user)
        }

        assertThat(result).isSuccess()
        assertThat(mockExecutor.hasExecuted("insert into users")).isTrue()
        assertThat(result.getOrNull()?.id).isEqualTo(100L)
    }

    @Test
    fun `save updates when id is non-zero`() = runTest {
        mockExecutor.setFetchAllRows(row("id" to "5"))

        val user = User(id = 5, name = "ExistingUser", email = "existing@example.com")
        val result = with(mockExecutor) {
            repository.save(user)
        }

        assertThat(result).isSuccess()
        assertThat(mockExecutor.hasExecuted("update users")).isTrue()
    }

    // ==================== findOneById Tests ====================

    @Test
    fun `findOneById calls fetchAll with SELECT statement`() = runTest {
        mockExecutor.setFetchAllRows(userRow(1, "Alice", "alice@example.com"))

        with(mockExecutor) {
            repository.findOneById(1)
        }

        assertThat(mockExecutor.hasExecuted("SELECT * FROM users")).isTrue()
        assertThat(mockExecutor.hasExecuted("WHERE id")).isTrue()
    }

    @Test
    fun `findOneById returns entity when found`() = runTest {
        mockExecutor.setFetchAllRows(userRow(42, "Alice", "alice@example.com"))

        val result = with(mockExecutor) {
            repository.findOneById(42)
        }

        assertThat(result).isSuccess()
        assertThat(result.getOrNull()).isNotNull()
        assertThat(result.getOrNull()?.id).isEqualTo(42L)
        assertThat(result.getOrNull()?.name).isEqualTo("Alice")
        assertThat(result.getOrNull()?.email).isEqualTo("alice@example.com")
    }

    @Test
    fun `findOneById returns null when not found`() = runTest {
        mockExecutor.setFetchAllRows() // Empty result

        val result = with(mockExecutor) {
            repository.findOneById(999)
        }

        assertThat(result).isSuccess()
        assertThat(result.getOrNull()).isNull()
    }

    @Test
    fun `findOneById fails when multiple rows returned`() = runTest {
        mockExecutor.setFetchAllRows(
            userRow(1, "Alice", "alice@example.com"),
            userRow(2, "Bob", "bob@example.com")
        )

        val result = with(mockExecutor) {
            repository.findOneById(1)
        }

        assertThat(result).isFailure()
        val error = result.exceptionOrNull() as SQLError
        assertThat(error.code).isEqualTo(SQLError.Code.MultipleRowsReturned)
    }

    // ==================== findOneByEmail Tests ====================

    @Test
    fun `findOneByEmail returns entity when found`() = runTest {
        mockExecutor.setFetchAllRows(userRow(1, "Alice", "alice@example.com"))

        val result = with(mockExecutor) {
            repository.findOneByEmail("alice@example.com")
        }

        assertThat(result).isSuccess()
        assertThat(result.getOrNull()?.email).isEqualTo("alice@example.com")
    }

    @Test
    fun `findOneByEmail binds email parameter`() = runTest {
        mockExecutor.setFetchAllRows()

        with(mockExecutor) {
            repository.findOneByEmail("test@example.com")
        }

        val sql = mockExecutor.lastExecutedStatement()
        assertThat(sql).isNotNull()
        assertThat(sql!!).contains("'test@example.com'")
    }

    // ==================== findAll Tests ====================

    @Test
    fun `findAll returns list of entities`() = runTest {
        mockExecutor.setFetchAllRows(
            userRow(1, "Alice", "alice@example.com"),
            userRow(2, "Bob", "bob@example.com"),
            userRow(3, "Charlie", "charlie@example.com")
        )

        val result = with(mockExecutor) {
            repository.findAll()
        }

        assertThat(result).isSuccess()
        val users = result.getOrThrow()
        assertThat(users.size).isEqualTo(3)
        assertThat(users.map { it.name }).containsExactly("Alice", "Bob", "Charlie")
    }

    @Test
    fun `findAll returns empty list when no data`() = runTest {
        mockExecutor.setFetchAllRows()

        val result = with(mockExecutor) {
            repository.findAll()
        }

        assertThat(result).isSuccess()
        assertThat(result.getOrNull()?.size).isEqualTo(0)
    }

    // ==================== findAllByName Tests ====================

    @Test
    fun `findAllByName returns filtered list`() = runTest {
        mockExecutor.setFetchAllRows(
            userRow(1, "John", "john1@example.com"),
            userRow(5, "John", "john2@example.com")
        )

        val result = with(mockExecutor) {
            repository.findAllByName("John")
        }

        assertThat(result).isSuccess()
        assertThat(result.getOrNull()?.size).isEqualTo(2)
    }

    @Test
    fun `findAllByName binds name parameter`() = runTest {
        mockExecutor.setFetchAllRows()

        with(mockExecutor) {
            repository.findAllByName("TestName")
        }

        val sql = mockExecutor.lastExecutedStatement()
        assertThat(sql).isNotNull()
        assertThat(sql!!).contains("'TestName'")
    }

    // ==================== countAll Tests ====================

    @Test
    fun `countAll returns count value`() = runTest {
        mockExecutor.setFetchAllRows(countRow(42))

        val result = with(mockExecutor) {
            repository.countAll()
        }

        assertThat(result).isSuccess()
        assertThat(result.getOrNull()).isEqualTo(42L)
    }

    @Test
    fun `countAll executes SELECT count query`() = runTest {
        mockExecutor.setFetchAllRows(countRow(0))

        with(mockExecutor) {
            repository.countAll()
        }

        assertThat(mockExecutor.hasExecuted("SELECT count(*)")).isTrue()
        assertThat(mockExecutor.hasExecuted("FROM users")).isTrue()
    }

    // ==================== countByName Tests ====================

    @Test
    fun `countByName returns filtered count`() = runTest {
        mockExecutor.setFetchAllRows(countRow(5))

        val result = with(mockExecutor) {
            repository.countByName("John")
        }

        assertThat(result).isSuccess()
        assertThat(result.getOrNull()).isEqualTo(5L)
    }

    @Test
    fun `countByName binds name parameter`() = runTest {
        mockExecutor.setFetchAllRows(countRow(0))

        with(mockExecutor) {
            repository.countByName("TestName")
        }

        val sql = mockExecutor.lastExecutedStatement()
        assertThat(sql).isNotNull()
        assertThat(sql!!).contains("'TestName'")
    }

    // ==================== deleteByEmail Tests ====================

    @Test
    fun `deleteByEmail calls execute with DELETE statement`() = runTest {
        mockExecutor.setExecuteResponse(Result.success(1L))

        with(mockExecutor) {
            repository.deleteByEmail("test@example.com")
        }

        assertThat(mockExecutor.hasExecuted("DELETE FROM users")).isTrue()
        assertThat(mockExecutor.hasExecuted("WHERE email")).isTrue()
    }

    @Test
    fun `deleteByEmail returns affected row count`() = runTest {
        mockExecutor.setExecuteResponse(Result.success(3L))

        val result = with(mockExecutor) {
            repository.deleteByEmail("duplicate@example.com")
        }

        assertThat(result).isSuccess()
        assertThat(result.getOrNull()).isEqualTo(3L)
    }

    @Test
    fun `deleteByEmail binds email parameter`() = runTest {
        mockExecutor.setExecuteResponse(Result.success(0L))

        with(mockExecutor) {
            repository.deleteByEmail("delete@test.com")
        }

        val sql = mockExecutor.lastExecutedStatement()
        assertThat(sql).isNotNull()
        assertThat(sql!!).contains("'delete@test.com'")
    }

    // ==================== executeUpdateName Tests ====================

    @Test
    fun `executeUpdateName calls execute with UPDATE statement`() = runTest {
        mockExecutor.setExecuteResponse(Result.success(5L))

        with(mockExecutor) {
            repository.executeUpdateName("OldName", "NewName")
        }

        assertThat(mockExecutor.hasExecuted("UPDATE users")).isTrue()
        assertThat(mockExecutor.hasExecuted("SET name")).isTrue()
    }

    @Test
    fun `executeUpdateName returns affected row count`() = runTest {
        mockExecutor.setExecuteResponse(Result.success(10L))

        val result = with(mockExecutor) {
            repository.executeUpdateName("Old", "New")
        }

        assertThat(result).isSuccess()
        assertThat(result.getOrNull()).isEqualTo(10L)
    }

    @Test
    fun `executeUpdateName binds both parameters`() = runTest {
        mockExecutor.setExecuteResponse(Result.success(0L))

        with(mockExecutor) {
            repository.executeUpdateName("FromName", "ToName")
        }

        val sql = mockExecutor.lastExecutedStatement()
        assertThat(sql).isNotNull()
        assertThat(sql!!).contains("'FromName'")
        assertThat(sql).contains("'ToName'")
    }

    // ==================== Error Propagation Tests ====================

    @Test
    fun `repository propagates executor errors on execute`() = runTest {
        val error = SQLError(SQLError.Code.Database, "Connection failed")
        mockExecutor.setExecuteResponse(Result.failure(error))

        val user = User(id = 1, name = "Test", email = "test@example.com")
        val result = with(mockExecutor) {
            repository.delete(user)
        }

        assertThat(result).isFailure()
        assertThat(result.exceptionOrNull()).isEqualTo(error)
    }

    @Test
    fun `repository propagates executor errors on fetchAll`() = runTest {
        val error = SQLError(SQLError.Code.Database, "Query failed")
        mockExecutor.setFetchAllResponse(Result.failure(error))

        val result = with(mockExecutor) {
            repository.findAll()
        }

        assertThat(result).isFailure()
        assertThat(result.exceptionOrNull()).isEqualTo(error)
    }

    // ==================== Context Receiver Pattern Tests ====================

    @Test
    fun `multiple operations can share the same context`() = runTest {
        // First operation: insert
        mockExecutor.setFetchAllRows(row("id" to "1"))
        val user = User(id = 0, name = "Test", email = "test@example.com")

        with(mockExecutor) {
            val insertResult = repository.insert(user)
            assertThat(insertResult).isSuccess()

            // Second operation: find
            mockExecutor.reset()
            mockExecutor.setFetchAllRows(userRow(1, "Test", "test@example.com"))
            val findResult = repository.findOneById(1)
            assertThat(findResult).isSuccess()
        }
    }
}
