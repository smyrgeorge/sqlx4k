mod client;
mod decode;
mod errors;
mod ffi;
mod listen;
mod params;
mod result;
mod stream;
mod types;

use std::sync::OnceLock;
use tokio::runtime::Runtime;

static RUNTIME: OnceLock<Runtime> = OnceLock::new();
