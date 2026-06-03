package io.github.smyrgeorge.sqlx4k.sqlite.cipher

import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

private val nativeLoaded = AtomicBoolean(false)

/**
 * Loads the `sqlx4k_sqlite_cipher` native library exactly once.
 *
 * First it tries the Gradle-provided host library directory (set during `jvmTest`/`androidHostTest`
 * via the `sqlx4k.sqlite.cipher.native.path` system property); this is what lets Robolectric host
 * tests on the JVM load the host `.dylib`/`.so`/`.dll`. If that property is absent (a real
 * deployment), [loadFallback] is invoked — resource extraction on the JVM, `System.loadLibrary` on
 * Android.
 */
internal fun loadCipherNative(loadFallback: () -> Unit) {
    if (!nativeLoaded.compareAndSet(false, true)) return
    if (!tryLoadHostNativeLibFromProperty()) loadFallback()
}

private fun tryLoadHostNativeLibFromProperty(): Boolean {
    // Property name follows the `<crate>.native.path` convention set by the rustJni Gradle plugin.
    val path = System.getProperty("sqlx4k_sqlite_cipher.native.path") ?: return false
    val src = File(path, hostNativeLibName())
    require(src.exists()) { "native lib not found at ${src.absolutePath}" }
    // Copy to a unique temp file before loading. Robolectric runs each test sandbox in its own
    // classloader, and System.load-ing the *same* file from two classloaders throws
    // "Native Library already loaded in another classloader"; loading distinct copies avoids it.
    val ext = src.name.substringAfterLast('.', "so")
    val tmp = File.createTempFile("sqlx4k_sqlite_cipher", ".$ext").also { it.deleteOnExit() }
    src.inputStream().use { input -> tmp.outputStream().use { input.copyTo(it) } }
    System.load(tmp.absolutePath)
    return true
}

internal fun hostNativeLibName(): String {
    val os = System.getProperty("os.name").orEmpty().lowercase()
    val arch = System.getProperty("os.arch").orEmpty().lowercase()
    val isArm64 = arch == "aarch64" || arch == "arm64"
    return when {
        os.contains("mac") || os.contains("darwin") ->
            if (isArm64) "libsqlx4k_sqlite_cipher_aarch64.dylib" else "libsqlx4k_sqlite_cipher.dylib"

        os.contains("windows") ->
            if (isArm64) "sqlx4k_sqlite_cipher_aarch64.dll" else "sqlx4k_sqlite_cipher.dll"

        else ->
            if (isArm64) "libsqlx4k_sqlite_cipher_aarch64.so" else "libsqlx4k_sqlite_cipher.so"
    }
}
