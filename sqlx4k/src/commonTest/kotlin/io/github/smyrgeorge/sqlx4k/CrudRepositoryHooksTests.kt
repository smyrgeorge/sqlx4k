package io.github.smyrgeorge.sqlx4k

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isSameInstanceAs
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertFailsWith

class CrudRepositoryHooksTests {

    private data class Entity(val id: Int)

    /** Bare implementation overriding nothing, so every hook exercises its default body. */
    private class BareHooks : CrudRepositoryHooks<Entity>

    /**
     * Minimal [QueryExecutor] used only to satisfy the `context` parameter; the default hook bodies
     * never touch it.
     */
    private class StubExecutor : QueryExecutor {
        override val encoders: ValueEncoderRegistry = ValueEncoderRegistry.EMPTY
        override suspend fun execute(sql: String): Result<Long> = Result.success(0L)
        override suspend fun execute(statement: Statement): Result<Long> = Result.success(0L)
        override suspend fun fetchAll(sql: String): Result<ResultSet> =
            Result.success(ResultSet(emptyList(), null, ResultSet.Metadata(emptyList())))
        override suspend fun fetchAll(statement: Statement): Result<ResultSet> =
            Result.success(ResultSet(emptyList(), null, ResultSet.Metadata(emptyList())))
    }

    private val hooks = BareHooks()
    private val ctx = StubExecutor()

    // ========================================================================================
    // pre/after hooks -> return the same entity instance unchanged
    // ========================================================================================

    @Test
    fun `preInsertHook returns the same entity instance`() = runBlocking {
        val e = Entity(1)
        assertThat(hooks.preInsertHook(ctx, e)).isSameInstanceAs(e)
    }

    @Test
    fun `preUpdateHook returns the same entity instance`() = runBlocking {
        val e = Entity(2)
        assertThat(hooks.preUpdateHook(ctx, e)).isSameInstanceAs(e)
    }

    @Test
    fun `preDeleteHook returns the same entity instance`() = runBlocking {
        val e = Entity(3)
        assertThat(hooks.preDeleteHook(ctx, e)).isSameInstanceAs(e)
    }

    @Test
    fun `afterInsertHook returns the same entity instance`() = runBlocking {
        val e = Entity(4)
        assertThat(hooks.afterInsertHook(ctx, e)).isSameInstanceAs(e)
    }

    @Test
    fun `afterUpdateHook returns the same entity instance`() = runBlocking {
        val e = Entity(5)
        assertThat(hooks.afterUpdateHook(ctx, e)).isSameInstanceAs(e)
    }

    @Test
    fun `afterDeleteHook returns the same entity instance`() = runBlocking {
        val e = Entity(6)
        assertThat(hooks.afterDeleteHook(ctx, e)).isSameInstanceAs(e)
    }

    // ========================================================================================
    // aroundQuery -> returns exactly block()'s result, invoking block once
    // ========================================================================================

    @Test
    fun `aroundQuery returns exactly the block result and invokes block once`() = runBlocking {
        val stmt = Statement.create("select 1")
        var invocations = 0
        val expected = Any()
        val actual = hooks.aroundQuery("findOneById", stmt) {
            invocations++
            expected
        }
        assertThat(actual).isSameInstanceAs(expected)
        assertThat(invocations).isEqualTo(1)
    }

    @Test
    fun `aroundQuery propagates an exception thrown by the block`() = runBlocking {
        val stmt = Statement.create("insert into t values (1)")
        val ex = assertFailsWith<IllegalStateException> {
            hooks.aroundQuery<Unit>("insert", stmt) {
                throw IllegalStateException("boom")
            }
        }
        assertThat(ex.message).isEqualTo("boom")
    }
}
