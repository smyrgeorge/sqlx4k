package io.github.smyrgeorge.sqlx4k.sqlite

import io.github.smyrgeorge.sqlx4k.SQLError

/**
 * A parsed SQLite connection URL.
 *
 * Every target accepts the same sqlx-style URL, so a single string works everywhere:
 *
 * ```
 * sqlite::memory:                  in-memory database
 * sqlite:data.db                   file `data.db` in the current directory
 * sqlite://data.db                 same as above
 * sqlite:///abs/path/data.db       absolute path
 * sqlite://data.db?mode=ro         open read-only
 * sqlite:///abs/data.db?mode=rwc   open read-write, create if missing
 * sqlite::memory:?cache=shared     in-memory database with a shared cache
 * ```
 *
 * Everything after the first `?` is the query string. Its parameters are the ones SQLite defines for
 * URI filenames (and that sqlx accepts): `mode` (`ro`, `rw`, `rwc`, `memory`), `cache` (`shared`,
 * `private`), `immutable` and `vfs`. Each platform decides which of them it can honour; the parser
 * validates the values of `mode` and `cache`, and [requireKnownParams] rejects any other key, both
 * mirroring sqlx so that a bad URL fails the same way on every target.
 *
 * Errors are reported as [SQLError] with [SQLError.Code.Pool], the code the native driver uses for a
 * connection URL sqlx rejects.
 */
internal class SQLiteUrl private constructor(
    /** The URL exactly as given. */
    val raw: String,
    /** The database path, or `:memory:`. Kept exactly as written (no percent-decoding). */
    val database: String,
    /** The raw query string without the leading `?`, or `null` when the URL has none. */
    val query: String?,
    /** The parsed query parameters. When a key is repeated, the last value wins. */
    val params: Map<String, String>,
) {
    /** The `mode` parameter, if present. */
    val mode: String? get() = params[PARAM_MODE]

    /** `true` for `:memory:` databases and for `mode=memory`. */
    val isInMemory: Boolean get() = database.removePrefix(FILE_PREFIX) == MEMORY || mode == MODE_MEMORY

    /** `true` for `mode=ro`. */
    val isReadOnly: Boolean get() = mode == MODE_RO

    /**
     * Fails with the same [SQLError] the native driver raises when the URL carries a parameter other
     * than the ones SQLite defines (`mode`, `cache`, `immutable`, `vfs`).
     */
    fun requireKnownParams(): SQLiteUrl {
        params.keys.firstOrNull { it !in KNOWN_PARAMS }?.let {
            invalid("Unknown parameter `$it` in SQLite URL '$raw'. Supported: ${KNOWN_PARAMS.joinToString()}.")
        }
        return this
    }

    override fun toString(): String = raw

    companion object {
        const val MEMORY = ":memory:"
        const val FILE_PREFIX = "file:"
        const val PARAM_MODE = "mode"
        const val PARAM_CACHE = "cache"
        const val PARAM_IMMUTABLE = "immutable"
        const val PARAM_VFS = "vfs"
        const val MODE_RO = "ro"
        const val MODE_RW = "rw"
        const val MODE_RWC = "rwc"
        const val MODE_MEMORY = "memory"

        /** The parameters SQLite (and sqlx) define for URI filenames. */
        val KNOWN_PARAMS: Set<String> = setOf(PARAM_MODE, PARAM_CACHE, PARAM_IMMUTABLE, PARAM_VFS)

        private val MODES = setOf(MODE_RO, MODE_RW, MODE_RWC, MODE_MEMORY)
        private val CACHES = setOf("shared", "private")

        /**
         * Parses [url]. The `sqlite:` scheme (with or without `//`) is optional, so a bare path is
         * accepted as well.
         *
         * @throws SQLError (code [SQLError.Code.Pool]) if `mode` or `cache` carries a value SQLite does
         * not define.
         */
        fun parse(url: String): SQLiteUrl {
            val stripped = url.removePrefix("sqlite:").removePrefix("//")
            val q = stripped.indexOf('?')
            val database = if (q < 0) stripped else stripped.substring(0, q)
            val query = if (q < 0) null else stripped.substring(q + 1).ifEmpty { null }
            val params: Map<String, String> = query
                ?.split('&')
                ?.filter { it.isNotEmpty() }
                ?.associate { p ->
                    val eq = p.indexOf('=')
                    if (eq < 0) p to "" else p.substring(0, eq) to p.substring(eq + 1)
                }
                ?: emptyMap()

            params[PARAM_MODE]?.let {
                if (it !in MODES) invalid(
                    "Unknown value '$it' for `$PARAM_MODE` in SQLite URL '$url'. Expected one of: ${MODES.joinToString()}."
                )
            }
            params[PARAM_CACHE]?.let {
                if (it !in CACHES) invalid(
                    "Unknown value '$it' for `$PARAM_CACHE` in SQLite URL '$url'. Expected one of: ${CACHES.joinToString()}."
                )
            }
            return SQLiteUrl(url, database, query, params)
        }

        private fun invalid(message: String): Nothing = SQLError(SQLError.Code.Pool, message).raise()
    }
}
