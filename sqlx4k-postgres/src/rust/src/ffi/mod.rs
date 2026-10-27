//! The `extern "C"` exports Kotlin calls, grouped by the object they act on.

pub(crate) mod connection;
pub(crate) mod pool;
pub(crate) mod stream;
pub(crate) mod transaction;
