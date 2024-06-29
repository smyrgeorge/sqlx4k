package io.github.smyrgeorge.sqlx4k.driver

import io.github.smyrgeorge.sqlx4k.Sqlx4k
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.get
import kotlinx.cinterop.pointed
import kotlinx.cinterop.staticCFunction
import kotlinx.cinterop.toKString
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import librust_lib.Sqlx4kResult
import librust_lib.sqlx4k_free_result
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume

@OptIn(ExperimentalForeignApi::class)
interface Driver {
    suspend fun query(sql: String): Result<Unit>
    suspend fun <T> fetchAll(sql: String, mapper: Sqlx4k.Row.() -> T): Result<List<T>>

    @OptIn(ExperimentalForeignApi::class)
    private fun <T> CPointer<Sqlx4kResult>?.use(f: (it: Sqlx4kResult) -> T): T {
        return try {
            this?.pointed?.let { f(it) }
                ?: error("Could not extract the value from the raw pointer (null).")
        } finally {
            sqlx4k_free_result(this)
        }
    }

    @OptIn(ExperimentalForeignApi::class)
    fun CPointer<Sqlx4kResult>?.orThrow() {
        use { Sqlx4k.Error(it.error, it.error_message?.toKString()) }.throwIfError()
    }

    @OptIn(ExperimentalForeignApi::class)
    private fun Sqlx4kResult.throwIfError(): Unit =
        Sqlx4k.Error(error, error_message?.toKString()).throwIfError()

    @OptIn(ExperimentalForeignApi::class)
    fun CPointer<Sqlx4kResult>?.tx(): Transaction = use { result ->
        result.throwIfError()
        Transaction(result.tx)
    }

    @OptIn(ExperimentalForeignApi::class)
    fun <T> CPointer<Sqlx4kResult>?.map(f: Sqlx4k.Row.() -> T): List<T> = use { result ->
        result.throwIfError()
        val rows = mutableListOf<T>()
        repeat(result.size) { index ->
            val scope = Sqlx4k.Row(result.rows!![index])
            val row = f(scope)
            rows.add(row)
        }
        rows
    }

    interface Tx {
        suspend fun begin(): Result<Transaction>
    }

    companion object {
        private var idx: ULong = ULong.MIN_VALUE
        private lateinit var mutexIdx: Mutex
        internal suspend fun idx(): ULong = mutexIdx.withLock {
            // At some point is going to overflow, thus it will start from 0.
            // It's the expected behaviour.
            idx++
        }

        internal lateinit var mutexMap: Mutex
        internal lateinit var map: HashMap<ULong, Continuation<CPointer<Sqlx4kResult>?>>
        internal val fn = staticCFunction<ULong, CPointer<Sqlx4kResult>?, Unit> { idx, it ->
            runBlocking { mutexMap.withLock { map.remove(idx) } }!!.resume(it)
        }

        fun init(maxConnections: Int) {
            mutexIdx = Mutex()
            mutexMap = Mutex()
            map = HashMap(maxConnections)
        }
    }
}
