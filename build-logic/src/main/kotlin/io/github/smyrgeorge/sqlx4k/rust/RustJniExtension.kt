package io.github.smyrgeorge.sqlx4k.rust

/**
 * DSL extension exposed as `rustJni { }` by the `io.github.smyrgeorge.sqlx4k.rust.jni`
 * convention plugin.
 *
 * Minimal usage:
 * ```kotlin
 * rustJni {
 *     crateName = "sqlx4k_sqlite_cipher"
 * }
 * ```
 */
open class RustJniExtension {
    /** Rust crate name (snake_case), e.g. `sqlx4k_sqlite_cipher`. Required. */
    var crateName: String = ""

    /** Project-relative path to the directory containing `Cargo.toml`. Default: `src/rust`. */
    var cargoDir: String = "src/rust"

    /**
     * Android ABIs built via `cargo ndk` into `src/androidMain/jniLibs`. Defaults to the two
     * 64-bit ABIs (32-bit Android is not supported because native pointers cross JNI as `Long`).
     */
    var androidAbis: List<String> = listOf("arm64-v8a", "x86_64")

    /**
     * Desktop/JVM Rust target triples whose `cdylib` is built and bundled into the JVM jar, so a
     * single jar runs on multiple host platforms (a "fat jar"). The runtime loader picks the right
     * library by OS + architecture.
     *
     * Empty (the default) means "just the build host", which keeps local development free of any
     * cross-toolchains. Set the full list — typically only when publishing — to produce a portable
     * jar; each listed triple then requires its Rust target and C cross-toolchain to be installed.
     * Example:
     * ```kotlin
     * jvmHostTargets = listOf(
     *     "aarch64-apple-darwin", "x86_64-apple-darwin",
     *     "x86_64-unknown-linux-gnu", "aarch64-unknown-linux-gnu",
     *     "x86_64-pc-windows-gnu",
     * )
     * ```
     */
    var jvmHostTargets: List<String> = emptyList()
}
