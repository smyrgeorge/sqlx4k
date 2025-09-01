package io.github.smyrgeorge.sqlx4k.impl.coroutines

import io.github.smyrgeorge.sqlx4k.Driver
import io.github.smyrgeorge.sqlx4k.Transaction
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext

/**
 * Represents a coroutine-aware transactional context that integrates a transaction as part of the coroutine context.
 *
 * This class provides a mechanism to manage transactional operations within a coroutine, allowing the use of
 * transaction functions seamlessly while maintaining coroutine context safety. It implements `CoroutineContext.Element`
 * to operate as part of the coroutine context and delegates all transaction operations to the provided `Transaction` instance.
 *
 * @constructor Creates a `TransactionContext` instance that delegates to the specified `Transaction`.
 * @property tx The transaction instance to be integrated into the coroutine context.
 *
 * @see io.github.smyrgeorge.sqlx4k.Transaction
 * @see Element
 */
class TransactionContext(
    private val tx: Transaction
) : CoroutineContext.Element, Transaction by tx {
    override val key: CoroutineContext.Key<TransactionContext>
        get() = TransactionContext

    override fun toString(): String {
        return "TransactionContext(tx=$tx)"
    }

    companion object : CoroutineContext.Key<TransactionContext> {
        /**
         * Executes a block of code within a transactional context provided by the given database driver.
         *
         * This method starts a new transaction using the provided `Driver`, wraps the transaction into a
         * `TransactionContext`, and executes the given suspend function block within that context.
         * The transaction is automatically managed, committing if the block completes successfully or
         * rolling back in case of an exception.
         *
         * @param T The result type of the operation performed within the transaction context.
         * @param db The database `Driver` used to manage the transaction and provide access to the underlying database.
         * @param f A suspend function block that performs operations within the provided `TransactionContext`.
         * @return The result of the operation performed within the transaction.
         * @throws Throwable Rethrows any exception encountered during the execution of the transactional block.
         */
        suspend inline fun <T> new(db: Driver, crossinline f: suspend TransactionContext.() -> T): T =
            db.transaction {
                val ctx = TransactionContext(this)
                withContext(ctx) { f(ctx) }
            }

        suspend inline fun <T> withCurrent(crossinline f: suspend TransactionContext.() -> T): T = current().f()

        /**
         * Retrieves the current [TransactionContext] from the coroutine context.
         *
         * This method throws an error if no active [TransactionContext] is found.
         *
         * @return The current [TransactionContext] associated with the coroutine context.
         * @throws IllegalStateException if no transaction context is found in the coroutine context.
         */
        suspend inline fun current(): TransactionContext =
            currentOrNull() ?: error("No transaction context found.")

        /**
         * Retrieves the current [TransactionContext] from the coroutine context if available.
         *
         * This function searches the coroutine context for an active [TransactionContext]
         * and returns it. If no [TransactionContext] is present in the current coroutine context,
         * the function returns null.
         *
         * @return The current [TransactionContext] if it exists in the coroutine context, or null otherwise.
         */
        suspend inline fun currentOrNull(): TransactionContext? =
            currentCoroutineContext()[TransactionContext]
    }
}