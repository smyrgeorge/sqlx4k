//! sqlx4k SQLite (SQLCipher) driver.
//!
//! A single Rust core ([`core`]) backed by `sqlx` + SQLCipher is exposed two ways:
//! - [`ffi`]: `#[no_mangle] extern "C"` functions consumed by Kotlin/Native targets via
//!   cinterop (the static library). Fully asynchronous: a completion callback resumes the
//!   suspended Kotlin coroutine.
//! - [`jni`]: JNI exports consumed by the JVM and Android (the shared library). Blocking:
//!   each call drives the tokio runtime to completion and returns the marshalled result,
//!   while Kotlin keeps the call off the main thread via `Dispatchers.IO`.
//!
//! The `jni` module is unavailable on iOS, which uses the FFI/cinterop path exclusively.

// The crate has no Rust API (it is called through its exports): its docs are read with `--document-private-items`,
// where the links above to its private modules resolve.
#![allow(rustdoc::private_intra_doc_links)]

mod core;
mod ffi;

#[cfg(test)]
mod test_support;

#[cfg(not(target_os = "ios"))]
mod jni;

// Linux-only: provides the `fcntl64` symbol that SQLCipher's sqlite3.c references but
// Kotlin/Native's bundled glibc sysroot doesn't export. See the module docs for details.
#[cfg(target_os = "linux")]
mod compat;
