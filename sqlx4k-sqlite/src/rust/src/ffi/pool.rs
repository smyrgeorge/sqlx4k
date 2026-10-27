//! Exports acting on the pool: create and close it, and run queries on it.

use sqlx::migrate::MigrateDatabase;
use sqlx::sqlite::{SqliteConnectOptions, SqlitePool, SqlitePoolOptions};
use std::ffi::{c_char, c_int, c_void};
use std::time::Duration;
use tokio::runtime::Runtime;

use crate::client::{sqlite_url_mode, with_pool_timeouts, Sqlx4kSqlite};
use crate::errors::sqlx4k_sqlite_error_result_of;
use crate::params::{read_params, Sqlx4kSqliteParam};
use crate::result::c_chars_to_str_sqlite;
use crate::types::{Sqlx4kSqlitePtr, Sqlx4kSqliteResult};
use crate::RUNTIME;

#[no_mangle]
pub extern "C" fn sqlx4k_sqlite_of(
    url: *const c_char,
    username: *const c_char,
    password: *const c_char,
    min_connections: c_int,
    max_connections: c_int,
    acquire_timeout_milis: c_int,
    idle_timeout_milis: c_int,
    max_lifetime_milis: c_int,
) -> *mut Sqlx4kSqliteResult {
    let url = c_chars_to_str_sqlite(url);
    let _username = username;
    let _password = password;
    let options: SqliteConnectOptions = match url.parse() {
        Ok(o) => o,
        Err(err) => return sqlx4k_sqlite_error_result_of(err).leak(),
    };
    // `mode` decides whether a missing file may be created and whether the connection is writable,
    // exactly as on the JVM and Android drivers (and as SQLite defines `ro` / `rw` / `rwc`).
    let mode = sqlite_url_mode(url);
    let read_only = mode == Some("ro");
    let create_if_missing = matches!(mode, None | Some("rwc"));

    // Create the tokio runtime, once: get_or_init also when two pools are created concurrently (checking and then
    // setting raced: a losing set().unwrap() panicked, and the process aborted).
    let runtime = RUNTIME.get_or_init(|| Runtime::new().expect("failed to create the tokio runtime"));

    // Create the db pool options.
    //
    // Enable WAL via `after_connect` (which runs once the connection is fully established) rather
    // than the built-in `journal_mode` option, keeping this identical to the encrypted
    // `sqlx4k-sqlite-cipher` driver. There the built-in option can't be used — it runs before
    // `PRAGMA key` and silently fails on the still-encrypted file — so both drivers set WAL the same
    // way. WAL is sqlx's default anyway, and this is a no-op for in-memory databases ("memory").
    // It is skipped for `mode=ro`: changing the journal mode writes to the database header, which a
    // read-only connection cannot do (it would fail with "attempt to write a readonly database").
    let pool = SqlitePoolOptions::new()
        .max_connections(max_connections as u32)
        .after_connect(move |conn, _meta| {
            Box::pin(async move {
                if !read_only {
                    sqlx::query("PRAGMA journal_mode = WAL;")
                        .execute(&mut *conn)
                        .await?;
                }
                Ok(())
            })
        });

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

    let pool = with_pool_timeouts(pool, idle_timeout_milis, max_lifetime_milis);

    // Create the database file if it does not exist, but only for the default mode and `mode=rwc`:
    // `ro` and `rw` must fail on a missing file instead (the pool below then reports that error).
    // Creation can legitimately fail too (e.g. an unwritable directory), so surface it as an error.
    if create_if_missing {
        let created = runtime.block_on(async {
            if !sqlx::Sqlite::database_exists(&url).await? {
                sqlx::Sqlite::create_database(&url).await?;
            }
            Ok::<(), sqlx::Error>(())
        });
        if let Err(err) = created {
            return sqlx4k_sqlite_error_result_of(err).leak();
        }
    }

    let pool = pool.connect_with(options);

    // Create the pool here.
    let pool = runtime.block_on(pool);
    let pool: SqlitePool = match pool {
        Ok(pool) => pool,
        Err(err) => return sqlx4k_sqlite_error_result_of(err).leak(),
    };
    let sqlx4k = Sqlx4kSqlite { pool };
    let sqlx4k = Box::new(sqlx4k);
    let sqlx4k = Box::leak(sqlx4k);

    Sqlx4kSqliteResult {
        rt: sqlx4k as *mut _ as *mut c_void,
        ..Default::default()
    }
    .leak()
}

