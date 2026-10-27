//! Exports acting on a transaction.

use std::ffi::{c_char, c_int, c_void};

use crate::client::Sqlx4kPostgreSql;
use crate::params::{params_error, read_params, Sqlx4kPostgresParam};
use crate::result::c_chars_to_str_postgres;
use crate::types::{Sqlx4kPostgresPtr, Sqlx4kPostgresResult};
use crate::RUNTIME;

#[no_mangle]
pub extern "C" fn sqlx4k_postgresql_tx_begin(
    rt: *mut c_void,
    callback: *mut c_void,
    fun: extern "C" fn(Sqlx4kPostgresPtr, *mut Sqlx4kPostgresResult),
) {
    let callback = Sqlx4kPostgresPtr { ptr: callback };
    let runtime = RUNTIME.get().unwrap();
    let sqlx4k = unsafe { &*(rt as *mut Sqlx4kPostgreSql) };
    runtime.spawn(async move {
        let result = sqlx4k.tx_begin().await;
        fun(callback, result)
    });
}

#[no_mangle]
pub extern "C" fn sqlx4k_postgresql_tx_commit(
    rt: *mut c_void,
    tx: *mut c_void,
    callback: *mut c_void,
    fun: extern "C" fn(Sqlx4kPostgresPtr, *mut Sqlx4kPostgresResult),
) {
    let tx = Sqlx4kPostgresPtr { ptr: tx };
    let callback = Sqlx4kPostgresPtr { ptr: callback };
    let runtime = RUNTIME.get().unwrap();
    let sqlx4k = unsafe { &*(rt as *mut Sqlx4kPostgreSql) };
    runtime.spawn(async move {
        let result = sqlx4k.tx_commit(tx).await;
        fun(callback, result)
    });
}

#[no_mangle]
pub extern "C" fn sqlx4k_postgresql_tx_rollback(
    rt: *mut c_void,
    tx: *mut c_void,
    callback: *mut c_void,
    fun: extern "C" fn(Sqlx4kPostgresPtr, *mut Sqlx4kPostgresResult),
) {
    let tx = Sqlx4kPostgresPtr { ptr: tx };
    let callback = Sqlx4kPostgresPtr { ptr: callback };
    let runtime = RUNTIME.get().unwrap();
    let sqlx4k = unsafe { &*(rt as *mut Sqlx4kPostgreSql) };
    runtime.spawn(async move {
        let result = sqlx4k.tx_rollback(tx).await;
        fun(callback, result)
    });
}

#[no_mangle]
pub extern "C" fn sqlx4k_postgresql_tx_query(
    rt: *mut c_void,
    tx: *mut c_void,
    sql: *const c_char,
    callback: *mut c_void,
    fun: extern "C" fn(Sqlx4kPostgresPtr, *mut Sqlx4kPostgresResult),
) {
    let tx = Sqlx4kPostgresPtr { ptr: tx };
    let callback = Sqlx4kPostgresPtr { ptr: callback };
    let sql = c_chars_to_str_postgres(sql).to_owned();
    let runtime = RUNTIME.get().unwrap();
    let sqlx4k = unsafe { &*(rt as *mut Sqlx4kPostgreSql) };
    runtime.spawn(async move {
        let result = sqlx4k.tx_query(tx, sql).await;
        fun(callback, result)
    });
}

#[no_mangle]
pub extern "C" fn sqlx4k_postgresql_tx_fetch_all(
    rt: *mut c_void,
    tx: *mut c_void,
    sql: *const c_char,
    callback: *mut c_void,
    fun: extern "C" fn(Sqlx4kPostgresPtr, *mut Sqlx4kPostgresResult),
) {
    let tx = Sqlx4kPostgresPtr { ptr: tx };
    let callback = Sqlx4kPostgresPtr { ptr: callback };
    let sql = c_chars_to_str_postgres(sql).to_owned();
    let runtime = RUNTIME.get().unwrap();
    let sqlx4k = unsafe { &*(rt as *mut Sqlx4kPostgreSql) };
    runtime.spawn(async move {
        let result = sqlx4k.tx_fetch_all(tx, sql).await;
        fun(callback, result)
    });
}

#[no_mangle]
pub extern "C" fn sqlx4k_postgresql_tx_query_with_params(
    rt: *mut c_void,
    tx: *mut c_void,
    sql: *const c_char,
    params: *const Sqlx4kPostgresParam,
    params_len: c_int,
    callback: *mut c_void,
    fun: extern "C" fn(Sqlx4kPostgresPtr, *mut Sqlx4kPostgresResult),
) {
    let tx = Sqlx4kPostgresPtr { ptr: tx };
    let callback = Sqlx4kPostgresPtr { ptr: callback };
    let sql = c_chars_to_str_postgres(sql).to_owned();
    let owned = match read_params(params, params_len) {
        Ok(owned) => owned,
        Err(err) => return fun(callback, params_error(err, tx.ptr)),
    };
    let runtime = RUNTIME.get().unwrap();
    let sqlx4k = unsafe { &*(rt as *mut Sqlx4kPostgreSql) };
    runtime.spawn(async move {
        let result = sqlx4k.tx_query_with_params(tx, sql, owned).await;
        fun(callback, result)
    });
}

#[no_mangle]
pub extern "C" fn sqlx4k_postgresql_tx_fetch_all_with_params(
    rt: *mut c_void,
    tx: *mut c_void,
    sql: *const c_char,
    params: *const Sqlx4kPostgresParam,
    params_len: c_int,
    callback: *mut c_void,
    fun: extern "C" fn(Sqlx4kPostgresPtr, *mut Sqlx4kPostgresResult),
) {
    let tx = Sqlx4kPostgresPtr { ptr: tx };
    let callback = Sqlx4kPostgresPtr { ptr: callback };
    let sql = c_chars_to_str_postgres(sql).to_owned();
    let owned = match read_params(params, params_len) {
        Ok(owned) => owned,
        Err(err) => return fun(callback, params_error(err, tx.ptr)),
    };
    let runtime = RUNTIME.get().unwrap();
    let sqlx4k = unsafe { &*(rt as *mut Sqlx4kPostgreSql) };
    runtime.spawn(async move {
        let result = sqlx4k.tx_fetch_all_with_params(tx, sql, owned).await;
        fun(callback, result)
    });
}
