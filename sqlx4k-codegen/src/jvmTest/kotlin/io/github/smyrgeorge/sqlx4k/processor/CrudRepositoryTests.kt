package io.github.smyrgeorge.sqlx4k.processor

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.containsExactly
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isFailure
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import assertk.assertions.isSuccess
import assertk.assertions.isTrue
import io.github.smyrgeorge.sqlx4k.QueryExecutor
import io.github.smyrgeorge.sqlx4k.SQLError
import io.github.smyrgeorge.sqlx4k.processor.test.generated.UserCrudRepositoryImpl
import io.github.smyrgeorge.sqlx4k.processor.util.MockQueryExecutor
import io.github.smyrgeorge.sqlx4k.processor.util.MockQueryExecutor.Companion.countRow
import io.github.smyrgeorge.sqlx4k.processor.util.MockQueryExecutor.Companion.row
import io.github.smyrgeorge.sqlx4k.processor.util.MockQueryExecutor.Companion.userRow
import io.github.smyrgeorge.sqlx4k.processor.util.User
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest

/**
 * End-to-end tests for generated CrudRepository implementations.
 *
 * These tests verify that the generated repository code correctly:
 * - Calls the QueryExecutor with proper SQL statements
 * - Handles successful responses
 * - Handles error conditions
 * - Maps results correctly
 */
class CrudRepositoryTests {

    private lateinit var mockExecutor: MockQueryExecutor
    private val repository = UserCrudRepositoryImpl

    @BeforeTest
    fun setup() {
        mockExecutor = MockQueryExecutor()
    }

    // ==================== INSERT Tests ====================

    @Test
    fun `insert calls fetchAll with INSERT statement`() = runTest {
        mockExecutor.setFetchAllRows(row("id" to "42"))

        val user = User(id = 0, name = "Alice", email = "alice@example.com")
        repository.insert(mockExecutor, user)

        assertThat(mockExecutor.hasExecuted("insert into users")).isTrue()
        assertThat(mockExecutor.hasExecuted("returning id")).isTrue()
    }

    @Test
    fun `insert returns entity with generated id`() = runTest {
        mockExecutor.setFetchAllRows(row("id" to "42"))

        val user = User(id = 0, name = "Alice", email = "alice@example.com")
        val result = repository.insert(mockExecutor, user)

        assertThat(result).isSuccess()
        assertThat(result.getOrNull()?.id).isEqualTo(42L)
        assertThat(result.getOrNull()?.name).isEqualTo("Alice")
        assertThat(result.getOrNull()?.email).isEqualTo("alice@example.com")
    }

    @Test
    fun `insert fails when no rows returned`() = runTest {
        mockExecutor.setFetchAllRows() // Empty result

        val user = User(id = 0, name = "Alice", email = "alice@example.com")
        val result = repository.insert(mockExecutor, user)

        assertThat(result).isFailure()
        val error = result.exceptionOrNull() as SQLError
        assertThat(error.code).isEqualTo(SQLError.Code.EmptyResultSet)
    }

    @Test
    fun `insert binds name and email parameters`() = runTest {
        mockExecutor.setFetchAllRows(row("id" to "1"))

        val user = User(id = 0, name = "Bob", email = "bob@test.com")
        repository.insert(mockExecutor, user)

        val sql = mockExecutor.lastExecutedStatement()
        assertThat(sql).isNotNull()
        assertThat(mockExecutor.lastBoundValues).contains("Bob")
        assertThat(mockExecutor.lastBoundValues).contains("bob@test.com")
    }

    // ==================== UPDATE Tests ====================

    @Test
    fun `update calls fetchAll with UPDATE statement`() = runTest {
        mockExecutor.setFetchAllRows(row("id" to "1"))

        val user = User(id = 1, name = "Alice Updated", email = "alice@example.com")
        repository.update(mockExecutor, user)

        assertThat(mockExecutor.hasExecuted("update users")).isTrue()
        assertThat(mockExecutor.hasExecuted("where id")).isTrue()
        assertThat(mockExecutor.hasExecuted("returning id")).isTrue()
    }

