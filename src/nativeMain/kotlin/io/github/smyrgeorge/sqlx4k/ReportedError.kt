package io.github.smyrgeorge.sqlx4k

import kotlinx.cinterop.ExperimentalForeignApi
import librust_lib.INVALID_TYPE_CONVERSION
import librust_lib.OK

enum class ReportedError(private val message: String) {
    MappingError("Could not properly map to a valid rust type."),
    UnknownError("Unknown error"),
    ;

    fun ex(): Nothing = throw ReportedErrorException(this)

    companion object {
        private class ReportedErrorException(error: ReportedError) : RuntimeException(error.message)

        @OptIn(ExperimentalForeignApi::class)
        fun Int.checkExitCode(): Unit = when (this) {
            OK -> Unit
            INVALID_TYPE_CONVERSION -> MappingError.ex()
            else -> UnknownError.ex()
        }
    }
}
