//! Shared SQLite (SQLCipher) engine.
//!
//! Holds the connection pool, the async query methods, the C-ABI result types, parameter
//! binding, error mapping, and the byte-buffer (de)serialization used by the JNI bridge, in the
//! modules below, whose items are re-exported here.
//! Both [`crate::ffi`] (cinterop) and [`crate::jni`] (JVM/Android) call into here, so this is
//! the single source of truth for query execution and result shaping.

#[cfg(not(target_os = "ios"))]
mod byte_buf;
mod client;
mod decode;
mod errors;
mod params;
mod result;
mod stream;
mod types;
mod upsert;

#[cfg(not(target_os = "ios"))]
pub(crate) use byte_buf::*;
pub(crate) use client::*;
pub(crate) use params::*;
pub(crate) use result::*;
pub(crate) use stream::*;
pub(crate) use types::*;

use std::sync::OnceLock;
use tokio::runtime::Runtime;

pub static RUNTIME: OnceLock<Runtime> = OnceLock::new();
