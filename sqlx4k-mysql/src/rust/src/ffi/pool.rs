//! Exports acting on the pool: create and close it, and run queries on it.

use sqlx::mysql::{MySqlConnectOptions, MySqlPool, MySqlPoolOptions};
use std::ffi::{c_char, c_int, c_void};
use std::time::Duration;
use tokio::runtime::Runtime;

use crate::client::Sqlx4kMySql;
use crate::errors::sqlx4k_mysql_error_result_of;
use crate::params::{params_error, read_params, Sqlx4kMysqlParam};
use crate::result::c_chars_to_str_mysql;
use crate::types::{Sqlx4kMysqlPtr, Sqlx4kMysqlResult};
use crate::RUNTIME;

#[no_mangle]
pub extern "C" fn sqlx4k_mysql_of(
    url: *const c_char,
    username: *const c_char,
    password: *const c_char,
    min_connections: c_int,
    max_connections: c_int,
    acquire_timeout_milis: c_int,
    idle_timeout_milis: c_int,
    max_lifetime_milis: c_int,
) -> *mut Sqlx4kMysqlResult {
    let url = c_chars_to_str_mysql(url);
    let username = c_chars_to_str_mysql(username);
    let password = c_chars_to_str_mysql(password);
    // A malformed URL is reported to the caller (panic = "abort" would take the process down).
    let options: MySqlConnectOptions = match url.parse() {
        Ok(options) => options,
        Err(err) => return sqlx4k_mysql_error_result_of(err).leak(),
    };
    let options = options.username(username).password(password);

    // Create the tokio runtime, once: get_or_init also when two pools are created concurrently (checking and then
    // setting raced, and the losing set().unwrap() panicked).
    let runtime =
        RUNTIME.get_or_init(|| Runtime::new().expect("failed to create the tokio runtime"));

    // Create the db pool options.
    let pool = MySqlPoolOptions::new().max_connections(max_connections as u32);

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

    let pool = pool.connect_with(options);

    // Create the pool here.
    let pool = runtime.block_on(pool);
    let pool: MySqlPool = match pool {
        Ok(pool) => pool,
        Err(err) => return sqlx4k_mysql_error_result_of(err).leak(),
    };
    let sqlx4k = Sqlx4kMySql { pool };
    let sqlx4k = Box::new(sqlx4k);
    let sqlx4k = Box::leak(sqlx4k);

    Sqlx4kMysqlResult {
        rt: sqlx4k as *mut _ as *mut c_void,
        ..Default::default()
    }
    .leak()
}

#[no_mangle]
pub extern "C" fn sqlx4k_mysql_pool_size(rt: *mut c_void) -> c_int {
    let sqlx4k = unsafe { &*(rt as *mut Sqlx4kMySql) };
    sqlx4k.pool.size() as c_int
}

#[no_mangle]
pub extern "C" fn sqlx4k_mysql_pool_idle_size(rt: *mut c_void) -> c_int {
    let sqlx4k = unsafe { &*(rt as *mut Sqlx4kMySql) };
    sqlx4k.pool.num_idle() as c_int
}

#[no_mangle]
pub extern "C" fn sqlx4k_mysql_close(
    rt: *mut c_void,
    callback: *mut c_void,
    fun: extern "C" fn(Sqlx4kMysqlPtr, *mut Sqlx4kMysqlResult),
) {
    let callback = Sqlx4kMysqlPtr { ptr: callback };
    let runtime = RUNTIME.get().unwrap();
    let sqlx4k = unsafe { &*(rt as *mut Sqlx4kMySql) };
    runtime.spawn(async move {
        let result = sqlx4k.close().await;
        fun(callback, result)
    });
}

#[no_mangle]
pub extern "C" fn sqlx4k_mysql_query(
    rt: *mut c_void,
    sql: *const c_char,
    callback: *mut c_void,
    fun: extern "C" fn(Sqlx4kMysqlPtr, *mut Sqlx4kMysqlResult),
) {
    let callback = Sqlx4kMysqlPtr { ptr: callback };
    let sql = c_chars_to_str_mysql(sql).to_owned();
    let runtime = RUNTIME.get().unwrap();
    let sqlx4k = unsafe { &*(rt as *mut Sqlx4kMySql) };
    runtime.spawn(async move {
        let result = sqlx4k.query(sql).await;
        fun(callback, result)
    });
}

