//! Exports acting on the pool: create and close it, and run queries on it.

use sqlx::postgres::{PgConnectOptions, PgPool, PgPoolOptions};
use std::ffi::{c_char, c_int, c_void};
use std::time::Duration;
use tokio::runtime::Runtime;

use crate::client::Sqlx4kPostgreSql;
use crate::errors::sqlx4k_postgres_error_result_of;
use crate::params::{params_error, read_params, Sqlx4kPostgresParam};
use crate::result::c_chars_to_str_postgres;
use crate::types::{Sqlx4kPostgresPtr, Sqlx4kPostgresResult};
use crate::RUNTIME;

#[no_mangle]
pub extern "C" fn sqlx4k_postgresql_of(
    url: *const c_char,
    username: *const c_char,
    password: *const c_char,
    min_connections: c_int,
    max_connections: c_int,
    acquire_timeout_milis: c_int,
    idle_timeout_milis: c_int,
    max_lifetime_milis: c_int,
) -> *mut Sqlx4kPostgresResult {
    let url = c_chars_to_str_postgres(url);
    let username = c_chars_to_str_postgres(username);
    let password = c_chars_to_str_postgres(password);
    // A malformed URL is reported to the caller (panic = "abort" would take the process down).
    let options: PgConnectOptions = match url.parse() {
        Ok(options) => options,
        Err(err) => return sqlx4k_postgres_error_result_of(err).leak(),
    };
    let options = options.username(username).password(password);

    // Create the tokio runtime, once: get_or_init also when two pools are created concurrently (checking and then
    // setting raced, and the losing set().unwrap() panicked).
    let runtime = RUNTIME.get_or_init(|| Runtime::new().expect("failed to create the tokio runtime"));

    // Create the db pool options.
    let pool = PgPoolOptions::new().max_connections(max_connections as u32);

    let pool = if min_connections > 0 {
        pool.min_connections(min_connections as u32)
    } else {
        pool
    };

    let pool = if acquire_timeout_milis > 0 {
        pool.acquire_timeout(Duration::from_millis(acquire_timeout_milis as u64))
    } else {
        pool
    };

    let pool = if idle_timeout_milis > 0 {
        pool.idle_timeout(Duration::from_millis(idle_timeout_milis as u64))
    } else {
        pool
    };

    let pool = if max_lifetime_milis > 0 {
        pool.max_lifetime(Duration::from_millis(max_lifetime_milis as u64))
    } else {
        pool
    };

    let pool = pool.connect_with(options.clone());

    // Create the pool here.
    let pool = runtime.block_on(pool);
    let pool: PgPool = match pool {
        Ok(pool) => pool,
        Err(err) => return sqlx4k_postgres_error_result_of(err).leak(),
    };
    let sqlx4k = Sqlx4kPostgreSql {
        connect_options: options,
        pool,
    };
    let sqlx4k = Box::new(sqlx4k);
    let sqlx4k = Box::leak(sqlx4k);

    Sqlx4kPostgresResult {
        rt: sqlx4k as *mut _ as *mut c_void,
        ..Default::default()
    }
    .leak()
}

#[no_mangle]
pub extern "C" fn sqlx4k_postgresql_pool_size(rt: *mut c_void) -> c_int {
    let sqlx4k = unsafe { &*(rt as *mut Sqlx4kPostgreSql) };
    sqlx4k.pool.size() as c_int
}

#[no_mangle]
pub extern "C" fn sqlx4k_postgresql_pool_idle_size(rt: *mut c_void) -> c_int {
    let sqlx4k = unsafe { &*(rt as *mut Sqlx4kPostgreSql) };
    sqlx4k.pool.num_idle() as c_int
}

#[no_mangle]
pub extern "C" fn sqlx4k_postgresql_close(
    rt: *mut c_void,
    callback: *mut c_void,
    fun: extern "C" fn(Sqlx4kPostgresPtr, *mut Sqlx4kPostgresResult),
) {
    let callback = Sqlx4kPostgresPtr { ptr: callback };
    let runtime = RUNTIME.get().unwrap();
    let sqlx4k = unsafe { &*(rt as *mut Sqlx4kPostgreSql) };
    runtime.spawn(async move {
        let result = sqlx4k.close().await;
        fun(callback, result)
    });
}

#[no_mangle]
pub extern "C" fn sqlx4k_postgresql_query(
    rt: *mut c_void,
    sql: *const c_char,
    callback: *mut c_void,
    fun: extern "C" fn(Sqlx4kPostgresPtr, *mut Sqlx4kPostgresResult),
) {
    let callback = Sqlx4kPostgresPtr { ptr: callback };
    let sql = c_chars_to_str_postgres(sql).to_owned();
    let runtime = RUNTIME.get().unwrap();
    let sqlx4k = unsafe { &*(rt as *mut Sqlx4kPostgreSql) };
    runtime.spawn(async move {
        let result = sqlx4k.query(sql).await;
        fun(callback, result)
    });
}

#[no_mangle]
pub extern "C" fn sqlx4k_postgresql_fetch_all(
    rt: *mut c_void,
    sql: *const c_char,
    callback: *mut c_void,
    fun: extern "C" fn(Sqlx4kPostgresPtr, *mut Sqlx4kPostgresResult),
) {
    let callback = Sqlx4kPostgresPtr { ptr: callback };
    let sql = c_chars_to_str_postgres(sql).to_owned();
    let runtime = RUNTIME.get().unwrap();
    let sqlx4k = unsafe { &*(rt as *mut Sqlx4kPostgreSql) };
    runtime.spawn(async move {
        let result = sqlx4k.fetch_all(sql).await;
        fun(callback, result)
    });
}

#[no_mangle]
pub extern "C" fn sqlx4k_postgresql_query_with_params(
    rt: *mut c_void,
    sql: *const c_char,
    params: *const Sqlx4kPostgresParam,
    params_len: c_int,
    callback: *mut c_void,
    fun: extern "C" fn(Sqlx4kPostgresPtr, *mut Sqlx4kPostgresResult),
) {
    let callback = Sqlx4kPostgresPtr { ptr: callback };
    let sql = c_chars_to_str_postgres(sql).to_owned();
    let owned = match read_params(params, params_len) {
        Ok(owned) => owned,
        Err(err) => return fun(callback, params_error(err, std::ptr::null_mut())),
    };
    let runtime = RUNTIME.get().unwrap();
    let sqlx4k = unsafe { &*(rt as *mut Sqlx4kPostgreSql) };
    runtime.spawn(async move {
        let result = sqlx4k.query_with_params(sql, owned).await;
        fun(callback, result)
    });
}

#[no_mangle]
pub extern "C" fn sqlx4k_postgresql_fetch_all_with_params(
    rt: *mut c_void,
    sql: *const c_char,
    params: *const Sqlx4kPostgresParam,
    params_len: c_int,
    callback: *mut c_void,
    fun: extern "C" fn(Sqlx4kPostgresPtr, *mut Sqlx4kPostgresResult),
) {
    let callback = Sqlx4kPostgresPtr { ptr: callback };
    let sql = c_chars_to_str_postgres(sql).to_owned();
    let owned = match read_params(params, params_len) {
        Ok(owned) => owned,
        Err(err) => return fun(callback, params_error(err, std::ptr::null_mut())),
    };
    let runtime = RUNTIME.get().unwrap();
    let sqlx4k = unsafe { &*(rt as *mut Sqlx4kPostgreSql) };
    runtime.spawn(async move {
        let result = sqlx4k.fetch_all_with_params(sql, owned).await;
        fun(callback, result)
    });
}
