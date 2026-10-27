//! Exports acting on a transaction.

use std::ffi::{c_char, c_int, c_void};

use crate::client::Sqlx4kMySql;
use crate::params::{read_params, Sqlx4kMysqlParam};
use crate::result::c_chars_to_str_mysql;
use crate::types::{Sqlx4kMysqlPtr, Sqlx4kMysqlResult};
use crate::RUNTIME;

#[no_mangle]
pub extern "C" fn sqlx4k_mysql_tx_begin(
    rt: *mut c_void,
    callback: *mut c_void,
    fun: extern "C" fn(Sqlx4kMysqlPtr, *mut Sqlx4kMysqlResult),
) {
    let callback = Sqlx4kMysqlPtr { ptr: callback };
    let runtime = RUNTIME.get().unwrap();
    let sqlx4k = unsafe { &*(rt as *mut Sqlx4kMySql) };
    runtime.spawn(async move {
        let result = sqlx4k.tx_begin().await;
        fun(callback, result)
    });
}

#[no_mangle]
pub extern "C" fn sqlx4k_mysql_tx_commit(
    rt: *mut c_void,
    tx: *mut c_void,
    callback: *mut c_void,
    fun: extern "C" fn(Sqlx4kMysqlPtr, *mut Sqlx4kMysqlResult),
) {
    let tx = Sqlx4kMysqlPtr { ptr: tx };
    let callback = Sqlx4kMysqlPtr { ptr: callback };
    let runtime = RUNTIME.get().unwrap();
    let sqlx4k = unsafe { &*(rt as *mut Sqlx4kMySql) };
    runtime.spawn(async move {
        let result = sqlx4k.tx_commit(tx).await;
        fun(callback, result)
    });
}

#[no_mangle]
pub extern "C" fn sqlx4k_mysql_tx_rollback(
    rt: *mut c_void,
    tx: *mut c_void,
    callback: *mut c_void,
    fun: extern "C" fn(Sqlx4kMysqlPtr, *mut Sqlx4kMysqlResult),
) {
    let tx = Sqlx4kMysqlPtr { ptr: tx };
    let callback = Sqlx4kMysqlPtr { ptr: callback };
    let runtime = RUNTIME.get().unwrap();
    let sqlx4k = unsafe { &*(rt as *mut Sqlx4kMySql) };
    runtime.spawn(async move {
        let result = sqlx4k.tx_rollback(tx).await;
        fun(callback, result)
    });
}

#[no_mangle]
pub extern "C" fn sqlx4k_mysql_tx_query(
    rt: *mut c_void,
    tx: *mut c_void,
    sql: *const c_char,
    callback: *mut c_void,
    fun: extern "C" fn(Sqlx4kMysqlPtr, *mut Sqlx4kMysqlResult),
) {
    let tx = Sqlx4kMysqlPtr { ptr: tx };
    let callback = Sqlx4kMysqlPtr { ptr: callback };
    let sql = c_chars_to_str_mysql(sql).to_owned();
    let runtime = RUNTIME.get().unwrap();
    let sqlx4k = unsafe { &*(rt as *mut Sqlx4kMySql) };
    runtime.spawn(async move {
        let result = sqlx4k.tx_query(tx, sql).await;
        fun(callback, result)
    });
}

#[no_mangle]
pub extern "C" fn sqlx4k_mysql_tx_fetch_all(
    rt: *mut c_void,
    tx: *mut c_void,
    sql: *const c_char,
    callback: *mut c_void,
    fun: extern "C" fn(Sqlx4kMysqlPtr, *mut Sqlx4kMysqlResult),
) {
    let tx = Sqlx4kMysqlPtr { ptr: tx };
    let callback = Sqlx4kMysqlPtr { ptr: callback };
    let sql = c_chars_to_str_mysql(sql).to_owned();
    let runtime = RUNTIME.get().unwrap();
    let sqlx4k = unsafe { &*(rt as *mut Sqlx4kMySql) };
    runtime.spawn(async move {
        let result = sqlx4k.tx_fetch_all(tx, sql).await;
        fun(callback, result)
    });
}

#[no_mangle]
pub extern "C" fn sqlx4k_mysql_tx_query_with_params(
    rt: *mut c_void,
    tx: *mut c_void,
    sql: *const c_char,
    params: *const Sqlx4kMysqlParam,
    params_len: c_int,
    callback: *mut c_void,
    fun: extern "C" fn(Sqlx4kMysqlPtr, *mut Sqlx4kMysqlResult),
) {
    let tx = Sqlx4kMysqlPtr { ptr: tx };
    let callback = Sqlx4kMysqlPtr { ptr: callback };
    let sql = c_chars_to_str_mysql(sql).to_owned();
    let owned = read_params(params, params_len);
    let runtime = RUNTIME.get().unwrap();
    let sqlx4k = unsafe { &*(rt as *mut Sqlx4kMySql) };
    runtime.spawn(async move {
        let result = sqlx4k.tx_query_with_params(tx, sql, owned).await;
        fun(callback, result)
    });
}

#[no_mangle]
pub extern "C" fn sqlx4k_mysql_tx_fetch_all_with_params(
    rt: *mut c_void,
    tx: *mut c_void,
    sql: *const c_char,
    params: *const Sqlx4kMysqlParam,
    params_len: c_int,
    callback: *mut c_void,
    fun: extern "C" fn(Sqlx4kMysqlPtr, *mut Sqlx4kMysqlResult),
) {
    let tx = Sqlx4kMysqlPtr { ptr: tx };
    let callback = Sqlx4kMysqlPtr { ptr: callback };
    let sql = c_chars_to_str_mysql(sql).to_owned();
    let owned = read_params(params, params_len);
    let runtime = RUNTIME.get().unwrap();
    let sqlx4k = unsafe { &*(rt as *mut Sqlx4kMySql) };
    runtime.spawn(async move {
        let result = sqlx4k.tx_fetch_all_with_params(tx, sql, owned).await;
        fun(callback, result)
    });
}
