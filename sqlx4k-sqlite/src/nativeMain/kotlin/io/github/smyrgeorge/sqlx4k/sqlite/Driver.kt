package io.github.smyrgeorge.sqlx4k.sqlite

import io.github.smyrgeorge.sqlx4k.NamedParameters
import kotlinx.cinterop.CPointed
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.CValue
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.asStableRef
import kotlinx.cinterop.get
import kotlinx.cinterop.pointed
import kotlinx.cinterop.staticCFunction
import kotlinx.cinterop.toKString
import kotlinx.cinterop.useContents
import librust_lib.Ptr
import librust_lib.Sqlx4kResult
import librust_lib.sqlx4k_free_result
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume

@OptIn(ExperimentalForeignApi::class)
interface Driver {
    suspend fun execute(sql: String): Result<ULong>
    suspend fun execute(
        sql: String,
        params: Map<String, Any?>,
        paramsMapper: ((v: Any?) -> String?)? = null
    ): Result<ULong> = execute(NamedParameters.render(sql, params, paramsMapper))

    suspend fun <T> fetchAll(sql: String, mapper: ResultSet.Row.() -> T): Result<List<T>>
    suspend fun <T> fetchAll(
        sql: String,
        params: Map<String, Any?>,
        paramsMapper: ((v: Any?) -> String?)? = null,
        mapper: ResultSet.Row.() -> T,
    ): Result<List<T>> = fetchAll(NamedParameters.render(sql, params, paramsMapper), mapper)

    private inline fun <T> CPointer<Sqlx4kResult>?.use(f: (it: Sqlx4kResult) -> T): T {
        return try {
            this?.pointed?.let { f(it) }
                ?: error("Could not extract the value from the raw pointer (null).")
        } finally {
            sqlx4k_free_result(this)
        }
    }

    private fun Sqlx4kResult.isError(): Boolean = error >= 0
    private fun Sqlx4kResult.toError(): ResultSet.Error {
        val code = ResultSet.Error.Code.entries[error]
        val message = error_message?.toKString()
        return ResultSet.Error(code, message)
    }

    fun CPointer<Sqlx4kResult>?.rowsAffectedOrError(): ULong = use {
        it.throwIfError()
        it.rows_affected
    }

    fun CPointer<Sqlx4kResult>?.throwIfError() {
        use { it.throwIfError() }
    }

    private fun Sqlx4kResult.throwIfError() {
        if (isError()) toError().ex()
    }

    private inline fun <T> Sqlx4kResult.map(f: ResultSet.Row.() -> T): List<T> {
        throwIfError()
        val rows = mutableListOf<T>()
        repeat(size) { index ->
            val scope = ResultSet.Row(this.rows!![index])
            val row = f(scope)
            rows.add(row)
        }
        return rows
    }

    fun <T> CPointer<Sqlx4kResult>?.map(f: ResultSet.Row.() -> T): List<T> =
        use { result -> result.map(f) }

    fun CPointer<Sqlx4kResult>?.tx(): Pair<CPointer<out CPointed>, ULong> = use { result ->
        result.throwIfError()
        result.tx!! to result.rows_affected
    }

    fun <T> CPointer<Sqlx4kResult>?.txMap(f: ResultSet.Row.() -> T): Pair<CPointer<out CPointed>, List<T>> =
        use { result -> result.tx!! to result.map(f) }

    interface Tx {
        suspend fun begin(): Result<Transaction>
    }

    companion object {
        internal val fn = staticCFunction<CValue<Ptr>, CPointer<Sqlx4kResult>?, Unit> { c, r ->
            val ref = c.useContents { ptr }!!.asStableRef<Continuation<CPointer<Sqlx4kResult>?>>()
            ref.get().resume(r)
            ref.dispose()
        }
    }
}
