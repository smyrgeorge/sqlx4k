package io.github.smyrgeorge.sqlx4k

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
    suspend fun query(sql: String): Result<Unit>
    suspend fun query(
        sql: String,
        params: Map<String, Any?>,
        paramsMapper: ((v: Any?) -> String?)? = null
    ): Result<Unit> = query(sql.withNamedParameters(params, paramsMapper))

    suspend fun <T> fetchAll(sql: String, mapper: Sqlx4k.Row.() -> T): Result<List<T>>
    suspend fun <T> fetchAll(
        sql: String,
        params: Map<String, Any?>,
        paramsMapper: ((v: Any?) -> String?)? = null,
        mapper: Sqlx4k.Row.() -> T,
    ): Result<List<T>> = fetchAll(sql.withNamedParameters(params, paramsMapper), mapper)

    private fun <T> CPointer<Sqlx4kResult>?.use(f: (it: Sqlx4kResult) -> T): T {
        return try {
            this?.pointed?.let { f(it) }
                ?: error("Could not extract the value from the raw pointer (null).")
        } finally {
            sqlx4k_free_result(this)
        }
    }

    private fun Sqlx4kResult.isError(): Boolean = error >= 0
    private fun Sqlx4kResult.toError(): Sqlx4k.Error {
        val code = Sqlx4k.Error.Code.entries[error]
        val message = error_message?.toKString()
        return Sqlx4k.Error(code, message)
    }

    fun CPointer<Sqlx4kResult>?.throwIfError() {
        use { it.throwIfError() }
    }

    private fun Sqlx4kResult.throwIfError() {
        if (isError()) toError().ex()
    }

    private fun <T> Sqlx4kResult.map(f: Sqlx4k.Row.() -> T): List<T> {
        throwIfError()
        val rows = mutableListOf<T>()
        repeat(size) { index ->
            val scope = Sqlx4k.Row(this.rows!![index])
            val row = f(scope)
            rows.add(row)
        }
        return rows
    }

    fun <T> CPointer<Sqlx4kResult>?.map(f: Sqlx4k.Row.() -> T): List<T> =
        use { result -> result.map(f) }

    fun CPointer<Sqlx4kResult>?.tx(): CPointer<out CPointed> = use { result ->
        result.throwIfError()
        result.tx!!
    }

    fun <T> CPointer<Sqlx4kResult>?.txMap(f: Sqlx4k.Row.() -> T): Pair<CPointer<out CPointed>, List<T>> =
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

        private val nameParameterRegex = Regex("""(?<!:):(?!:)[a-zA-Z]\w+""")
        private fun extractNamedParamsIndexes(sql: String): Map<String, List<IntRange>> =
            nameParameterRegex.findAll(sql)
                .mapIndexed { index, group -> Pair(group, index) }
                .groupBy({ it.first.value.substring(1) }, { it.first.range })

        private fun String.withNamedParameters(
            params: Map<String, Any?>,
            paramsMapper: ((v: Any?) -> String?)?
        ): String {
            fun Any?.toValueString(): String {
                if (paramsMapper != null) paramsMapper(this)?.let { return it }
                return when (this) {
                    null -> "null"
                    is String -> this
                    is Byte, is Boolean, is Int, is Long, is Short, is Double, is Float -> toString()
                    else -> Sqlx4k.Error(
                        code = Sqlx4k.Error.Code.NamedParameterTypeNotSupported,
                        message = "Could not map named parameter of type ${this::class.qualifiedName}"
                    ).ex()
                }
            }

            var res = this
            extractNamedParamsIndexes(this).entries.forEach { (name, ranges) ->
                if (!params.containsKey(name)) {
                    Sqlx4k.Error(
                        code = Sqlx4k.Error.Code.NamedParameterValueNotSupplied,
                        message = "Value for named parameter '$name' was not supplied"
                    ).ex()
                }
                ranges.forEach { res = res.replaceRange(it, params[name].toValueString()) }
            }
            return res
        }
    }
}
