mod client;
mod decode;
mod errors;
mod ffi;
mod params;
mod result;
mod stream;
mod types;
mod upsert;

// Linux-only: provides the `fcntl64` symbol that the bundled `sqlite3.c` references but
// Kotlin/Native's bundled glibc sysroot doesn't export. See the module docs for details.
#[cfg(target_os = "linux")]
mod compat;

use std::sync::OnceLock;
use tokio::runtime::Runtime;

static RUNTIME: OnceLock<Runtime> = OnceLock::new();
