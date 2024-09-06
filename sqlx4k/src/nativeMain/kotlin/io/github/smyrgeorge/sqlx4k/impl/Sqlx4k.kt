package io.github.smyrgeorge.sqlx4k.impl

class Sqlx4k {
    class Error(
        val code: Code,
        message: String? = null,
    ) : RuntimeException("[$code] :: $message") {
        fun ex(): Nothing = throw this

        enum class Code {
            // Error from the underlying driver:
            Database,
            PoolTimedOut,
            PoolClosed,
            WorkerCrashed,

            // Other errors:
            NamedParameterTypeNotSupported,
            NamedParameterValueNotSupplied
        }
    }
}
