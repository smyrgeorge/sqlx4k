//! Exports acting on a connection acquired from the pool.

use std::ffi::{c_char, c_int, c_void};

use crate::client::Sqlx4kPostgreSql;
use crate::params::{params_error, read_params, Sqlx4kPostgresParam};
use crate::result::c_chars_to_str_postgres;
use crate::types::{Sqlx4kPostgresPtr, Sqlx4kPostgresResult};
use crate::RUNTIME;

#[no_mangle]
pub extern "C" fn sqlx4k_postgresql_cn_acquire(
    rt: *mut c_void,
    callback: *mut c_void,
    fun: extern "C" fn(Sqlx4kPostgresPtr, *mut Sqlx4kPostgresResult),
) {
    let callback = Sqlx4kPostgresPtr { ptr: callback };
    let runtime = RUNTIME.get().unwrap();
    let sqlx4k = unsafe { &*(rt as *mut Sqlx4kPostgreSql) };
    runtime.spawn(async move {
        let result = sqlx4k.cn_acquire().await;
        fun(callback, result)
    });
}

#[no_mangle]
pub extern "C" fn sqlx4k_postgresql_cn_release(
    rt: *mut c_void,
    cn: *mut c_void,
    callback: *mut c_void,
    fun: extern "C" fn(Sqlx4kPostgresPtr, *mut Sqlx4kPostgresResult),
) {
    let cn = Sqlx4kPostgresPtr { ptr: cn };
    let callback = Sqlx4kPostgresPtr { ptr: callback };
    let runtime = RUNTIME.get().unwrap();
    let sqlx4k = unsafe { &*(rt as *mut Sqlx4kPostgreSql) };
    runtime.spawn(async move {
        let result = sqlx4k.cn_release(cn).await;
        fun(callback, result)
    });
}

#[no_mangle]
pub extern "C" fn sqlx4k_postgresql_cn_query(
    rt: *mut c_void,
    cn: *mut c_void,
    sql: *const c_char,
    callback: *mut c_void,
    fun: extern "C" fn(Sqlx4kPostgresPtr, *mut Sqlx4kPostgresResult),
) {
    let cn = Sqlx4kPostgresPtr { ptr: cn };
    let callback = Sqlx4kPostgresPtr { ptr: callback };
    let sql = c_chars_to_str_postgres(sql).to_owned();
    let runtime = RUNTIME.get().unwrap();
    let sqlx4k = unsafe { &*(rt as *mut Sqlx4kPostgreSql) };
    runtime.spawn(async move {
        let result = sqlx4k.cn_query(cn, sql).await;
        fun(callback, result)
    });
}

#[no_mangle]
pub extern "C" fn sqlx4k_postgresql_cn_fetch_all(
    rt: *mut c_void,
    cn: *mut c_void,
    sql: *const c_char,
    callback: *mut c_void,
    fun: extern "C" fn(Sqlx4kPostgresPtr, *mut Sqlx4kPostgresResult),
) {
    let cn = Sqlx4kPostgresPtr { ptr: cn };
    let callback = Sqlx4kPostgresPtr { ptr: callback };
    let sql = c_chars_to_str_postgres(sql).to_owned();
    let runtime = RUNTIME.get().unwrap();
    let sqlx4k = unsafe { &*(rt as *mut Sqlx4kPostgreSql) };
    runtime.spawn(async move {
        let result = sqlx4k.cn_fetch_all(cn, sql).await;
        fun(callback, result)
    });
}

#[no_mangle]
pub extern "C" fn sqlx4k_postgresql_cn_tx_begin(
    rt: *mut c_void,
    cn: *mut c_void,
    callback: *mut c_void,
    fun: extern "C" fn(Sqlx4kPostgresPtr, *mut Sqlx4kPostgresResult),
) {
    let cn = Sqlx4kPostgresPtr { ptr: cn };
    let callback = Sqlx4kPostgresPtr { ptr: callback };
    let runtime = RUNTIME.get().unwrap();
    let sqlx4k = unsafe { &*(rt as *mut Sqlx4kPostgreSql) };
    runtime.spawn(async move {
        let result = sqlx4k.cn_tx_begin(cn).await;
        fun(callback, result)
    });
}

#[no_mangle]
pub extern "C" fn sqlx4k_postgresql_cn_query_with_params(
    rt: *mut c_void,
    cn: *mut c_void,
    sql: *const c_char,
    params: *const Sqlx4kPostgresParam,
    params_len: c_int,
    callback: *mut c_void,
    fun: extern "C" fn(Sqlx4kPostgresPtr, *mut Sqlx4kPostgresResult),
) {
    let cn = Sqlx4kPostgresPtr { ptr: cn };
    let callback = Sqlx4kPostgresPtr { ptr: callback };
    let sql = c_chars_to_str_postgres(sql).to_owned();
    let owned = match read_params(params, params_len) {
        Ok(owned) => owned,
        Err(err) => return fun(callback, params_error(err, std::ptr::null_mut())),
    };
    let runtime = RUNTIME.get().unwrap();
    let sqlx4k = unsafe { &*(rt as *mut Sqlx4kPostgreSql) };
    runtime.spawn(async move {
        let result = sqlx4k.cn_query_with_params(cn, sql, owned).await;
        fun(callback, result)
    });
}

#[no_mangle]
pub extern "C" fn sqlx4k_postgresql_cn_fetch_all_with_params(
    rt: *mut c_void,
    cn: *mut c_void,
    sql: *const c_char,
    params: *const Sqlx4kPostgresParam,
    params_len: c_int,
    callback: *mut c_void,
    fun: extern "C" fn(Sqlx4kPostgresPtr, *mut Sqlx4kPostgresResult),
) {
    let cn = Sqlx4kPostgresPtr { ptr: cn };
    let callback = Sqlx4kPostgresPtr { ptr: callback };
    let sql = c_chars_to_str_postgres(sql).to_owned();
    let owned = match read_params(params, params_len) {
        Ok(owned) => owned,
        Err(err) => return fun(callback, params_error(err, std::ptr::null_mut())),
    };
    let runtime = RUNTIME.get().unwrap();
    let sqlx4k = unsafe { &*(rt as *mut Sqlx4kPostgreSql) };
    runtime.spawn(async move {
        let result = sqlx4k.cn_fetch_all_with_params(cn, sql, owned).await;
        fun(callback, result)
    });
}
