package io.github.smyrgeorge.sqlx4k.sqlite.cipher

/**
 * Loads `libsqlx4k_sqlite_cipher.so` on Android. On a device/emulator the `.so` is packaged into
 * the AAR/APK under `jniLibs/<abi>/` (built by `buildRustAndroid` via cargo-ndk) and resolved by
 * `System.loadLibrary`. Under Robolectric host tests the shared loader instead picks up the host
 * library from the Gradle-provided path, so this fallback never runs there.
 */
internal actual fun ensureCipherNativeLoaded() = loadCipherNative {
    System.loadLibrary("sqlx4k_sqlite_cipher")
}
