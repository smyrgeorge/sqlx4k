package io.github.smyrgeorge.sqlx4k.sqlite.cipher

import java.io.File

/**
 * Loads `libsqlx4k_sqlite_cipher` for the JVM. When not running under Gradle (no host-path system
 * property), the library is extracted from a JAR classpath resource into a temp file and loaded
 * with `System.load` — `System.loadLibrary` cannot be used because the temp dir is not on
 * `java.library.path`.
 */
internal actual fun ensureCipherNativeLoaded() = loadCipherNative {
    val libName = hostNativeLibName()
    val stream = CipherJni::class.java.getResourceAsStream("/$libName")
        ?: error("$libName not found on classpath")
    val ext = when {
        libName.endsWith(".dylib") -> ".dylib"
        libName.endsWith(".dll") -> ".dll"
        else -> ".so"
    }
    val tmp = File.createTempFile("sqlx4k_sqlite_cipher", ext).also { it.deleteOnExit() }
    stream.use { input -> tmp.outputStream().use { input.copyTo(it) } }
    System.load(tmp.absolutePath)
}
