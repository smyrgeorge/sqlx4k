package io.github.smyrgeorge.sqlx4k.impl.coroutines

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlin.coroutines.cancellation.CancellationException

/**
 * Like [runCatching], but does not swallow the cancellation of the calling coroutine.
 *
 * [runCatching] also catches [CancellationException], which would turn a cancellation into a failed
 * [Result] and let the caller keep running in a cancelled coroutine. Here, a [CancellationException]
 * is rethrown when the current coroutine is no longer active. A [CancellationException] thrown while
 * the coroutine is still active (for example a `TimeoutCancellationException` from a `withTimeout`
 * inside [block]) is an ordinary failure and is returned as a failed [Result].
 */
internal suspend inline fun <T> runSuspendCatching(block: () -> T): Result<T> = try {
    Result.success(block())
} catch (e: Throwable) {
    if (e is CancellationException && !currentCoroutineContext().isActive) throw e
    Result.failure(e)
}