    @Test
    fun `update returns updated entity`() = runTest {
        mockExecutor.setFetchAllRows(row("id" to "5"))

        val user = User(id = 5, name = "Updated Name", email = "updated@example.com")
        val result = repository.update(mockExecutor, user)

        assertThat(result).isSuccess()
        assertThat(result.getOrNull()?.id).isEqualTo(5L)
    }

    @Test
    fun `update fails when no rows returned`() = runTest {
        mockExecutor.setFetchAllRows() // Empty result

        val user = User(id = 999, name = "Ghost", email = "ghost@example.com")
        val result = repository.update(mockExecutor, user)

        assertThat(result).isFailure()
        val error = result.exceptionOrNull() as SQLError
        assertThat(error.code).isEqualTo(SQLError.Code.EmptyResultSet)
    }

    @Test
    fun `update fails when returned id does not match`() = runTest {
        mockExecutor.setFetchAllRows(row("id" to "999")) // Different ID

        val user = User(id = 1, name = "Test", email = "test@example.com")
        val result = repository.update(mockExecutor, user)

        assertThat(result).isFailure()
        val error = result.exceptionOrNull() as SQLError
        assertThat(error.code).isEqualTo(SQLError.Code.RowMismatch)
    }

    // ==================== DELETE Tests ====================

    @Test
    fun `delete calls execute with DELETE statement`() = runTest {
        mockExecutor.setExecuteResponse(Result.success(1L))

        val user = User(id = 1, name = "ToDelete", email = "delete@example.com")
        repository.delete(mockExecutor, user)

        assertThat(mockExecutor.hasExecuted("delete from users")).isTrue()
        assertThat(mockExecutor.hasExecuted("where id")).isTrue()
    }

    @Test
    fun `delete succeeds when one row affected`() = runTest {
        mockExecutor.setExecuteResponse(Result.success(1L))

        val user = User(id = 1, name = "ToDelete", email = "delete@example.com")
        val result = repository.delete(mockExecutor, user)

        assertThat(result).isSuccess()
    }

    @Test
    fun `delete fails when no rows affected`() = runTest {
        mockExecutor.setExecuteResponse(Result.success(0L))

        val user = User(id = 999, name = "NotFound", email = "notfound@example.com")
        val result = repository.delete(mockExecutor, user)

        assertThat(result).isFailure()
        val error = result.exceptionOrNull() as SQLError
        assertThat(error.code).isEqualTo(SQLError.Code.EmptyResultSet)
    }

    @Test
    fun `delete fails when multiple rows affected`() = runTest {
        mockExecutor.setExecuteResponse(Result.success(5L)) // More than 1 row

        val user = User(id = 1, name = "Multiple", email = "multiple@example.com")
        val result = repository.delete(mockExecutor, user)

        assertThat(result).isFailure()
        val error = result.exceptionOrNull() as SQLError
        assertThat(error.code).isEqualTo(SQLError.Code.RowMismatch)
    }

    // ==================== BATCH Tests ====================

    private val users = listOf(
        User(id = 1, name = "A", email = "a@example.com"),
        User(id = 2, name = "B", email = "b@example.com"),
    )

    @Test
    fun `batchInsert executes one INSERT and returns the number of inserted rows`() = runTest {
        mockExecutor.setExecuteResponse(Result.success(2L))

        val result = repository.batchInsert(mockExecutor, users)

        assertThat(result).isSuccess()
        assertThat(result.getOrThrow()).isEqualTo(2L)
        assertThat(mockExecutor.executeCalls.size).isEqualTo(1)
        assertThat(mockExecutor.hasExecuted("insert into users(name, email) values")).isTrue()
        assertThat(mockExecutor.fetchAllCalls).isEmpty()
        assertThat(mockExecutor.lastBoundValues).containsExactly("A", "a@example.com", "B", "b@example.com")
    }

