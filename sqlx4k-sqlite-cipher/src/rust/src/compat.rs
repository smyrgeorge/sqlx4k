//! Linux-only ABI shims.
//!
//! SQLCipher's bundled `sqlite3.c` is compiled with large-file support, so on Linux it references
//! the glibc `fcntl64` symbol. Kotlin/Native links against an older bundled glibc sysroot that
//! only exports `fcntl`, leaving `fcntl64` undefined. We provide `fcntl64` here, forwarding to
//! `fcntl` (which Kotlin/Native does resolve, exactly as the plain sqlite driver relies on).
//!
//! `fcntl`/`fcntl64` are ABI-identical on 64-bit Linux, and SQLite only ever calls them with three
//! arguments (`fd`, `cmd`, and either a pointer or an int), so a fixed three-argument forwarder is
//! sufficient.
//!
//! Likewise, glibc 2.38+ headers (e.g. Ubuntu 24.04) redirect `strtol` to `__isoc23_strtol` (its C23
//! variant, which also accepts `0b` binary prefixes), which SQLCipher's codec references and the
//! older sysroot lacks: we forward it to `strtol`. SQLCipher parses decimal and hex pragma values
//! only, where the two behave the same.

use std::ffi::{c_char, c_int, c_long, c_void};

extern "C" {
    fn fcntl(fd: c_int, cmd: c_int, arg: *mut c_void) -> c_int;
    fn strtol(nptr: *const c_char, endptr: *mut *mut c_char, base: c_int) -> c_long;
}

#[no_mangle]
pub extern "C" fn fcntl64(fd: c_int, cmd: c_int, arg: *mut c_void) -> c_int {
    unsafe { fcntl(fd, cmd, arg) }
}

#[no_mangle]
pub extern "C" fn __isoc23_strtol(
    nptr: *const c_char,
    endptr: *mut *mut c_char,
    base: c_int,
) -> c_long {
    unsafe { strtol(nptr, endptr, base) }
}
