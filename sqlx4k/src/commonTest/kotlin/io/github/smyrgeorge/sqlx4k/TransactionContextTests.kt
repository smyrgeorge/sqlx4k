package io.github.smyrgeorge.sqlx4k

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNotNull
import assertk.assertions.isNotSameInstanceAs
import assertk.assertions.isNull
import assertk.assertions.isSameInstanceAs
import assertk.assertions.isTrue
import io.github.smyrgeorge.sqlx4k.utils.FakeDriver
import io.github.smyrgeorge.sqlx4k.impl.coroutines.TransactionContext
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlinx.coroutines.runBlocking

class TransactionContextTests {

    @Test
    fun `currentOrNull is null outside any context`() = runBlocking {
        assertThat(TransactionContext.currentOrNull()).isNull()
    }

    @Test
    fun `current throws outside any context`() = runBlocking {
        val ex = assertFailsWith<IllegalStateException> { TransactionContext.current() }
        assertThat(ex.message).isEqualTo("No transaction context found.")
    }

    @Test
    fun `new commits on success and exposes the current context inside`() = runBlocking {
        val db = FakeDriver()
        var seen: TransactionContext? = null
        val result = TransactionContext.new(db) {
            seen = TransactionContext.currentOrNull()
            123
        }
        assertThat(result).isEqualTo(123)
        assertThat(db.beginCount).isEqualTo(1)
        assertThat(seen).isNotNull()
        assertThat(db.transactions.single().commited).isTrue()
        assertThat(db.transactions.single().rollbacked).isFalse()
    }

    @Test
    fun `new rolls back and rethrows on failure`() = runBlocking {
        val db = FakeDriver()
        val ex = assertFailsWith<IllegalStateException> {
            TransactionContext.new(db) { error("boom") }
        }
        assertThat(ex.message).isEqualTo("boom")
        assertThat(db.transactions.single().rollbacked).isTrue()
        assertThat(db.transactions.single().commited).isFalse()
    }

    @Test
    fun `withCurrent without db reuses the active context`() = runBlocking {
        val db = FakeDriver()
        TransactionContext.new(db) {
            val outer = TransactionContext.current()
            TransactionContext.withCurrent {
                assertThat(TransactionContext.current()).isSameInstanceAs(outer)
            }
        }
        assertThat(db.beginCount).isEqualTo(1) // no extra begin
    }

    @Test
    fun `withCurrent without db throws when there is no active context`() = runBlocking {
        assertFailsWith<IllegalStateException> {
            TransactionContext.withCurrent { 1 }
        }
        Unit
    }

    @Test
    fun `withCurrent with db creates a new context when none is active`() = runBlocking {
        val db = FakeDriver()
        val result = TransactionContext.withCurrent(db) { 9 }
        assertThat(result).isEqualTo(9)
        assertThat(db.beginCount).isEqualTo(1)
        assertThat(db.transactions.single().commited).isTrue()
    }

    @Test
    fun `withCurrent with db reuses an active context without a new begin`() = runBlocking {
        val db = FakeDriver()
        TransactionContext.new(db) {
            TransactionContext.withCurrent(db) { 1 }
        }
        assertThat(db.beginCount).isEqualTo(1) // reused, not a second begin
    }

    @Test
    fun `nested new opens independent transactions and restores the outer context`() = runBlocking {
        val db = FakeDriver()
        TransactionContext.new(db) {
            val outer = TransactionContext.current()
            TransactionContext.new(db) {
                assertThat(TransactionContext.current()).isNotSameInstanceAs(outer)
            }
            assertThat(TransactionContext.current()).isSameInstanceAs(outer)
        }
        assertThat(db.beginCount).isEqualTo(2)
        assertThat(db.transactions.all { it.commited }).isTrue()
    }

    @Test
    fun `withCurrent with db reuses the active context and runs the block once when it returns null`() = runBlocking {
        // Regression guard: withCurrent(db) must run the block exactly once and reuse the active
        // transaction even when the block returns null (no spurious second execution / extra begin).
        val db = FakeDriver()
        var invocations = 0
        val result = TransactionContext.new(db) {
            TransactionContext.withCurrent<Int?>(db) {
                invocations++
                null
            }
        }
        assertThat(result).isNull()
        assertThat(invocations).isEqualTo(1)
        assertThat(db.beginCount).isEqualTo(1) // reused the active tx, no extra begin
    }
}
