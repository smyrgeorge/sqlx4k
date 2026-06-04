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
}
