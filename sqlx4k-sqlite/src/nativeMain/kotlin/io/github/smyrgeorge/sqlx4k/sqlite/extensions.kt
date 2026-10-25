@file:OptIn(ExperimentalForeignApi::class)

package io.github.smyrgeorge.sqlx4k.sqlite

import io.github.smyrgeorge.sqlx4k.Dialect
import io.github.smyrgeorge.sqlx4k.Statement
import io.github.smyrgeorge.sqlx4k.ValueEncoderRegistry
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.memScoped
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import sqlx4k.sqlite.Sqlx4kSqliteParam

/**
 * Runs [block] while holding the lock, once [assertIsOpen] passes. The FFI already reports failures as
 * [io.github.smyrgeorge.sqlx4k.SQLError]s, so they are returned as they are.
 */
internal suspend inline fun <T> Mutex.withLockCatching(
    assertIsOpen: () -> Unit,
    block: () -> T
): Result<T> = runCatching {
    withLock {
        assertIsOpen()
        block()
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
 * Renders the statement for SQLite and runs [block] with its SQL and its parameters, allocated for the FFI call
 * (see [allocParams]); the parameters are freed when [block] returns.
 */
internal inline fun <T> Statement.withNativeQuery(
    encoders: ValueEncoderRegistry,
    block: (sql: String, params: CPointer<Sqlx4kSqliteParam>?, paramsLen: Int) -> T
): T {
    val nq = renderNativeQuery(Dialect.SQLite, encoders)
    return memScoped {
        val (params, paramsLen) = allocParams(nq.values)
        block(nq.sql, params, paramsLen)
    }
}
