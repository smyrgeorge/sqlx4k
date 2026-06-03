//! C ABI consumed by Kotlin/Native targets via cinterop (the static library).
//!
//! Every query function is asynchronous: it spawns the corresponding [`crate::core`] future on
//! the shared tokio runtime and, on completion, invokes `fun(callback, result)` to resume the
//! suspended Kotlin coroutine. Result pointers are leaked here and freed by Kotlin via
//! [`sqlx4k_sqlite_cipher_free_result`]. Mirrors the cbindgen-generated header consumed by the
//! `sqlx4k.sqlite.cipher` cinterop package.

use crate::core::*;
use std::ffi::{c_char, c_int, c_void};

// These no-op exports force cbindgen to emit the `#[repr(C)]` types (and everything they
// reference) into the generated C header.
#[no_mangle]
pub extern "C" fn auto_generated_for_struct_cipher_ptr(_: Sqlx4kSqliteCipherPtr) {}
#[no_mangle]
pub extern "C" fn auto_generated_for_struct_cipher_result(_: Sqlx4kSqliteCipherResult) {}
#[no_mangle]
pub extern "C" fn auto_generated_for_struct_cipher_param(_: Sqlx4kSqliteCipherParam) {}

#[no_mangle]
pub extern "C" fn sqlx4k_sqlite_cipher_free_result(ptr: *mut Sqlx4kSqliteCipherResult) {
    free_result(ptr);
}

#[no_mangle]
pub extern "C" fn sqlx4k_sqlite_cipher_of(
    url: *const c_char,
    password: *const c_char,
    min_connections: c_int,
    max_connections: c_int,
    acquire_timeout_milis: c_int,
    idle_timeout_milis: c_int,
    max_lifetime_milis: c_int,
) -> *mut Sqlx4kSqliteCipherResult {
    let url = c_chars_to_str(url);
    let password = if password.is_null() {
        ""
    } else {
        c_chars_to_str(password)
    };
    open(
        url,
        password,
        min_connections,
        max_connections,
        acquire_timeout_milis,
        idle_timeout_milis,
        max_lifetime_milis,
    )
}

#[no_mangle]
pub extern "C" fn sqlx4k_sqlite_cipher_pool_size(rt: *mut c_void) -> c_int {
    pool_size(rt)
}

#[no_mangle]
pub extern "C" fn sqlx4k_sqlite_cipher_pool_idle_size(rt: *mut c_void) -> c_int {
    pool_idle_size(rt)
}

#[no_mangle]
pub extern "C" fn sqlx4k_sqlite_cipher_close(
    rt: *mut c_void,
    callback: *mut c_void,
    fun: extern "C" fn(Sqlx4kSqliteCipherPtr, *mut Sqlx4kSqliteCipherResult),
) {
    let callback = Sqlx4kSqliteCipherPtr { ptr: callback };
    let runtime = RUNTIME.get().unwrap();
    let sqlx4k = unsafe { &*(rt as *mut Sqlx4kSqliteCipher) };
    runtime.spawn(async move {
        let result = sqlx4k.close().await;
        fun(callback, result)
    });
}

#[no_mangle]
pub extern "C" fn sqlx4k_sqlite_cipher_query(
    rt: *mut c_void,
    sql: *const c_char,
    callback: *mut c_void,
    fun: extern "C" fn(Sqlx4kSqliteCipherPtr, *mut Sqlx4kSqliteCipherResult),
) {
    let callback = Sqlx4kSqliteCipherPtr { ptr: callback };
    let sql = c_chars_to_str(sql).to_owned();
    let runtime = RUNTIME.get().unwrap();
    let sqlx4k = unsafe { &*(rt as *mut Sqlx4kSqliteCipher) };
    runtime.spawn(async move {
        let result = sqlx4k.query(sql).await;
        fun(callback, result)
    });
}

#[no_mangle]
pub extern "C" fn sqlx4k_sqlite_cipher_fetch_all(
    rt: *mut c_void,
    sql: *const c_char,
    callback: *mut c_void,
    fun: extern "C" fn(Sqlx4kSqliteCipherPtr, *mut Sqlx4kSqliteCipherResult),
) {
    let callback = Sqlx4kSqliteCipherPtr { ptr: callback };
    let sql = c_chars_to_str(sql).to_owned();
    let runtime = RUNTIME.get().unwrap();
    let sqlx4k = unsafe { &*(rt as *mut Sqlx4kSqliteCipher) };
    runtime.spawn(async move {
        let result = sqlx4k.fetch_all(sql).await;
        fun(callback, result)
    });
}