#[no_mangle]
pub extern "C" fn sqlx4k_sqlite_pool_size(rt: *mut c_void) -> c_int {
    let sqlx4k = unsafe { &*(rt as *mut Sqlx4kSqlite) };
    sqlx4k.pool.size() as c_int
}

#[no_mangle]
pub extern "C" fn sqlx4k_sqlite_pool_idle_size(rt: *mut c_void) -> c_int {
    let sqlx4k = unsafe { &*(rt as *mut Sqlx4kSqlite) };
    sqlx4k.pool.num_idle() as c_int
}

#[no_mangle]
pub extern "C" fn sqlx4k_sqlite_close(
    rt: *mut c_void,
    callback: *mut c_void,
    fun: extern "C" fn(Sqlx4kSqlitePtr, *mut Sqlx4kSqliteResult),
) {
    let callback = Sqlx4kSqlitePtr { ptr: callback };
    let runtime = RUNTIME.get().unwrap();
    let sqlx4k = unsafe { &*(rt as *mut Sqlx4kSqlite) };
    runtime.spawn(async move {
        let result = sqlx4k.close().await;
        fun(callback, result)
    });
}

#[no_mangle]
pub extern "C" fn sqlx4k_sqlite_query(
    rt: *mut c_void,
    sql: *const c_char,
    callback: *mut c_void,
    fun: extern "C" fn(Sqlx4kSqlitePtr, *mut Sqlx4kSqliteResult),
) {
    let callback = Sqlx4kSqlitePtr { ptr: callback };
    let sql = c_chars_to_str_sqlite(sql).to_owned();
    let runtime = RUNTIME.get().unwrap();
    let sqlx4k = unsafe { &*(rt as *mut Sqlx4kSqlite) };
    runtime.spawn(async move {
        let result = sqlx4k.query(sql).await;
        fun(callback, result)
    });
}

#[no_mangle]
pub extern "C" fn sqlx4k_sqlite_fetch_all(
    rt: *mut c_void,
    sql: *const c_char,
    callback: *mut c_void,
    fun: extern "C" fn(Sqlx4kSqlitePtr, *mut Sqlx4kSqliteResult),
) {
    let callback = Sqlx4kSqlitePtr { ptr: callback };
    let sql = c_chars_to_str_sqlite(sql).to_owned();
    let runtime = RUNTIME.get().unwrap();
    let sqlx4k = unsafe { &*(rt as *mut Sqlx4kSqlite) };
    runtime.spawn(async move {
        let result = sqlx4k.fetch_all(sql).await;
        fun(callback, result)
    });
}

#[no_mangle]
pub extern "C" fn sqlx4k_sqlite_query_with_params(
    rt: *mut c_void,
    sql: *const c_char,
    params: *const Sqlx4kSqliteParam,
    params_len: c_int,
    callback: *mut c_void,
    fun: extern "C" fn(Sqlx4kSqlitePtr, *mut Sqlx4kSqliteResult),
) {
    let callback = Sqlx4kSqlitePtr { ptr: callback };
    let sql = c_chars_to_str_sqlite(sql).to_owned();
    let owned = read_params(params, params_len);
    let runtime = RUNTIME.get().unwrap();
    let sqlx4k = unsafe { &*(rt as *mut Sqlx4kSqlite) };
    runtime.spawn(async move {
        let result = sqlx4k.query_with_params(sql, owned).await;
        fun(callback, result)
    });
}

#[no_mangle]
pub extern "C" fn sqlx4k_sqlite_fetch_all_with_params(
    rt: *mut c_void,
    sql: *const c_char,
    params: *const Sqlx4kSqliteParam,
    params_len: c_int,
    callback: *mut c_void,
    fun: extern "C" fn(Sqlx4kSqlitePtr, *mut Sqlx4kSqliteResult),
) {
    let callback = Sqlx4kSqlitePtr { ptr: callback };
    let sql = c_chars_to_str_sqlite(sql).to_owned();
    let owned = read_params(params, params_len);
    let runtime = RUNTIME.get().unwrap();
    let sqlx4k = unsafe { &*(rt as *mut Sqlx4kSqlite) };
    runtime.spawn(async move {
        let result = sqlx4k.fetch_all_with_params(sql, owned).await;
        fun(callback, result)
    });
}
