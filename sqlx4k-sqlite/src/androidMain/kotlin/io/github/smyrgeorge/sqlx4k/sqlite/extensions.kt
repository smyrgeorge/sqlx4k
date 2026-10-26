package io.github.smyrgeorge.sqlx4k.sqlite

import android.database.sqlite.SQLiteDatabase
import io.github.smyrgeorge.sqlx4k.Connection
import io.github.smyrgeorge.sqlx4k.ConnectionPool
import io.github.smyrgeorge.sqlx4k.SQLError
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Runs [block], reporting a driver failure as the matching [SQLError]. */
internal inline fun <T> rethrowAsSQLError(block: () -> T): T =
    try {
        block()
    } catch (e: Exception) {
        e.toSQLError().raise()
    }

/** Runs [block] on a connection acquired from the pool and closes (releases) the connection afterwards. */
internal suspend inline fun <T> ConnectionPool.withConnection(block: Connection.() -> T): Result<T> = runCatching {
    val connection = acquire().getOrThrow()
    try {
        rethrowAsSQLError { connection.block() }
    } finally {
        // Released also when the caller is cancelled, so the connection always goes back to the pool.
        withContext(NonCancellable) { connection.close() }
    }
}

/**
 * Collects the flow of [block] on a connection acquired from the pool. The connection is held for the whole
 * collection and released when the flow completes, fails or is cancelled.
 */
internal fun <T> ConnectionPool.flowWithConnection(block: Connection.() -> Flow<T>): Flow<T> = flow {
    val connection = acquire().getOrThrow()
    try {
        emitAll(connection.block())
    } finally {
        withContext(NonCancellable) { connection.close() }
    }
}

/** Runs [block] while holding the lock, once [assertIsOpen] passes. */
internal suspend inline fun <T> Mutex.withLockCatching(
    assertIsOpen: () -> Unit,
    block: () -> T
): Result<T> = runCatching {
    withLock {
        assertIsOpen()
        rethrowAsSQLError(block)
    }
}

/** Collects the flow of [block] while holding the lock (for the whole collection), once [assertIsOpen] passes. */
internal fun <T> Mutex.flowWithLock(
    assertIsOpen: () -> Unit,
    block: () -> Flow<T>
): Flow<T> = flow {
    withLock {
        assertIsOpen()
        emitAll(block())
    }
}

/**
 * Ends the transaction after [block], which marks it successful for a commit (a rollback leaves it unmarked, so
 * ending it rolls back), and closes the database afterwards when [close] is set. Blocking: run it on the
 * connection's dispatcher, where the transaction was begun.
 *
 * [SQLiteDatabase.endTransaction] is where the COMMIT (or ROLLBACK) actually runs, so its failure is reported (a
 * deferred foreign key violation, a busy or full database, ...); when [block] itself failed, that failure is reported
 * instead. A failure closing the database is ignored.
 */
internal inline fun SQLiteDatabase.completeTransaction(close: Boolean, block: SQLiteDatabase.() -> Unit) {
    try {
        try {
            rethrowAsSQLError { block() }
        } catch (e: Throwable) {
            // Still end the transaction, but keep the original failure.
            runCatching { endTransaction() }
            throw e
        }
        rethrowAsSQLError { endTransaction() }
    } finally {
        if (close) runCatching { close() }
    }
}