#[no_mangle]
pub extern "C" fn sqlx4k_sqlite_cipher_cn_acquire(
    rt: *mut c_void,
    callback: *mut c_void,
    fun: extern "C" fn(Sqlx4kSqliteCipherPtr, *mut Sqlx4kSqliteCipherResult),
) {
    let callback = Sqlx4kSqliteCipherPtr { ptr: callback };
    let runtime = RUNTIME.get().unwrap();
    let sqlx4k = unsafe { &*(rt as *mut Sqlx4kSqliteCipher) };
    runtime.spawn(async move {
        let result = sqlx4k.cn_acquire().await;
        fun(callback, result)
    });
}

#[no_mangle]
pub extern "C" fn sqlx4k_sqlite_cipher_cn_release(
    rt: *mut c_void,
    cn: *mut c_void,
    callback: *mut c_void,
    fun: extern "C" fn(Sqlx4kSqliteCipherPtr, *mut Sqlx4kSqliteCipherResult),
) {
    let cn = Sqlx4kSqliteCipherPtr { ptr: cn };
    let callback = Sqlx4kSqliteCipherPtr { ptr: callback };
    let runtime = RUNTIME.get().unwrap();
    let sqlx4k = unsafe { &*(rt as *mut Sqlx4kSqliteCipher) };
    runtime.spawn(async move {
        let result = sqlx4k.cn_release(cn).await;
        fun(callback, result)
    });
}

#[no_mangle]
pub extern "C" fn sqlx4k_sqlite_cipher_cn_query(
    rt: *mut c_void,
    cn: *mut c_void,
    sql: *const c_char,
    callback: *mut c_void,
    fun: extern "C" fn(Sqlx4kSqliteCipherPtr, *mut Sqlx4kSqliteCipherResult),
) {
    let cn = Sqlx4kSqliteCipherPtr { ptr: cn };
    let callback = Sqlx4kSqliteCipherPtr { ptr: callback };
    let sql = c_chars_to_str(sql).to_owned();
    let runtime = RUNTIME.get().unwrap();
    let sqlx4k = unsafe { &*(rt as *mut Sqlx4kSqliteCipher) };
    runtime.spawn(async move {
        let result = sqlx4k.cn_query(cn, sql).await;
        fun(callback, result)
    });
}

#[no_mangle]
pub extern "C" fn sqlx4k_sqlite_cipher_cn_fetch_all(
    rt: *mut c_void,
    cn: *mut c_void,
    sql: *const c_char,
    callback: *mut c_void,
    fun: extern "C" fn(Sqlx4kSqliteCipherPtr, *mut Sqlx4kSqliteCipherResult),
) {
    let cn = Sqlx4kSqliteCipherPtr { ptr: cn };
    let callback = Sqlx4kSqliteCipherPtr { ptr: callback };
    let sql = c_chars_to_str(sql).to_owned();
    let runtime = RUNTIME.get().unwrap();
    let sqlx4k = unsafe { &*(rt as *mut Sqlx4kSqliteCipher) };
    runtime.spawn(async move {
        let result = sqlx4k.cn_fetch_all(cn, sql).await;
        fun(callback, result)
    });
}

#[no_mangle]
pub extern "C" fn sqlx4k_sqlite_cipher_cn_tx_begin(
    rt: *mut c_void,
    cn: *mut c_void,
    callback: *mut c_void,
    fun: extern "C" fn(Sqlx4kSqliteCipherPtr, *mut Sqlx4kSqliteCipherResult),
) {
    let cn = Sqlx4kSqliteCipherPtr { ptr: cn };
    let callback = Sqlx4kSqliteCipherPtr { ptr: callback };
    let runtime = RUNTIME.get().unwrap();
    let sqlx4k = unsafe { &*(rt as *mut Sqlx4kSqliteCipher) };
    runtime.spawn(async move {
        let result = sqlx4k.cn_tx_begin(cn).await;
        fun(callback, result)
    });
}

#[no_mangle]
pub extern "C" fn sqlx4k_sqlite_cipher_tx_begin(
    rt: *mut c_void,
    callback: *mut c_void,
    fun: extern "C" fn(Sqlx4kSqliteCipherPtr, *mut Sqlx4kSqliteCipherResult),
) {
    let callback = Sqlx4kSqliteCipherPtr { ptr: callback };
    let runtime = RUNTIME.get().unwrap();
    let sqlx4k = unsafe { &*(rt as *mut Sqlx4kSqliteCipher) };
    runtime.spawn(async move {
        let result = sqlx4k.tx_begin().await;
        fun(callback, result)
    });
}

