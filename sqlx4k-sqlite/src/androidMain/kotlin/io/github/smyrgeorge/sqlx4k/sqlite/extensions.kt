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
        connection.close()
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
 * ending it rolls back), and closes the database afterwards when [close] is set. A failure while ending or closing
 * is ignored. Blocking: run it on the connection's dispatcher, where the transaction was begun.
 */
internal inline fun SQLiteDatabase.completeTransaction(close: Boolean, block: SQLiteDatabase.() -> Unit) {
    try {
        rethrowAsSQLError { block() }
    } finally {
        try {
            endTransaction()
            if (close) close()
        } catch (_: Exception) {
            // Ignore exceptions in finally block
        }
    }
}
