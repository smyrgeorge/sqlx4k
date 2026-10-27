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
    // Reject a null password instead of silently falling back to an empty one (which would open an
    // unencrypted database). The key must be provided explicitly at the FFI boundary.
    if password.is_null() {
        panic!("password must not be null");
    }
    let password = c_chars_to_str(password);
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
pub extern "C" fn sqlx4k_sqlite_cipher_stream_open(
    rt: *mut c_void,
    sql: *const c_char,
    params: *const Sqlx4kSqliteCipherParam,
    params_len: c_int,
    fetch_size: c_int,
    callback: *mut c_void,
    fun: extern "C" fn(Sqlx4kSqliteCipherPtr, *mut Sqlx4kSqliteCipherResult),
) {
    let callback = Sqlx4kSqliteCipherPtr { ptr: callback };
    let sql = c_chars_to_str(sql).to_owned();
    let owned = match read_params(params, params_len) {
        Ok(owned) => owned,
        Err(err) => return fun(callback, params_error(err, std::ptr::null_mut())),
    };
    let fetch_size = stream_fetch_size(fetch_size);
    let runtime = RUNTIME.get().unwrap();
    let sqlx4k = unsafe { &*(rt as *mut Sqlx4kSqliteCipher) };
    runtime.spawn(async move {
        let result = sqlx4k.stream_open(sql, owned, fetch_size).await;
        fun(callback, result)
    });
}

#[no_mangle]
pub extern "C" fn sqlx4k_sqlite_cipher_cn_stream_open(
    rt: *mut c_void,
    cn: *mut c_void,
    sql: *const c_char,
    params: *const Sqlx4kSqliteCipherParam,
    params_len: c_int,
    fetch_size: c_int,
    callback: *mut c_void,
    fun: extern "C" fn(Sqlx4kSqliteCipherPtr, *mut Sqlx4kSqliteCipherResult),
) {
    let callback = Sqlx4kSqliteCipherPtr { ptr: callback };
    let cn = Sqlx4kSqliteCipherPtr { ptr: cn };
    let sql = c_chars_to_str(sql).to_owned();
    let owned = match read_params(params, params_len) {
        Ok(owned) => owned,
        Err(err) => return fun(callback, params_error(err, std::ptr::null_mut())),
    };
    let fetch_size = stream_fetch_size(fetch_size);
    let runtime = RUNTIME.get().unwrap();
    let sqlx4k = unsafe { &*(rt as *mut Sqlx4kSqliteCipher) };
    runtime.spawn(async move {
        let result = sqlx4k.cn_stream_open(cn, sql, owned, fetch_size).await;
        fun(callback, result)
    });
}

#[no_mangle]
pub extern "C" fn sqlx4k_sqlite_cipher_tx_stream_open(
    rt: *mut c_void,
    tx: *mut c_void,
    sql: *const c_char,
    params: *const Sqlx4kSqliteCipherParam,
    params_len: c_int,
    fetch_size: c_int,
    callback: *mut c_void,
    fun: extern "C" fn(Sqlx4kSqliteCipherPtr, *mut Sqlx4kSqliteCipherResult),
) {
    let callback = Sqlx4kSqliteCipherPtr { ptr: callback };
    let tx = Sqlx4kSqliteCipherPtr { ptr: tx };
    let sql = c_chars_to_str(sql).to_owned();
    let owned = match read_params(params, params_len) {
        Ok(owned) => owned,
        Err(err) => return fun(callback, params_error(err, tx.ptr)),
    };
    let fetch_size = stream_fetch_size(fetch_size);
    let runtime = RUNTIME.get().unwrap();
    let sqlx4k = unsafe { &*(rt as *mut Sqlx4kSqliteCipher) };
    runtime.spawn(async move {
        let result = sqlx4k.tx_stream_open(tx, sql, owned, fetch_size).await;
        fun(callback, result)
    });
}

