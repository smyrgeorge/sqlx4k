package io.github.smyrgeorge.sqlx4k.processor

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.containsExactly
import assertk.assertions.isEqualTo
import assertk.assertions.isFailure
import assertk.assertions.isSuccess
import assertk.assertions.isTrue
import io.github.smyrgeorge.sqlx4k.SQLError
import io.github.smyrgeorge.sqlx4k.processor.test.generated.DocumentCrudRepositoryImpl
import io.github.smyrgeorge.sqlx4k.processor.util.Document
import io.github.smyrgeorge.sqlx4k.processor.util.MockQueryExecutor
import io.github.smyrgeorge.sqlx4k.processor.util.MockQueryExecutor.Companion.row
import kotlinx.coroutines.test.runTest
import kotlin.test.BeforeTest
import kotlin.test.Test

/**
 * End-to-end tests for the generated CrudRepository of a `@Version`-ed entity (optimistic locking).
 */
class OptimisticLockRepositoryTests {

    private lateinit var mockExecutor: MockQueryExecutor
    private val repository = DocumentCrudRepositoryImpl
    private val doc = Document(id = 1, title = "Title", body = "Body", version = 3)

    @BeforeTest
    fun setup() {
        mockExecutor = MockQueryExecutor()
    }

    private fun Result<*>.sqlError(): SQLError = exceptionOrNull() as SQLError

    // ==================== UPDATE ====================

    @Test
    fun `update binds the current version and returns the incremented one`() = runTest {
        mockExecutor.setFetchAllRows(row("id" to "1", "version" to "4"))

        val result = repository.update(mockExecutor, doc.copy(title = "v2"))

        assertThat(result).isSuccess()
        assertThat(result.getOrNull()?.version).isEqualTo(4L)
        assertThat(result.getOrNull()?.title).isEqualTo("v2")
        assertThat(mockExecutor.hasExecuted("version = version + 1")).isTrue()
        assertThat(mockExecutor.hasExecuted("and version = ")).isTrue()
        assertThat(mockExecutor.lastBoundValues).containsExactly("v2", "Body", 1L, 3L)
    }

    @Test
    fun `update fails with OptimisticLockFailed when no row matched`() = runTest {
        mockExecutor.setFetchAllRows() // stale: UPDATE matched nothing

        val result = repository.update(mockExecutor, doc)

        assertThat(result).isFailure()
        val error = result.sqlError()
        assertThat(error.code).isEqualTo(SQLError.Code.OptimisticLockFailed)
        assertThat(error.message!!).contains("id=1")
        assertThat(error.message!!).contains("version=3")
    }

    @Test
    fun `update still validates the returned id`() = runTest {
        mockExecutor.setFetchAllRows(row("id" to "2", "version" to "4"))

        val result = repository.update(mockExecutor, doc)

        assertThat(result).isFailure()
        assertThat(result.sqlError().code).isEqualTo(SQLError.Code.RowMismatch)
    }

    // ==================== DELETE ====================

    @Test
    fun `delete binds id and current version`() = runTest {
        mockExecutor.setExecuteResponse(Result.success(1L))

        val result = repository.delete(mockExecutor, doc)

        assertThat(result).isSuccess()
        assertThat(mockExecutor.hasExecuted("delete from documents")).isTrue()
        assertThat(mockExecutor.hasExecuted("and version = ")).isTrue()
        assertThat(mockExecutor.lastBoundValues).containsExactly(1L, 3L)
    }

    @Test
    fun `delete fails with OptimisticLockFailed when no row matched`() = runTest {
        mockExecutor.setExecuteResponse(Result.success(0L))

        val result = repository.delete(mockExecutor, doc)

        assertThat(result).isFailure()
        val error = result.sqlError()
        assertThat(error.code).isEqualTo(SQLError.Code.OptimisticLockFailed)
        assertThat(error.message!!).contains("id=1")
        assertThat(error.message!!).contains("version=3")
    }

    @Test
    fun `delete still reports a multi-row delete as RowMismatch`() = runTest {
        mockExecutor.setExecuteResponse(Result.success(2L))

        val result = repository.delete(mockExecutor, doc)

        assertThat(result).isFailure()
        assertThat(result.sqlError().code).isEqualTo(SQLError.Code.RowMismatch)
    }

    // ==================== BATCH UPDATE ====================

    @Test
    fun `batchUpdate returns every entity with its incremented version`() = runTest {
        mockExecutor.setFetchAllRows(
            row("id" to "1", "version" to "4"),
            row("id" to "2", "version" to "8"),
        )
        val docs = listOf(doc, Document(id = 2, title = "Other", body = "o", version = 7))

        val result = repository.batchUpdate(mockExecutor, docs)

        assertThat(result).isSuccess()
        assertThat(result.getOrThrow().map { it.version }).containsExactly(4L, 8L)
        assertThat(mockExecutor.hasExecuted("t.version = v.version")).isTrue()
    }

    @Test
    fun `batchUpdate fails with OptimisticLockFailed when fewer rows than entities matched`() = runTest {
        mockExecutor.setFetchAllRows(row("id" to "1", "version" to "4")) // only one of two matched
        val docs = listOf(doc, Document(id = 2, title = "Other", body = "o", version = 7))

        val result = repository.batchUpdate(mockExecutor, docs)

        assertThat(result).isFailure()
        val error = result.sqlError()
        assertThat(error.code).isEqualTo(SQLError.Code.OptimisticLockFailed)
        assertThat(error.message!!).contains("1 of 2 rows")
    }

    @Test
    fun `batchUpdate of an empty collection succeeds without touching the executor`() = runTest {
        val result = repository.batchUpdate(mockExecutor, emptyList())

        assertThat(result).isSuccess()
        assertThat(result.getOrThrow()).isEqualTo(emptyList())
        assertThat(mockExecutor.executedStatements).isEqualTo(emptyList())
    }

    // ==================== SAVE ====================

    @Test
    fun `save of an existing entity goes through the versioned update`() = runTest {
        mockExecutor.setFetchAllRows(row("id" to "1", "version" to "4"))

        val result = repository.save(mockExecutor, doc)

        assertThat(result).isSuccess()
        assertThat(result.getOrNull()?.version).isEqualTo(4L)
        assertThat(mockExecutor.hasExecuted("update documents")).isTrue()
    }
}