#[no_mangle]
pub extern "C" fn sqlx4k_mysql_fetch_all(
    rt: *mut c_void,
    sql: *const c_char,
    callback: *mut c_void,
    fun: extern "C" fn(Sqlx4kMysqlPtr, *mut Sqlx4kMysqlResult),
) {
    let callback = Sqlx4kMysqlPtr { ptr: callback };
    let sql = c_chars_to_str_mysql(sql).to_owned();
    let runtime = RUNTIME.get().unwrap();
    let sqlx4k = unsafe { &*(rt as *mut Sqlx4kMySql) };
    runtime.spawn(async move {
        let result = sqlx4k.fetch_all(sql).await;
        fun(callback, result)
    });
}

#[no_mangle]
pub extern "C" fn sqlx4k_mysql_query_with_params(
    rt: *mut c_void,
    sql: *const c_char,
    params: *const Sqlx4kMysqlParam,
    params_len: c_int,
    callback: *mut c_void,
    fun: extern "C" fn(Sqlx4kMysqlPtr, *mut Sqlx4kMysqlResult),
) {
    let callback = Sqlx4kMysqlPtr { ptr: callback };
    let sql = c_chars_to_str_mysql(sql).to_owned();
    let owned = match read_params(params, params_len) {
        Ok(owned) => owned,
        Err(err) => return fun(callback, params_error(err, std::ptr::null_mut())),
    };
    let runtime = RUNTIME.get().unwrap();
    let sqlx4k = unsafe { &*(rt as *mut Sqlx4kMySql) };
    runtime.spawn(async move {
        let result = sqlx4k.query_with_params(sql, owned).await;
        fun(callback, result)
    });
}