    @Test
    fun `batchInsert fails with RowMismatch when the affected rows differ from the entities`() = runTest {
        mockExecutor.setExecuteResponse(Result.success(1L))

        val result = repository.batchInsert(mockExecutor, users)

        assertThat(result).isFailure()
        val error = result.exceptionOrNull() as SQLError
        assertThat(error.code).isEqualTo(SQLError.Code.RowMismatch)
        assertThat(error.message!!).contains("affected 1 rows instead of 2")
    }

    @Test
    fun `batchInsert of an empty collection succeeds without touching the executor`() = runTest {
        val result = repository.batchInsert(mockExecutor, emptyList())

        assertThat(result.getOrThrow()).isEqualTo(0L)
        assertThat(mockExecutor.executedStatements).isEmpty()
    }

    @Test
    fun `batchUpdate executes one UPDATE and returns the number of updated rows`() = runTest {
        mockExecutor.setExecuteResponse(Result.success(2L))

        val result = repository.batchUpdate(mockExecutor, users)

        assertThat(result).isSuccess()
        assertThat(result.getOrThrow()).isEqualTo(2L)
        assertThat(mockExecutor.executeCalls.size).isEqualTo(1)
        assertThat(mockExecutor.hasExecuted("update users")).isTrue()
        assertThat(mockExecutor.fetchAllCalls).isEmpty()
        assertThat(mockExecutor.lastBoundValues).containsExactly(1L, "A", "a@example.com", 2L, "B", "b@example.com")
    }

    @Test
    fun `batchUpdate fails with RowMismatch when the affected rows differ from the entities`() = runTest {
        mockExecutor.setExecuteResponse(Result.success(1L)) // e.g. an entity whose row does not exist

        val result = repository.batchUpdate(mockExecutor, users)

        assertThat(result).isFailure()
        val error = result.exceptionOrNull() as SQLError
        assertThat(error.code).isEqualTo(SQLError.Code.RowMismatch)
        assertThat(error.message!!).contains("affected 1 rows instead of 2")
    }

    @Test
    fun `batchUpdate propagates an execute failure`() = runTest {
        mockExecutor.setExecuteResponse(Result.failure(SQLError(SQLError.Code.Database, "boom")))

        val result = repository.batchUpdate(mockExecutor, users)

        assertThat(result).isFailure()
        assertThat((result.exceptionOrNull() as SQLError).message!!).contains("boom")
    }

    // ==================== SAVE Tests ====================

    @Test
    fun `save inserts when id is zero`() = runTest {
        mockExecutor.setFetchAllRows(row("id" to "100"))

        val user = User(id = 0, name = "NewUser", email = "new@example.com")
        val result = repository.save(mockExecutor, user)

        assertThat(result).isSuccess()
        assertThat(mockExecutor.hasExecuted("insert into users")).isTrue()
        assertThat(result.getOrNull()?.id).isEqualTo(100L)
    }

    @Test
    fun `save updates when id is non-zero`() = runTest {
        mockExecutor.setFetchAllRows(row("id" to "5"))

        val user = User(id = 5, name = "ExistingUser", email = "existing@example.com")
        val result = repository.save(mockExecutor, user)

        assertThat(result).isSuccess()
        assertThat(mockExecutor.hasExecuted("update users")).isTrue()
    }

    // ==================== existsBy Tests ====================

    @Test
    fun `existsByEmail wraps the query in SELECT EXISTS and returns a boolean`() = runTest {
        // No rows configured -> firstOrNull() is null -> false.
        val absent = repository.existsByEmail(mockExecutor, "nobody@example.com")
        assertThat(absent).isSuccess()
        assertThat(absent.getOrNull()).isEqualTo(false)
        assertThat(mockExecutor.hasExecuted("exists(select 1 from users")).isTrue()
        assertThat(mockExecutor.hasExecuted("where email")).isTrue()

        // A row whose first column is truthy -> true.
        mockExecutor.setFetchAllRows(row("exists" to "true"))
        val present = repository.existsByEmail(mockExecutor, "alice@example.com")
        assertThat(present).isSuccess()
        assertThat(present.getOrNull()).isEqualTo(true)
    }

