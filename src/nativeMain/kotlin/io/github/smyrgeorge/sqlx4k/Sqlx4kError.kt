package io.github.smyrgeorge.sqlx4k

@Suppress("MemberVisibilityCanBePrivate")
class Sqlx4kError(
    val code: Int,
    val message: String? = null,
) {

    fun throwIfError() {
        if (code > 0) error("[$code] $message")
    }
}