#[no_mangle]
pub extern "C" fn sqlx4k_sqlite_cipher_tx_commit(
    rt: *mut c_void,
    tx: *mut c_void,
    callback: *mut c_void,
    fun: extern "C" fn(Sqlx4kSqliteCipherPtr, *mut Sqlx4kSqliteCipherResult),
) {
    let tx = Sqlx4kSqliteCipherPtr { ptr: tx };
    let callback = Sqlx4kSqliteCipherPtr { ptr: callback };
    let runtime = RUNTIME.get().unwrap();
    let sqlx4k = unsafe { &*(rt as *mut Sqlx4kSqliteCipher) };
    runtime.spawn(async move {
        let result = sqlx4k.tx_commit(tx).await;
        fun(callback, result)
    });
}

#[no_mangle]
pub extern "C" fn sqlx4k_sqlite_cipher_tx_rollback(
    rt: *mut c_void,
    tx: *mut c_void,
    callback: *mut c_void,
    fun: extern "C" fn(Sqlx4kSqliteCipherPtr, *mut Sqlx4kSqliteCipherResult),
) {
    let tx = Sqlx4kSqliteCipherPtr { ptr: tx };
    let callback = Sqlx4kSqliteCipherPtr { ptr: callback };
    let runtime = RUNTIME.get().unwrap();
    let sqlx4k = unsafe { &*(rt as *mut Sqlx4kSqliteCipher) };
    runtime.spawn(async move {
        let result = sqlx4k.tx_rollback(tx).await;
        fun(callback, result)
    });
}

#[no_mangle]
pub extern "C" fn sqlx4k_sqlite_cipher_tx_query(
    rt: *mut c_void,
    tx: *mut c_void,
    sql: *const c_char,
    callback: *mut c_void,
    fun: extern "C" fn(Sqlx4kSqliteCipherPtr, *mut Sqlx4kSqliteCipherResult),
) {
    let tx = Sqlx4kSqliteCipherPtr { ptr: tx };
    let callback = Sqlx4kSqliteCipherPtr { ptr: callback };
    let sql = c_chars_to_str(sql).to_owned();
    let runtime = RUNTIME.get().unwrap();
    let sqlx4k = unsafe { &*(rt as *mut Sqlx4kSqliteCipher) };
    runtime.spawn(async move {
        let result = sqlx4k.tx_query(tx, sql).await;
        fun(callback, result)
    });
}

#[no_mangle]
pub extern "C" fn sqlx4k_sqlite_cipher_tx_fetch_all(
    rt: *mut c_void,
    tx: *mut c_void,
    sql: *const c_char,
    callback: *mut c_void,
    fun: extern "C" fn(Sqlx4kSqliteCipherPtr, *mut Sqlx4kSqliteCipherResult),
) {
    let tx = Sqlx4kSqliteCipherPtr { ptr: tx };
    let callback = Sqlx4kSqliteCipherPtr { ptr: callback };
    let sql = c_chars_to_str(sql).to_owned();
    let runtime = RUNTIME.get().unwrap();
    let sqlx4k = unsafe { &*(rt as *mut Sqlx4kSqliteCipher) };
    runtime.spawn(async move {
        let result = sqlx4k.tx_fetch_all(tx, sql).await;
        fun(callback, result)
    });
}

#[no_mangle]
pub extern "C" fn sqlx4k_sqlite_cipher_query_with_params(
    rt: *mut c_void,
    sql: *const c_char,
    params: *const Sqlx4kSqliteCipherParam,
    params_len: c_int,
    callback: *mut c_void,
    fun: extern "C" fn(Sqlx4kSqliteCipherPtr, *mut Sqlx4kSqliteCipherResult),
) {
    let callback = Sqlx4kSqliteCipherPtr { ptr: callback };
    let sql = c_chars_to_str(sql).to_owned();
    let owned = read_params(params, params_len);
    let runtime = RUNTIME.get().unwrap();
    let sqlx4k = unsafe { &*(rt as *mut Sqlx4kSqliteCipher) };
    runtime.spawn(async move {
        let result = sqlx4k.query_with_params(sql, owned).await;
        fun(callback, result)
    });
}