    // ==================== findOneById Tests ====================

    @Test
    fun `findOneById calls fetchAll with SELECT statement`() = runTest {
        mockExecutor.setFetchAllRows(userRow(1, "Alice", "alice@example.com"))

        repository.findOneById(mockExecutor, 1)

        assertThat(mockExecutor.hasExecuted("select id, name, email from users")).isTrue()
        assertThat(mockExecutor.hasExecuted("WHERE id")).isTrue()
    }

    @Test
    fun `findOneById returns entity when found`() = runTest {
        mockExecutor.setFetchAllRows(userRow(42, "Alice", "alice@example.com"))

        val result = repository.findOneById(mockExecutor, 42)

        assertThat(result).isSuccess()
        assertThat(result.getOrNull()).isNotNull()
        assertThat(result.getOrNull()?.id).isEqualTo(42L)
        assertThat(result.getOrNull()?.name).isEqualTo("Alice")
        assertThat(result.getOrNull()?.email).isEqualTo("alice@example.com")
    }

    @Test
    fun `findOneById returns null when not found`() = runTest {
        mockExecutor.setFetchAllRows() // Empty result

        val result = repository.findOneById(mockExecutor, 999)

        assertThat(result).isSuccess()
        assertThat(result.getOrNull()).isNull()
    }

    @Test
    fun `findOneById fails when multiple rows returned`() = runTest {
        mockExecutor.setFetchAllRows(
            userRow(1, "Alice", "alice@example.com"),
            userRow(2, "Bob", "bob@example.com")
        )

        val result = repository.findOneById(mockExecutor, 1)

        assertThat(result).isFailure()
        val error = result.exceptionOrNull() as SQLError
        assertThat(error.code).isEqualTo(SQLError.Code.MultipleRowsReturned)
    }

    // ==================== findOneByEmail Tests ====================

    @Test
    fun `findOneByEmail returns entity when found`() = runTest {
        mockExecutor.setFetchAllRows(userRow(1, "Alice", "alice@example.com"))

        val result = repository.findOneByEmail(mockExecutor, "alice@example.com")

        assertThat(result).isSuccess()
        assertThat(result.getOrNull()?.email).isEqualTo("alice@example.com")
    }

    @Test
    fun `findOneByEmail binds email parameter`() = runTest {
        mockExecutor.setFetchAllRows()

        repository.findOneByEmail(mockExecutor, "test@example.com")

        val sql = mockExecutor.lastExecutedStatement()
        assertThat(sql).isNotNull()
        assertThat(mockExecutor.lastBoundValues).contains("test@example.com")
    }

    // ==================== findAll Tests ====================

    @Test
    fun `findAll returns list of entities`() = runTest {
        mockExecutor.setFetchAllRows(
            userRow(1, "Alice", "alice@example.com"),
            userRow(2, "Bob", "bob@example.com"),
            userRow(3, "Charlie", "charlie@example.com")
        )

        val result = repository.findAll(mockExecutor)

        assertThat(result).isSuccess()
        val users = result.getOrThrow()
        assertThat(users.size).isEqualTo(3)
        assertThat(users.map { it.name }).containsExactly("Alice", "Bob", "Charlie")
    }

    @Test
    fun `findAll returns empty list when no data`() = runTest {
        mockExecutor.setFetchAllRows()

        val result = repository.findAll(mockExecutor)

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

        val result = repository.findAllByName(mockExecutor, "John")

        assertThat(result).isSuccess()
        assertThat(result.getOrNull()?.size).isEqualTo(2)
    }

    @Test
    fun `findAllByName binds name parameter`() = runTest {
        mockExecutor.setFetchAllRows()

        repository.findAllByName(mockExecutor, "TestName")

        val sql = mockExecutor.lastExecutedStatement()
        assertThat(sql).isNotNull()
        assertThat(mockExecutor.lastBoundValues).contains("TestName")
    }

    // ==================== findAllByEmailNotNull Tests ====================

