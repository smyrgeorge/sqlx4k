package io.github.smyrgeorge.sqlx4k.sqlite.cipher

import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

/**
 * Deletes a leftover database file (and its WAL/journal sidecars) so each encrypted test run starts
 * from a clean slate.
 *
 * A plaintext SQLite database tolerates reuse across runs, but SQLCipher does not: a stale or
 * key-incompatible file surfaces as "file is not a database" (code 26). This bites particularly on
 * the iOS simulator, which keeps its own sandboxed copy of a relative path across runs. In-memory
 * URLs (`sqlite::memory:`) have no backing file and are left untouched.
 */
internal fun resetDatabaseFile(url: String) {
    if (url.contains(":memory:")) return
    // Strip the `sqlite:` scheme, any `?query`, and leading slashes to get the on-disk path.
    val path = url.substringAfter("sqlite:").substringBefore('?').trimStart('/')
    if (path.isEmpty()) return
    for (suffix in listOf("", "-wal", "-shm", "-journal")) {
        runCatching { SystemFileSystem.delete(Path(path + suffix), mustExist = false) }
    }
}
