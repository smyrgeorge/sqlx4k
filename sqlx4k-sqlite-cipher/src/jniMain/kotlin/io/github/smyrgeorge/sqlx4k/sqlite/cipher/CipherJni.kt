package io.github.smyrgeorge.sqlx4k.sqlite.cipher

/**
 * JNI bindings to the Rust `sqlx4k_sqlite_cipher` shared library, shared by the JVM and Android
 * targets. Each `native*` function blocks while the Rust side drives the tokio runtime to
 * completion (callers must invoke them off the main thread, e.g. on `Dispatchers.IO`).
 *
 * `rt`/`cn`/`tx` are opaque native pointers passed as `Long` (64-bit only). Every call returns a
 * length-prefixed, big-endian byte buffer decoded by [decodeResult]; the parameterized variants
 * take the matching buffer produced by [encodeParams]. The symbol names must match the
 * `Java_io_github_smyrgeorge_sqlx4k_sqlite_cipher_CipherJni_*` exports in the Rust `jni` module.
 */
internal object CipherJni {
    external fun nativeOf(
        url: String,
        password: String,
        minConnections: Int,
        maxConnections: Int,
        acquireTimeoutMillis: Int,
        idleTimeoutMillis: Int,
        maxLifetimeMillis: Int,
    ): ByteArray

    external fun nativePoolSize(rt: Long): Int
    external fun nativePoolIdleSize(rt: Long): Int
    external fun nativeClose(rt: Long): ByteArray

    external fun nativeQuery(rt: Long, sql: String): ByteArray
    external fun nativeFetchAll(rt: Long, sql: String): ByteArray
    external fun nativeQueryWithParams(rt: Long, sql: String, params: ByteArray): ByteArray
    external fun nativeFetchAllWithParams(rt: Long, sql: String, params: ByteArray): ByteArray

    external fun nativeCnAcquire(rt: Long): ByteArray
    external fun nativeCnRelease(rt: Long, cn: Long): ByteArray
    external fun nativeCnQuery(rt: Long, cn: Long, sql: String): ByteArray
    external fun nativeCnFetchAll(rt: Long, cn: Long, sql: String): ByteArray
    external fun nativeCnQueryWithParams(rt: Long, cn: Long, sql: String, params: ByteArray): ByteArray
    external fun nativeCnFetchAllWithParams(rt: Long, cn: Long, sql: String, params: ByteArray): ByteArray
    external fun nativeCnTxBegin(rt: Long, cn: Long): ByteArray

    external fun nativeTxBegin(rt: Long): ByteArray
    external fun nativeTxCommit(rt: Long, tx: Long): ByteArray
    external fun nativeTxRollback(rt: Long, tx: Long): ByteArray
    external fun nativeTxQuery(rt: Long, tx: Long, sql: String): ByteArray
    external fun nativeTxFetchAll(rt: Long, tx: Long, sql: String): ByteArray
    external fun nativeTxQueryWithParams(rt: Long, tx: Long, sql: String, params: ByteArray): ByteArray
    external fun nativeTxFetchAllWithParams(rt: Long, tx: Long, sql: String, params: ByteArray): ByteArray
}