    @Test
    fun `findAllBy without parameters returns filtered list`() = runTest {
        mockExecutor.setFetchAllRows(
            userRow(1, "Alice", "alice@example.com"),
            userRow(2, "Bob", "bob@example.com")
        )

        val result = repository.findAllByEmailNotNull(mockExecutor)

        assertThat(result).isSuccess()
        assertThat(result.getOrNull()?.size).isEqualTo(2)
    }

    @Test
    fun `findAllBy without parameters executes query as-is`() = runTest {
        mockExecutor.setFetchAllRows()

        repository.findAllByEmailNotNull(mockExecutor)

        assertThat(mockExecutor.hasExecuted("email IS NOT NULL")).isTrue()
    }

    // ==================== countAll Tests ====================

    @Test
    fun `countAll returns count value`() = runTest {
        mockExecutor.setFetchAllRows(countRow(42))

        val result = repository.countAll(mockExecutor)

        assertThat(result).isSuccess()
        assertThat(result.getOrNull()).isEqualTo(42L)
    }

    @Test
    fun `countAll executes SELECT count query`() = runTest {
        mockExecutor.setFetchAllRows(countRow(0))

        repository.countAll(mockExecutor)

        assertThat(mockExecutor.hasExecuted("SELECT count(*)")).isTrue()
        assertThat(mockExecutor.hasExecuted("FROM users")).isTrue()
    }

    // ==================== countByName Tests ====================

    @Test
    fun `countByName returns filtered count`() = runTest {
        mockExecutor.setFetchAllRows(countRow(5))

        val result = repository.countByName(mockExecutor, "John")

        assertThat(result).isSuccess()
        assertThat(result.getOrNull()).isEqualTo(5L)
    }

    @Test
    fun `countByName binds name parameter`() = runTest {
        mockExecutor.setFetchAllRows(countRow(0))

        repository.countByName(mockExecutor, "TestName")

        val sql = mockExecutor.lastExecutedStatement()
        assertThat(sql).isNotNull()
        assertThat(mockExecutor.lastBoundValues).contains("TestName")
    }

    // ==================== deleteByEmail Tests ====================

    @Test
    fun `deleteByEmail calls execute with DELETE statement`() = runTest {
        mockExecutor.setExecuteResponse(Result.success(1L))

        repository.deleteByEmail(mockExecutor, "test@example.com")

        assertThat(mockExecutor.hasExecuted("DELETE FROM users")).isTrue()
        assertThat(mockExecutor.hasExecuted("WHERE email")).isTrue()
    }

    @Test
    fun `deleteByEmail returns affected row count`() = runTest {
        mockExecutor.setExecuteResponse(Result.success(3L))

        val result = repository.deleteByEmail(mockExecutor, "duplicate@example.com")

        assertThat(result).isSuccess()
        assertThat(result.getOrNull()).isEqualTo(3L)
    }

    @Test
    fun `deleteByEmail binds email parameter`() = runTest {
        mockExecutor.setExecuteResponse(Result.success(0L))

        repository.deleteByEmail(mockExecutor, "delete@test.com")

        val sql = mockExecutor.lastExecutedStatement()
        assertThat(sql).isNotNull()
        assertThat(mockExecutor.lastBoundValues).contains("delete@test.com")
    }

    // ==================== executeUpdateName Tests ====================

    @Test
    fun `executeUpdateName calls execute with UPDATE statement`() = runTest {
        mockExecutor.setExecuteResponse(Result.success(5L))

        repository.executeUpdateName(mockExecutor, "OldName", "NewName")

        assertThat(mockExecutor.hasExecuted("UPDATE users")).isTrue()
        assertThat(mockExecutor.hasExecuted("SET name")).isTrue()
    }

    @Test
    fun `executeUpdateName returns affected row count`() = runTest {
        mockExecutor.setExecuteResponse(Result.success(10L))

        val result = repository.executeUpdateName(mockExecutor, "Old", "New")

        assertThat(result).isSuccess()
        assertThat(result.getOrNull()).isEqualTo(10L)
    }