#[no_mangle]
pub extern "C" fn sqlx4k_sqlite_cipher_fetch_all_with_params(
    rt: *mut c_void,
    sql: *const c_char,
    params: *const Sqlx4kSqliteCipherParam,
    params_len: c_int,
    callback: *mut c_void,
    fun: extern "C" fn(Sqlx4kSqliteCipherPtr, *mut Sqlx4kSqliteCipherResult),
) {
    let callback = Sqlx4kSqliteCipherPtr { ptr: callback };
    let sql = c_chars_to_str(sql).to_owned();
    let owned = read_params(params, params_len);
    let runtime = RUNTIME.get().unwrap();
    let sqlx4k = unsafe { &*(rt as *mut Sqlx4kSqliteCipher) };
    runtime.spawn(async move {
        let result = sqlx4k.fetch_all_with_params(sql, owned).await;
        fun(callback, result)
    });
}

#[no_mangle]
pub extern "C" fn sqlx4k_sqlite_cipher_cn_query_with_params(
    rt: *mut c_void,
    cn: *mut c_void,
    sql: *const c_char,
    params: *const Sqlx4kSqliteCipherParam,
    params_len: c_int,
    callback: *mut c_void,
    fun: extern "C" fn(Sqlx4kSqliteCipherPtr, *mut Sqlx4kSqliteCipherResult),
) {
    let cn = Sqlx4kSqliteCipherPtr { ptr: cn };
    let callback = Sqlx4kSqliteCipherPtr { ptr: callback };
    let sql = c_chars_to_str(sql).to_owned();
    let owned = read_params(params, params_len);
    let runtime = RUNTIME.get().unwrap();
    let sqlx4k = unsafe { &*(rt as *mut Sqlx4kSqliteCipher) };
    runtime.spawn(async move {
        let result = sqlx4k.cn_query_with_params(cn, sql, owned).await;
        fun(callback, result)
    });
}

#[no_mangle]
pub extern "C" fn sqlx4k_sqlite_cipher_cn_fetch_all_with_params(
    rt: *mut c_void,
    cn: *mut c_void,
    sql: *const c_char,
    params: *const Sqlx4kSqliteCipherParam,
    params_len: c_int,
    callback: *mut c_void,
    fun: extern "C" fn(Sqlx4kSqliteCipherPtr, *mut Sqlx4kSqliteCipherResult),
) {
    let cn = Sqlx4kSqliteCipherPtr { ptr: cn };
    let callback = Sqlx4kSqliteCipherPtr { ptr: callback };
    let sql = c_chars_to_str(sql).to_owned();
    let owned = read_params(params, params_len);
    let runtime = RUNTIME.get().unwrap();
    let sqlx4k = unsafe { &*(rt as *mut Sqlx4kSqliteCipher) };
    runtime.spawn(async move {
        let result = sqlx4k.cn_fetch_all_with_params(cn, sql, owned).await;
        fun(callback, result)
    });
}

#[no_mangle]
pub extern "C" fn sqlx4k_sqlite_cipher_tx_query_with_params(
    rt: *mut c_void,
    tx: *mut c_void,
    sql: *const c_char,
    params: *const Sqlx4kSqliteCipherParam,
    params_len: c_int,
    callback: *mut c_void,
    fun: extern "C" fn(Sqlx4kSqliteCipherPtr, *mut Sqlx4kSqliteCipherResult),
) {
    let tx = Sqlx4kSqliteCipherPtr { ptr: tx };
    let callback = Sqlx4kSqliteCipherPtr { ptr: callback };
    let sql = c_chars_to_str(sql).to_owned();
    let owned = read_params(params, params_len);
    let runtime = RUNTIME.get().unwrap();
    let sqlx4k = unsafe { &*(rt as *mut Sqlx4kSqliteCipher) };
    runtime.spawn(async move {
        let result = sqlx4k.tx_query_with_params(tx, sql, owned).await;
        fun(callback, result)
    });
}

#[no_mangle]
pub extern "C" fn sqlx4k_sqlite_cipher_tx_fetch_all_with_params(
    rt: *mut c_void,
    tx: *mut c_void,
    sql: *const c_char,
    params: *const Sqlx4kSqliteCipherParam,
    params_len: c_int,
    callback: *mut c_void,
    fun: extern "C" fn(Sqlx4kSqliteCipherPtr, *mut Sqlx4kSqliteCipherResult),
) {
    let tx = Sqlx4kSqliteCipherPtr { ptr: tx };
    let callback = Sqlx4kSqliteCipherPtr { ptr: callback };
    let sql = c_chars_to_str(sql).to_owned();
    let owned = read_params(params, params_len);
    let runtime = RUNTIME.get().unwrap();
    let sqlx4k = unsafe { &*(rt as *mut Sqlx4kSqliteCipher) };
    runtime.spawn(async move {
        let result = sqlx4k.tx_fetch_all_with_params(tx, sql, owned).await;
        fun(callback, result)
    });
}
