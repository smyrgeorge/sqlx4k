//! Exports acting on a connection acquired from the pool.

use std::ffi::{c_char, c_int, c_void};

use crate::client::Sqlx4kMySql;
use crate::params::{read_params, Sqlx4kMysqlParam};
use crate::result::c_chars_to_str_mysql;
use crate::types::{Sqlx4kMysqlPtr, Sqlx4kMysqlResult};
use crate::RUNTIME;

#[no_mangle]
pub extern "C" fn sqlx4k_mysql_cn_acquire(
    rt: *mut c_void,
    callback: *mut c_void,
    fun: extern "C" fn(Sqlx4kMysqlPtr, *mut Sqlx4kMysqlResult),
) {
    let callback = Sqlx4kMysqlPtr { ptr: callback };
    let runtime = RUNTIME.get().unwrap();
    let sqlx4k = unsafe { &*(rt as *mut Sqlx4kMySql) };
    runtime.spawn(async move {
        let result = sqlx4k.cn_acquire().await;
        fun(callback, result)
    });
}

#[no_mangle]
pub extern "C" fn sqlx4k_mysql_cn_release(
    rt: *mut c_void,
    cn: *mut c_void,
    callback: *mut c_void,
    fun: extern "C" fn(Sqlx4kMysqlPtr, *mut Sqlx4kMysqlResult),
) {
    let cn = Sqlx4kMysqlPtr { ptr: cn };
    let callback = Sqlx4kMysqlPtr { ptr: callback };
    let runtime = RUNTIME.get().unwrap();
    let sqlx4k = unsafe { &*(rt as *mut Sqlx4kMySql) };
    runtime.spawn(async move {
        let result = sqlx4k.cn_release(cn).await;
        fun(callback, result)
    });
}

#[no_mangle]
pub extern "C" fn sqlx4k_mysql_cn_query(
    rt: *mut c_void,
    cn: *mut c_void,
    sql: *const c_char,
    callback: *mut c_void,
    fun: extern "C" fn(Sqlx4kMysqlPtr, *mut Sqlx4kMysqlResult),
) {
    let cn = Sqlx4kMysqlPtr { ptr: cn };
    let callback = Sqlx4kMysqlPtr { ptr: callback };
    let sql = c_chars_to_str_mysql(sql).to_owned();
    let runtime = RUNTIME.get().unwrap();
    let sqlx4k = unsafe { &*(rt as *mut Sqlx4kMySql) };
    runtime.spawn(async move {
        let result = sqlx4k.cn_query(cn, sql).await;
        fun(callback, result)
    });
}

#[no_mangle]
pub extern "C" fn sqlx4k_mysql_cn_fetch_all(
    rt: *mut c_void,
    cn: *mut c_void,
    sql: *const c_char,
    callback: *mut c_void,
    fun: extern "C" fn(Sqlx4kMysqlPtr, *mut Sqlx4kMysqlResult),
) {
    let cn = Sqlx4kMysqlPtr { ptr: cn };
    let callback = Sqlx4kMysqlPtr { ptr: callback };
    let sql = c_chars_to_str_mysql(sql).to_owned();
    let runtime = RUNTIME.get().unwrap();
    let sqlx4k = unsafe { &*(rt as *mut Sqlx4kMySql) };
    runtime.spawn(async move {
        let result = sqlx4k.cn_fetch_all(cn, sql).await;
        fun(callback, result)
    });
}

#[no_mangle]
pub extern "C" fn sqlx4k_mysql_cn_tx_begin(
    rt: *mut c_void,
    cn: *mut c_void,
    callback: *mut c_void,
    fun: extern "C" fn(Sqlx4kMysqlPtr, *mut Sqlx4kMysqlResult),
) {
    let cn = Sqlx4kMysqlPtr { ptr: cn };
    let callback = Sqlx4kMysqlPtr { ptr: callback };
    let runtime = RUNTIME.get().unwrap();
    let sqlx4k = unsafe { &*(rt as *mut Sqlx4kMySql) };
    runtime.spawn(async move {
        let result = sqlx4k.cn_tx_begin(cn).await;
        fun(callback, result)
    });
}

#[no_mangle]
pub extern "C" fn sqlx4k_mysql_cn_query_with_params(
    rt: *mut c_void,
    cn: *mut c_void,
    sql: *const c_char,
    params: *const Sqlx4kMysqlParam,
    params_len: c_int,
    callback: *mut c_void,
    fun: extern "C" fn(Sqlx4kMysqlPtr, *mut Sqlx4kMysqlResult),
) {
    let cn = Sqlx4kMysqlPtr { ptr: cn };
    let callback = Sqlx4kMysqlPtr { ptr: callback };
    let sql = c_chars_to_str_mysql(sql).to_owned();
    let owned = read_params(params, params_len);
    let runtime = RUNTIME.get().unwrap();
    let sqlx4k = unsafe { &*(rt as *mut Sqlx4kMySql) };
    runtime.spawn(async move {
        let result = sqlx4k.cn_query_with_params(cn, sql, owned).await;
        fun(callback, result)
    });
}

#[no_mangle]
pub extern "C" fn sqlx4k_mysql_cn_fetch_all_with_params(
    rt: *mut c_void,
    cn: *mut c_void,
    sql: *const c_char,
    params: *const Sqlx4kMysqlParam,
    params_len: c_int,
    callback: *mut c_void,
    fun: extern "C" fn(Sqlx4kMysqlPtr, *mut Sqlx4kMysqlResult),
) {
    let cn = Sqlx4kMysqlPtr { ptr: cn };
    let callback = Sqlx4kMysqlPtr { ptr: callback };
    let sql = c_chars_to_str_mysql(sql).to_owned();
    let owned = read_params(params, params_len);
    let runtime = RUNTIME.get().unwrap();
    let sqlx4k = unsafe { &*(rt as *mut Sqlx4kMySql) };
    runtime.spawn(async move {
        let result = sqlx4k.cn_fetch_all_with_params(cn, sql, owned).await;
        fun(callback, result)
    });
}