#[no_mangle]
pub extern "C" fn sqlx4k_mysql_fetch_all_with_params(
    rt: *mut c_void,
    sql: *const c_char,
    params: *const Sqlx4kMysqlParam,
    params_len: c_int,
    callback: *mut c_void,
    fun: extern "C" fn(Sqlx4kMysqlPtr, *mut Sqlx4kMysqlResult),
) {
    let callback = Sqlx4kMysqlPtr { ptr: callback };
    let sql = c_chars_to_str_mysql(sql).to_owned();
    let owned = match read_params(params, params_len) {
        Ok(owned) => owned,
        Err(err) => return fun(callback, params_error(err, std::ptr::null_mut())),
    };
    let runtime = RUNTIME.get().unwrap();
    let sqlx4k = unsafe { &*(rt as *mut Sqlx4kMySql) };
    runtime.spawn(async move {
        let result = sqlx4k.fetch_all_with_params(sql, owned).await;
        fun(callback, result)
    });
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::params::{PARAM_INT, PARAM_TEXT};
    use crate::test_support::{c, call, param, text_param, unique, Pool, Res, PASSWORD, USERNAME};
    use crate::types::{ERROR_DATABASE, ERROR_POOL_CLOSED};

    fn query(pool: &Pool, sql: &str) -> Res {
        let sql = c(sql);
        call(|cb, fun| sqlx4k_mysql_query(pool.rt, sql.as_ptr(), cb, fun))
    }

    fn fetch_all(pool: &Pool, sql: &str) -> Res {
        let sql = c(sql);
        call(|cb, fun| sqlx4k_mysql_fetch_all(pool.rt, sql.as_ptr(), cb, fun))
    }

    #[test]
    fn a_malformed_url_is_an_error() {
        let r = Res(sqlx4k_mysql_of(
            c("not a url").as_ptr(),
            c("u").as_ptr(),
            c("p").as_ptr(),
            -1,
            1,
            -1,
            -1,
            -1,
        ));
        assert_eq!(r.get().error, ERROR_DATABASE);
        assert!(r.get().rt.is_null());
    }

    #[test]
    #[ignore = "needs the MySQL of docker-compose.yml"]
    fn a_wrong_password_is_an_error() {
        let url = c(&crate::test_support::url());
        let r = Res(sqlx4k_mysql_of(
            url.as_ptr(),
            c(USERNAME).as_ptr(),
            c("wrong").as_ptr(),
            1,
            1,
            5_000,
            -1,
            -1,
        ));
        assert_eq!(r.get().error, ERROR_DATABASE);
        assert_eq!(r.get().error_native_code, 1045);
    }

    #[test]
    #[ignore = "needs the MySQL of docker-compose.yml"]
    fn a_pool_keeps_its_minimum_of_connections() {
        let url = c(&crate::test_support::url());
        let r = Res(sqlx4k_mysql_of(
            url.as_ptr(),
            c(USERNAME).as_ptr(),
            c(PASSWORD).as_ptr(),
            2,
            3,
            5_000,
            -1,
            -1,
        ))
        .ok();
        let pool = Pool { rt: r.get().rt };
        for _ in 0..100 {
            if sqlx4k_mysql_pool_idle_size(pool.rt) == 2 {
                break;
            }
            std::thread::sleep(Duration::from_millis(10));
        }
        assert_eq!(sqlx4k_mysql_pool_size(pool.rt), 2);
        assert_eq!(sqlx4k_mysql_pool_idle_size(pool.rt), 2);
    }

    #[test]
    #[ignore = "needs the MySQL of docker-compose.yml"]
    fn statements_run_on_the_pool() {
        let pool = Pool::open(2);
        let table = unique("t_ffi_pool");
        query(&pool, &format!("create table {table}(id bigint, v text)")).ok();
        assert_eq!(
            query(
                &pool,
                &format!("insert into {table} values (1, 'a'), (2, 'b')")
            )
            .ok()
            .get()
            .rows_affected,
            2
        );
        let v = c("c");
        let params = [
            text_param(PARAM_TEXT, &v),
            param(PARAM_INT, |p| p.i64_val = 2),
        ];
        let sql = c(&format!("update {table} set v = ? where id = ?"));
        let r = call(|cb, fun| {
            sqlx4k_mysql_query_with_params(pool.rt, sql.as_ptr(), params.as_ptr(), 2, cb, fun)
        })
        .ok();
        assert_eq!(r.get().rows_affected, 1);
        let sql = c(&format!("select v from {table} where id >= ? order by id"));
        let params = [param(PARAM_INT, |p| p.i64_val = 1)];
        let r = call(|cb, fun| {
            sqlx4k_mysql_fetch_all_with_params(pool.rt, sql.as_ptr(), params.as_ptr(), 1, cb, fun)
        })
        .ok();
        assert_eq!(r.rows(), [[Some("a".into())], [Some("c".into())]]);
        assert_eq!(
            fetch_all(&pool, &format!("select count(*) from {table}"))
                .ok()
                .value()
                .as_deref(),
            Some("2")
        );
        query(&pool, &format!("drop table {table}")).ok();
    }

    #[test]
    #[ignore = "needs the MySQL of docker-compose.yml"]
    fn a_failing_statement_is_an_error() {
        let pool = Pool::open(1);
        let r = fetch_all(&pool, "select * from missing_table");
        assert_eq!(
            (r.get().error, r.get().error_native_code),
            (ERROR_DATABASE, 1146)
        );
        assert_eq!(r.sql_state().as_deref(), Some("42S02"));
    }

    #[test]
    #[ignore = "needs the MySQL of docker-compose.yml"]
    fn a_closed_pool_runs_nothing() {
        let pool = Pool::open(1);
        call(|cb, fun| sqlx4k_mysql_close(pool.rt, cb, fun)).ok();
        assert_eq!(query(&pool, "select 1").get().error, ERROR_POOL_CLOSED);
        assert_eq!(fetch_all(&pool, "select 1").get().error, ERROR_POOL_CLOSED);
    }
}
