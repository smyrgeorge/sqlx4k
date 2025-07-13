package io.github.smyrgeorge.sqlx4k

import kotlinx.cinterop.*
import sqlx4k.Ptr
import sqlx4k.Sqlx4kResult
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume

/**
 * Provides utility functionality for interfacing with native SQL operations from Kotlin code.
 * This interface handles the interaction between Kotlin coroutines and native functions, enabling
 * proper usage of asynchronous operations and maintaining resource safety during these interactions.
 */
interface DriverNativeUtils {
    @OptIn(ExperimentalForeignApi::class)
    companion object {
        /**
         * Represents a static C function used in the `Driver` class for interfacing with native code.
         *
         * This function handles the processing of the result from a native SQL operation. It takes
         * a native pointer to a continuation object and the resulting pointer from the SQL operation,
         * resumes the suspended continuation with the result, and disposes of the stable reference
         * associated with the continuation.
         *
         * The function encapsulates the interaction between native code and Kotlin coroutines,
         * enabling asynchronous processing and ensuring proper resource management.
         */
        val fn = staticCFunction<CValue<Ptr>, CPointer<Sqlx4kResult>?, Unit> { c, r ->
            val ref = c.useContents { ptr }!!.asStableRef<Continuation<CPointer<Sqlx4kResult>?>>()
            ref.get().resume(r)
            ref.dispose()
        }
    }
}
