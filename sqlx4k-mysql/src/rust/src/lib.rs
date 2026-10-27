mod client;
mod decode;
mod errors;
mod ffi;
mod params;
mod result;
mod stream;
mod types;

use std::sync::OnceLock;
use tokio::runtime::Runtime;

static RUNTIME: OnceLock<Runtime> = OnceLock::new();