#[no_mangle]
pub extern "C" fn sqlx4k_sqlite_cipher_stream_next(
    rt: *mut c_void,
    stream: *mut c_void,
    callback: *mut c_void,
    fun: extern "C" fn(Sqlx4kSqliteCipherPtr, *mut Sqlx4kSqliteCipherResult),
) {
    let callback = Sqlx4kSqliteCipherPtr { ptr: callback };
    let stream = Sqlx4kSqliteCipherPtr { ptr: stream };
    let runtime = RUNTIME.get().unwrap();
    let sqlx4k = unsafe { &*(rt as *mut Sqlx4kSqliteCipher) };
    runtime.spawn(async move {
        let result = sqlx4k.stream_next(stream).await;
        fun(callback, result)
    });
}

#[no_mangle]
pub extern "C" fn sqlx4k_sqlite_cipher_stream_close(
    rt: *mut c_void,
    stream: *mut c_void,
    callback: *mut c_void,
    fun: extern "C" fn(Sqlx4kSqliteCipherPtr, *mut Sqlx4kSqliteCipherResult),
) {
    let callback = Sqlx4kSqliteCipherPtr { ptr: callback };
    let stream = Sqlx4kSqliteCipherPtr { ptr: stream };
    let runtime = RUNTIME.get().unwrap();
    let sqlx4k = unsafe { &*(rt as *mut Sqlx4kSqliteCipher) };
    runtime.spawn(async move {
        let result = sqlx4k.stream_close(stream).await;
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
    let owned = match read_params(params, params_len) {
        Ok(owned) => owned,
        Err(err) => return fun(callback, params_error(err, std::ptr::null_mut())),
    };
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
    let owned = match read_params(params, params_len) {
        Ok(owned) => owned,
        Err(err) => return fun(callback, params_error(err, std::ptr::null_mut())),
    };
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
    let owned = match read_params(params, params_len) {
        Ok(owned) => owned,
        Err(err) => return fun(callback, params_error(err, std::ptr::null_mut())),
    };
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
    let owned = match read_params(params, params_len) {
        Ok(owned) => owned,
        Err(err) => return fun(callback, params_error(err, std::ptr::null_mut())),
    };
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
    let owned = match read_params(params, params_len) {
        Ok(owned) => owned,
        Err(err) => return fun(callback, params_error(err, tx.ptr)),
    };
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
    let owned = match read_params(params, params_len) {
        Ok(owned) => owned,
        Err(err) => return fun(callback, params_error(err, tx.ptr)),
    };
    let runtime = RUNTIME.get().unwrap();
    let sqlx4k = unsafe { &*(rt as *mut Sqlx4kSqliteCipher) };
    runtime.spawn(async move {
        let result = sqlx4k.tx_fetch_all_with_params(tx, sql, owned).await;
        fun(callback, result)
    });
}

#[cfg(test)]
mod tests {
    mod pool {
        use crate::core::{ERROR_DATABASE, ERROR_POOL, ERROR_POOL_CLOSED, PARAM_INT, PARAM_TEXT};
        use crate::ffi::*;
        use crate::test_support::{c, call, open, param, text_param, Pool, Res, TempDb};
        use std::time::Duration;

        fn query(rt: *mut c_void, sql: &str) -> Res {
            let sql = c(sql);
            call(|cb, fun| sqlx4k_sqlite_cipher_query(rt, sql.as_ptr(), cb, fun))
        }

        fn fetch_all(rt: *mut c_void, sql: &str) -> Res {
            let sql = c(sql);
            call(|cb, fun| sqlx4k_sqlite_cipher_fetch_all(rt, sql.as_ptr(), cb, fun))
        }

        fn close(rt: *mut c_void) {
            call(|cb, fun| sqlx4k_sqlite_cipher_close(rt, cb, fun)).ok();
        }

        #[test]
        fn an_invalid_url_is_an_error() {
            let db = TempDb::new();
            let r = open(&format!("{}?mode=bogus", db.url()), -1, 1);
            assert_eq!(r.get().error, ERROR_POOL);
            assert!(r.get().rt.is_null());
        }

        #[test]
        fn a_missing_file_is_created_by_default_and_with_mode_rwc() {
            for mode in ["", "?mode=rwc"] {
                let db = TempDb::new();
                let rt = open(&format!("{}{mode}", db.url()), -1, 1).ok().get().rt;
                assert!(db.path.exists(), "{mode}");
                close(rt);
            }
        }

        #[test]
        fn a_missing_file_is_not_created_with_mode_ro_or_rw() {
            for mode in ["ro", "rw"] {
                let db = TempDb::new();
                let r = open(&format!("{}?mode={mode}", db.url()), -1, 1);
                // SQLITE_CANTOPEN
                assert_eq!((r.get().error, r.get().error_native_code), (ERROR_DATABASE, 14), "{mode}");
                assert!(!db.path.exists(), "{mode}");
            }
        }

        #[test]
        fn a_read_only_pool_reads_but_does_not_write() {
            let db = TempDb::new();
            let rt = open(&db.url(), -1, 1).ok().get().rt;
            query(rt, "create table t(id int)").ok();
            query(rt, "insert into t values (1)").ok();
            close(rt);
            let rt = open(&format!("{}?mode=ro", db.url()), -1, 1).ok().get().rt;
            assert_eq!(fetch_all(rt, "select count(*) from t").ok().value().as_deref(), Some("1"));
            // SQLITE_READONLY
            assert_eq!(query(rt, "insert into t values (2)").get().error_native_code, 8);
            close(rt);
        }

        #[test]
        fn a_file_pool_is_in_wal_mode() {
            let pool = Pool::open(1);
            assert_eq!(fetch_all(pool.rt, "pragma journal_mode").ok().value().as_deref(), Some("wal"));
        }

        #[test]
        fn an_in_memory_database_lives_as_long_as_its_pool() {
            let rt = open("sqlite::memory:", 1, 1).ok().get().rt;
            query(rt, "create table t(id int)").ok();
            query(rt, "insert into t values (1)").ok();
            assert_eq!(fetch_all(rt, "select count(*) from t").ok().value().as_deref(), Some("1"));
            close(rt);
        }

        #[test]
        fn a_pool_keeps_its_minimum_of_connections() {
            let db = TempDb::new();
            let rt = open(&db.url(), 2, 3).ok().get().rt;
            for _ in 0..100 {
                if sqlx4k_sqlite_cipher_pool_idle_size(rt) == 2 {
                    break;
                }
                std::thread::sleep(Duration::from_millis(10));
            }
            assert_eq!(sqlx4k_sqlite_cipher_pool_size(rt), 2);
            assert_eq!(sqlx4k_sqlite_cipher_pool_idle_size(rt), 2);
            close(rt);
        }

        #[test]
        fn statements_run_on_the_pool() {
            let pool = Pool::open(2);
            query(pool.rt, "create table t(id integer primary key, v text)").ok();
            let r = query(pool.rt, "insert into t values (1, 'a'), (2, 'b')").ok();
            assert_eq!((r.get().rows_affected, r.get().last_insert_id), (2, 2));
            let v = c("c");
            let params = [text_param(PARAM_TEXT, &v), param(PARAM_INT, |p| p.i64_val = 2)];
            let sql = c("update t set v = ? where id = ?");
            let r = call(|cb, fun| sqlx4k_sqlite_cipher_query_with_params(pool.rt, sql.as_ptr(), params.as_ptr(), 2, cb, fun)).ok();
            assert_eq!(r.get().rows_affected, 1);
            let sql = c("select v from t where id >= ? order by id");
            let params = [param(PARAM_INT, |p| p.i64_val = 1)];
            let r = call(|cb, fun| sqlx4k_sqlite_cipher_fetch_all_with_params(pool.rt, sql.as_ptr(), params.as_ptr(), 1, cb, fun)).ok();
            assert_eq!(r.rows(), [[Some("a".into())], [Some("c".into())]]);
            assert_eq!(fetch_all(pool.rt, "select count(*) from t").ok().value().as_deref(), Some("2"));
        }

        #[test]
        fn a_failing_statement_is_an_error() {
            let pool = Pool::open(1);
            let r = fetch_all(pool.rt, "select * from missing_table");
            assert_eq!((r.get().error, r.get().error_native_code), (ERROR_DATABASE, 1));
            assert!(r.get().error_sql_state.is_null());
        }

        #[test]
        fn a_closed_pool_runs_nothing() {
            let pool = Pool::open(1);
            close(pool.rt);
            assert_eq!(query(pool.rt, "select 1").get().error, ERROR_POOL_CLOSED);
            assert_eq!(fetch_all(pool.rt, "select 1").get().error, ERROR_POOL_CLOSED);
        }
    }

    mod connection {
        use crate::core::{ERROR_DATABASE, PARAM_INT};
        use crate::ffi::*;
        use crate::test_support::{c, call, param, Pool, Res};

        fn cn_query(pool: &Pool, cn: *mut c_void, sql: &str) -> Res {
            let sql = c(sql);
            call(|cb, fun| sqlx4k_sqlite_cipher_cn_query(pool.rt, cn, sql.as_ptr(), cb, fun))
        }

        fn cn_fetch_all(pool: &Pool, cn: *mut c_void, sql: &str) -> Res {
            let sql = c(sql);
            call(|cb, fun| sqlx4k_sqlite_cipher_cn_fetch_all(pool.rt, cn, sql.as_ptr(), cb, fun))
        }

        #[test]
        fn statements_run_on_the_connection_acquired() {
            let pool = Pool::open(2);
            let cn = call(|cb, fun| sqlx4k_sqlite_cipher_cn_acquire(pool.rt, cb, fun)).ok().get().cn;
            assert!(!cn.is_null());
            // A temporary table is the connection's own: the statements after see it.
            cn_query(&pool, cn, "create temp table t(id integer primary key)").ok();
            let r = cn_query(&pool, cn, "insert into t values (1), (2), (3)").ok();
            assert_eq!((r.get().rows_affected, r.get().last_insert_id), (3, 3));
            let params = [param(PARAM_INT, |p| p.i64_val = 2)];
            let sql = c("delete from t where id = ?");
            let r = call(|cb, fun| sqlx4k_sqlite_cipher_cn_query_with_params(pool.rt, cn, sql.as_ptr(), params.as_ptr(), 1, cb, fun)).ok();
            assert_eq!(r.get().rows_affected, 1);
            let sql = c("select id from t where id > ? order by id");
            let params = [param(PARAM_INT, |p| p.i64_val = 0)];
            let r = call(|cb, fun| sqlx4k_sqlite_cipher_cn_fetch_all_with_params(pool.rt, cn, sql.as_ptr(), params.as_ptr(), 1, cb, fun)).ok();
            assert_eq!(r.rows(), [[Some("1".into())], [Some("3".into())]]);
            assert_eq!(cn_fetch_all(&pool, cn, "select count(*) from t").ok().value().as_deref(), Some("2"));
            call(|cb, fun| sqlx4k_sqlite_cipher_cn_release(pool.rt, cn, cb, fun)).ok();
        }

        #[test]
        fn a_transaction_is_begun_on_the_connection() {
            let pool = Pool::open(2);
            let cn = call(|cb, fun| sqlx4k_sqlite_cipher_cn_acquire(pool.rt, cb, fun)).ok().get().cn;
            let tx = call(|cb, fun| sqlx4k_sqlite_cipher_cn_tx_begin(pool.rt, cn, cb, fun)).ok().get().tx;
            assert!(!tx.is_null());
            call(|cb, fun| sqlx4k_sqlite_cipher_tx_commit(pool.rt, tx, cb, fun)).ok();
            call(|cb, fun| sqlx4k_sqlite_cipher_cn_release(pool.rt, cn, cb, fun)).ok();
        }

        #[test]
        fn a_failing_statement_leaves_the_connection_usable() {
            let pool = Pool::open(1);
            let cn = call(|cb, fun| sqlx4k_sqlite_cipher_cn_acquire(pool.rt, cb, fun)).ok().get().cn;
            assert_eq!(cn_fetch_all(&pool, cn, "select * from missing_table").get().error, ERROR_DATABASE);
            assert_eq!(cn_fetch_all(&pool, cn, "select 1").ok().value().as_deref(), Some("1"));
            call(|cb, fun| sqlx4k_sqlite_cipher_cn_release(pool.rt, cn, cb, fun)).ok();
        }
    }

    mod transaction {
        use crate::core::{ERROR_DATABASE, PARAM_INT};
        use crate::ffi::*;
        use crate::test_support::{c, call, param, Pool, Res};

        fn tx_query(pool: &Pool, tx: *mut c_void, sql: &str) -> Res {
            let sql = c(sql);
            call(|cb, fun| sqlx4k_sqlite_cipher_tx_query(pool.rt, tx, sql.as_ptr(), cb, fun))
        }

        fn count(pool: &Pool) -> Option<String> {
            let sql = c("select count(*) from t");
            call(|cb, fun| sqlx4k_sqlite_cipher_fetch_all(pool.rt, sql.as_ptr(), cb, fun)).ok().value()
        }

        fn create(pool: &Pool) {
            let sql = c("create table t(id integer primary key)");
            call(|cb, fun| sqlx4k_sqlite_cipher_query(pool.rt, sql.as_ptr(), cb, fun)).ok();
        }

        #[test]
        fn the_statements_of_a_transaction_are_committed_together() {
            let pool = Pool::open(2);
            create(&pool);
            let tx = call(|cb, fun| sqlx4k_sqlite_cipher_tx_begin(pool.rt, cb, fun)).ok().get().tx;
            // The transaction moves into each call and comes back in its result.
            let tx = tx_query(&pool, tx, "insert into t values (1)").ok().get().tx;
            let sql = c("insert into t values (?)");
            let params = [param(PARAM_INT, |p| p.i64_val = 2)];
            let r = call(|cb, fun| sqlx4k_sqlite_cipher_tx_query_with_params(pool.rt, tx, sql.as_ptr(), params.as_ptr(), 1, cb, fun)).ok();
            assert_eq!((r.get().rows_affected, r.get().last_insert_id), (1, 2));
            let tx = r.get().tx;
            let sql = c("select id from t where id >= ? order by id");
            let params = [param(PARAM_INT, |p| p.i64_val = 1)];
            let r = call(|cb, fun| sqlx4k_sqlite_cipher_tx_fetch_all_with_params(pool.rt, tx, sql.as_ptr(), params.as_ptr(), 1, cb, fun)).ok();
            assert_eq!(r.rows(), [[Some("1".into())], [Some("2".into())]]);
            let tx = r.get().tx;
            // Not committed yet: another connection sees none.
            assert_eq!(count(&pool).as_deref(), Some("0"));
            let sql = c("select count(*) from t");
            let r = call(|cb, fun| sqlx4k_sqlite_cipher_tx_fetch_all(pool.rt, tx, sql.as_ptr(), cb, fun)).ok();
            assert_eq!(r.value().as_deref(), Some("2"));
            let tx = r.get().tx;
            call(|cb, fun| sqlx4k_sqlite_cipher_tx_commit(pool.rt, tx, cb, fun)).ok();
            assert_eq!(count(&pool).as_deref(), Some("2"));
        }

        #[test]
        fn a_transaction_rolled_back_leaves_nothing() {
            let pool = Pool::open(2);
            create(&pool);
            let tx = call(|cb, fun| sqlx4k_sqlite_cipher_tx_begin(pool.rt, cb, fun)).ok().get().tx;
            let tx = tx_query(&pool, tx, "insert into t values (1)").ok().get().tx;
            call(|cb, fun| sqlx4k_sqlite_cipher_tx_rollback(pool.rt, tx, cb, fun)).ok();
            assert_eq!(count(&pool).as_deref(), Some("0"));
        }

        #[test]
        fn a_parameter_of_an_unknown_kind_is_an_error_handing_the_transaction_back() {
            let pool = Pool::open(1);
            let tx = call(|cb, fun| sqlx4k_sqlite_cipher_tx_begin(pool.rt, cb, fun)).ok().get().tx;
            let sql = c("select ?");
            let params = [param(9, |_| {})];
            let r = call(|cb, fun| sqlx4k_sqlite_cipher_tx_fetch_all_with_params(pool.rt, tx, sql.as_ptr(), params.as_ptr(), 1, cb, fun));
            assert_eq!(r.get().error, ERROR_DATABASE);
            assert!(r.error_message().unwrap().contains("kind: 9"), "{:?}", r.error_message());
            assert_eq!(r.get().tx, tx);
            call(|cb, fun| sqlx4k_sqlite_cipher_tx_rollback(pool.rt, tx, cb, fun)).ok();
        }

        #[test]
        fn a_failing_statement_hands_the_transaction_back() {
            let pool = Pool::open(1);
            let tx = call(|cb, fun| sqlx4k_sqlite_cipher_tx_begin(pool.rt, cb, fun)).ok().get().tx;
            let r = tx_query(&pool, tx, "select * from missing_table");
            assert_eq!(r.get().error, ERROR_DATABASE);
            let tx = r.get().tx;
            assert!(!tx.is_null());
            // SQLite keeps the transaction going after a failed statement.
            let tx = tx_query(&pool, tx, "select 1").ok().get().tx;
            call(|cb, fun| sqlx4k_sqlite_cipher_tx_rollback(pool.rt, tx, cb, fun)).ok();
        }
    }

    mod stream {
        use crate::core::{ERROR_DATABASE, PARAM_INT};
        use crate::ffi::*;
        use crate::test_support::{c, call, param, Pool};
        use std::ptr::null;

        const SERIES: &str = "with recursive s(n) as (select 1 union all select n + 1 from s where n < 5) select n from s";

        /// Reads a stream to its end and closes it: the rows of each chunk.
        fn read(pool: &Pool, stream: *mut c_void) -> Vec<Vec<Vec<Option<String>>>> {
            let mut chunks = Vec::new();
            loop {
                let chunk = call(|cb, fun| sqlx4k_sqlite_cipher_stream_next(pool.rt, stream, cb, fun)).ok();
                if chunk.get().size == 0 {
                    break;
                }
                chunks.push(chunk.rows());
            }
            call(|cb, fun| sqlx4k_sqlite_cipher_stream_close(pool.rt, stream, cb, fun)).ok();
            chunks
        }

        #[test]
        fn the_rows_of_a_pool_stream_come_in_chunks() {
            let pool = Pool::open(1);
            let sql = c(SERIES);
            let stream = call(|cb, fun| sqlx4k_sqlite_cipher_stream_open(pool.rt, sql.as_ptr(), null(), 0, 2, cb, fun)).ok().get().stream;
            let chunks = read(&pool, stream);
            assert_eq!(chunks.iter().map(Vec::len).collect::<Vec<_>>(), [2, 2, 1]);
            let values: Vec<String> = chunks.concat().into_iter().map(|r| r[0].clone().unwrap()).collect();
            assert_eq!(values, ["1", "2", "3", "4", "5"]);
        }

        #[test]
        fn a_prepared_statement_is_streamed() {
            let pool = Pool::open(1);
            let sql = c("select ? as a union all select ? union all select ?");
            let params = [param(PARAM_INT, |p| p.i64_val = 1), param(PARAM_INT, |p| p.i64_val = 2), param(PARAM_INT, |p| p.i64_val = 3)];
            let stream = call(|cb, fun| sqlx4k_sqlite_cipher_stream_open(pool.rt, sql.as_ptr(), params.as_ptr(), 3, 10, cb, fun))
                .ok()
                .get()
                .stream;
            assert_eq!(read(&pool, stream).concat().len(), 3);
        }

        #[test]
        fn a_failing_query_fails_its_first_chunk() {
            let pool = Pool::open(1);
            let sql = c("select * from missing_table");
            let stream = call(|cb, fun| sqlx4k_sqlite_cipher_stream_open(pool.rt, sql.as_ptr(), null(), 0, 10, cb, fun)).ok().get().stream;
            let chunk = call(|cb, fun| sqlx4k_sqlite_cipher_stream_next(pool.rt, stream, cb, fun));
            assert_eq!(chunk.get().error, ERROR_DATABASE);
            call(|cb, fun| sqlx4k_sqlite_cipher_stream_close(pool.rt, stream, cb, fun)).ok();
        }

        #[test]
        fn a_stream_of_a_connection_leaves_it_usable_also_when_closed_early() {
            let pool = Pool::open(1);
            let cn = call(|cb, fun| sqlx4k_sqlite_cipher_cn_acquire(pool.rt, cb, fun)).ok().get().cn;
            let sql = c("create temp table t as with recursive s(n) as (select 1 union all select n + 1 from s where n < 1000) select n from s");
            call(|cb, fun| sqlx4k_sqlite_cipher_cn_query(pool.rt, cn, sql.as_ptr(), cb, fun)).ok();
            let sql = c("select n from t order by n");
            let stream = call(|cb, fun| sqlx4k_sqlite_cipher_cn_stream_open(pool.rt, cn, sql.as_ptr(), null(), 0, 10, cb, fun))
                .ok()
                .get()
                .stream;
            let first = call(|cb, fun| sqlx4k_sqlite_cipher_stream_next(pool.rt, stream, cb, fun)).ok();
            assert_eq!(first.rows()[0], [Some("1".into())]);
            call(|cb, fun| sqlx4k_sqlite_cipher_stream_close(pool.rt, stream, cb, fun)).ok();
            let sql = c("select count(*) from t");
            let r = call(|cb, fun| sqlx4k_sqlite_cipher_cn_fetch_all(pool.rt, cn, sql.as_ptr(), cb, fun)).ok();
            assert_eq!(r.value().as_deref(), Some("1000"));
            call(|cb, fun| sqlx4k_sqlite_cipher_cn_release(pool.rt, cn, cb, fun)).ok();
        }

        #[test]
        fn a_stream_of_a_transaction_sees_its_writes() {
            let pool = Pool::open(1);
            let tx = call(|cb, fun| sqlx4k_sqlite_cipher_tx_begin(pool.rt, cb, fun)).ok().get().tx;
            let sql = c(&format!("create table t as {SERIES}"));
            let tx = call(|cb, fun| sqlx4k_sqlite_cipher_tx_query(pool.rt, tx, sql.as_ptr(), cb, fun)).ok().get().tx;
            let sql = c("select n from t");
            // The stream borrows the transaction in place: the same pointer is used after it.
            let stream = call(|cb, fun| sqlx4k_sqlite_cipher_tx_stream_open(pool.rt, tx, sql.as_ptr(), null(), 0, 10, cb, fun))
                .ok()
                .get()
                .stream;
            assert_eq!(read(&pool, stream).concat().len(), 5);
            call(|cb, fun| sqlx4k_sqlite_cipher_tx_commit(pool.rt, tx, cb, fun)).ok();
        }
    }
}