    @Test
    fun `executeUpdateName binds both parameters`() = runTest {
        mockExecutor.setExecuteResponse(Result.success(0L))

        repository.executeUpdateName(mockExecutor, "FromName", "ToName")

        val sql = mockExecutor.lastExecutedStatement()
        assertThat(sql).isNotNull()
        assertThat(mockExecutor.lastBoundValues).contains("FromName")
        assertThat(mockExecutor.lastBoundValues).contains("ToName")
    }

    // ==================== Error Propagation Tests ====================

    @Test
    fun `repository propagates executor errors on execute`() = runTest {
        val error = SQLError(SQLError.Code.Database, "Connection failed")
        mockExecutor.setExecuteResponse(Result.failure(error))

        val user = User(id = 1, name = "Test", email = "test@example.com")
        val result = repository.delete(mockExecutor, user)

        assertThat(result).isFailure()
        assertThat(result.exceptionOrNull()).isEqualTo(error)
    }

    @Test
    fun `repository propagates executor errors on fetchAll`() = runTest {
        val error = SQLError(SQLError.Code.Database, "Query failed")
        mockExecutor.setFetchAllResponse(Result.failure(error))

        val result = repository.findAll(mockExecutor)

        assertThat(result).isFailure()
        assertThat(result.exceptionOrNull()).isEqualTo(error)
    }

    // ==================== Stream Tests ====================

    @Test
    fun `streamAll is lazy and streams the mapped rows`() = runTest {
        mockExecutor.setFetchAllRows(userRow(1, "Alice", "alice@example.com"), userRow(2, "Bob", "bob@example.com"))

        val users = repository.streamAll(mockExecutor)
        assertThat(mockExecutor.fetchAllCalls).isEmpty()

        assertThat(users.toList().map { it.name }).containsExactly("Alice", "Bob")
        assertThat(mockExecutor.hasExecuted("select id, name, email from users")).isTrue()
    }

    @Test
    fun `streamAllByName binds the named parameter`() = runTest {
        mockExecutor.setFetchAllRows(userRow(1, "Alice", "alice@example.com"))

        val users = repository.streamAllByName(mockExecutor, "Alice").toList()

        assertThat(users.single().id).isEqualTo(1L)
        assertThat(mockExecutor.hasExecuted("where name")).isTrue()
        assertThat(mockExecutor.lastBoundValues).containsExactly("Alice")
    }

    @Test
    fun `streamAll failure fails the collection`() = runTest {
        mockExecutor.setFetchAllResponse(Result.failure(SQLError(SQLError.Code.Database, "boom")))

        val error = assertFailsWith<SQLError> { repository.streamAll(mockExecutor).toList() }

        assertThat(error.code).isEqualTo(SQLError.Code.Database)
    }

    @Test
    fun `stream uses the default fetch size unless the query sets one`() = runTest {
        mockExecutor.setFetchAllRows(userRow(1, "Alice", "alice@example.com"))

        repository.streamAll(mockExecutor).toList()
        assertThat(mockExecutor.lastFetchSize).isEqualTo(QueryExecutor.DEFAULT_FETCH_SIZE)

        repository.streamAllByEmailNotNull(mockExecutor).toList()
        assertThat(mockExecutor.lastFetchSize).isEqualTo(50)
    }

    @Test
    fun `query SQL that needs escaping reaches the database unchanged`() = runTest {
        mockExecutor.setFetchAllRows()

        val result = repository.findAllByQuotedName(mockExecutor, "Alice")

        assertThat(result).isSuccess()
        // A quoted identifier, a backslash and a dollar sign, exactly as written in @Query.
        assertThat(mockExecutor.hasExecuted("\"name\" = ")).isTrue()
        assertThat(mockExecutor.hasExecuted("email NOT LIKE '%\\_%'")).isTrue()
        assertThat(mockExecutor.hasExecuted("name <> '\$x'")).isTrue()

        repository.executeRenameQuoted(mockExecutor, "Bob").getOrThrow()
        assertThat(mockExecutor.hasExecuted("/* renamed */ WHERE \"name\" = '\$old'")).isTrue()
    }
}
