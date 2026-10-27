//! Exports acting on a connection acquired from the pool.

use std::ffi::{c_char, c_int, c_void};

use crate::client::Sqlx4kSqlite;
use crate::params::{params_error, read_params, Sqlx4kSqliteParam};
use crate::result::c_chars_to_str_sqlite;
use crate::types::{Sqlx4kSqlitePtr, Sqlx4kSqliteResult};
use crate::RUNTIME;

#[no_mangle]
pub extern "C" fn sqlx4k_sqlite_cn_acquire(
    rt: *mut c_void,
    callback: *mut c_void,
    fun: extern "C" fn(Sqlx4kSqlitePtr, *mut Sqlx4kSqliteResult),
) {
    let callback = Sqlx4kSqlitePtr { ptr: callback };
    let runtime = RUNTIME.get().unwrap();
    let sqlx4k = unsafe { &*(rt as *mut Sqlx4kSqlite) };
    runtime.spawn(async move {
        let result = sqlx4k.cn_acquire().await;
        fun(callback, result)
    });
}

#[no_mangle]
pub extern "C" fn sqlx4k_sqlite_cn_release(
    rt: *mut c_void,
    cn: *mut c_void,
    callback: *mut c_void,
    fun: extern "C" fn(Sqlx4kSqlitePtr, *mut Sqlx4kSqliteResult),
) {
    let cn = Sqlx4kSqlitePtr { ptr: cn };
    let callback = Sqlx4kSqlitePtr { ptr: callback };
    let runtime = RUNTIME.get().unwrap();
    let sqlx4k = unsafe { &*(rt as *mut Sqlx4kSqlite) };
    runtime.spawn(async move {
        let result = sqlx4k.cn_release(cn).await;
        fun(callback, result)
    });
}

#[no_mangle]
pub extern "C" fn sqlx4k_sqlite_cn_query(
    rt: *mut c_void,
    cn: *mut c_void,
    sql: *const c_char,
    callback: *mut c_void,
    fun: extern "C" fn(Sqlx4kSqlitePtr, *mut Sqlx4kSqliteResult),
) {
    let cn = Sqlx4kSqlitePtr { ptr: cn };
    let callback = Sqlx4kSqlitePtr { ptr: callback };
    let sql = c_chars_to_str_sqlite(sql).to_owned();
    let runtime = RUNTIME.get().unwrap();
    let sqlx4k = unsafe { &*(rt as *mut Sqlx4kSqlite) };
    runtime.spawn(async move {
        let result = sqlx4k.cn_query(cn, sql).await;
        fun(callback, result)
    });
}

#[no_mangle]
pub extern "C" fn sqlx4k_sqlite_cn_fetch_all(
    rt: *mut c_void,
    cn: *mut c_void,
    sql: *const c_char,
    callback: *mut c_void,
    fun: extern "C" fn(Sqlx4kSqlitePtr, *mut Sqlx4kSqliteResult),
) {
    let cn = Sqlx4kSqlitePtr { ptr: cn };
    let callback = Sqlx4kSqlitePtr { ptr: callback };
    let sql = c_chars_to_str_sqlite(sql).to_owned();
    let runtime = RUNTIME.get().unwrap();
    let sqlx4k = unsafe { &*(rt as *mut Sqlx4kSqlite) };
    runtime.spawn(async move {
        let result = sqlx4k.cn_fetch_all(cn, sql).await;
        fun(callback, result)
    });
}

#[no_mangle]
pub extern "C" fn sqlx4k_sqlite_cn_tx_begin(
    rt: *mut c_void,
    cn: *mut c_void,
    callback: *mut c_void,
    fun: extern "C" fn(Sqlx4kSqlitePtr, *mut Sqlx4kSqliteResult),
) {
    let cn = Sqlx4kSqlitePtr { ptr: cn };
    let callback = Sqlx4kSqlitePtr { ptr: callback };
    let runtime = RUNTIME.get().unwrap();
    let sqlx4k = unsafe { &*(rt as *mut Sqlx4kSqlite) };
    runtime.spawn(async move {
        let result = sqlx4k.cn_tx_begin(cn).await;
        fun(callback, result)
    });
}

#[no_mangle]
pub extern "C" fn sqlx4k_sqlite_cn_query_with_params(
    rt: *mut c_void,
    cn: *mut c_void,
    sql: *const c_char,
    params: *const Sqlx4kSqliteParam,
    params_len: c_int,
    callback: *mut c_void,
    fun: extern "C" fn(Sqlx4kSqlitePtr, *mut Sqlx4kSqliteResult),
) {
    let cn = Sqlx4kSqlitePtr { ptr: cn };
    let callback = Sqlx4kSqlitePtr { ptr: callback };
    let sql = c_chars_to_str_sqlite(sql).to_owned();
    let owned = match read_params(params, params_len) {
        Ok(owned) => owned,
        Err(err) => return fun(callback, params_error(err, std::ptr::null_mut())),
    };
    let runtime = RUNTIME.get().unwrap();
    let sqlx4k = unsafe { &*(rt as *mut Sqlx4kSqlite) };
    runtime.spawn(async move {
        let result = sqlx4k.cn_query_with_params(cn, sql, owned).await;
        fun(callback, result)
    });
}

#[no_mangle]
pub extern "C" fn sqlx4k_sqlite_cn_fetch_all_with_params(
    rt: *mut c_void,
    cn: *mut c_void,
    sql: *const c_char,
    params: *const Sqlx4kSqliteParam,
    params_len: c_int,
    callback: *mut c_void,
    fun: extern "C" fn(Sqlx4kSqlitePtr, *mut Sqlx4kSqliteResult),
) {
    let cn = Sqlx4kSqlitePtr { ptr: cn };
    let callback = Sqlx4kSqlitePtr { ptr: callback };
    let sql = c_chars_to_str_sqlite(sql).to_owned();
    let owned = match read_params(params, params_len) {
        Ok(owned) => owned,
        Err(err) => return fun(callback, params_error(err, std::ptr::null_mut())),
    };
    let runtime = RUNTIME.get().unwrap();
    let sqlx4k = unsafe { &*(rt as *mut Sqlx4kSqlite) };
    runtime.spawn(async move {
        let result = sqlx4k.cn_fetch_all_with_params(cn, sql, owned).await;
        fun(callback, result)
    });
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::ffi::transaction::sqlx4k_sqlite_tx_commit;
    use crate::params::PARAM_INT;
    use crate::test_support::{c, call, param, Pool, Res};
    use crate::types::ERROR_DATABASE;

    fn cn_query(pool: &Pool, cn: *mut c_void, sql: &str) -> Res {
        let sql = c(sql);
        call(|cb, fun| sqlx4k_sqlite_cn_query(pool.rt, cn, sql.as_ptr(), cb, fun))
    }

    fn cn_fetch_all(pool: &Pool, cn: *mut c_void, sql: &str) -> Res {
        let sql = c(sql);
        call(|cb, fun| sqlx4k_sqlite_cn_fetch_all(pool.rt, cn, sql.as_ptr(), cb, fun))
    }

    #[test]
    fn statements_run_on_the_connection_acquired() {
        let pool = Pool::open(2);
        let cn = call(|cb, fun| sqlx4k_sqlite_cn_acquire(pool.rt, cb, fun)).ok().get().cn;
        assert!(!cn.is_null());
        // A temporary table is the connection's own: the statements after see it.
        cn_query(&pool, cn, "create temp table t(id integer primary key)").ok();
        let r = cn_query(&pool, cn, "insert into t values (1), (2), (3)").ok();
        assert_eq!((r.get().rows_affected, r.get().last_insert_id), (3, 3));
        let params = [param(PARAM_INT, |p| p.i64_val = 2)];
        let sql = c("delete from t where id = ?");
        let r = call(|cb, fun| sqlx4k_sqlite_cn_query_with_params(pool.rt, cn, sql.as_ptr(), params.as_ptr(), 1, cb, fun)).ok();
        assert_eq!(r.get().rows_affected, 1);
        let sql = c("select id from t where id > ? order by id");
        let params = [param(PARAM_INT, |p| p.i64_val = 0)];
        let r = call(|cb, fun| sqlx4k_sqlite_cn_fetch_all_with_params(pool.rt, cn, sql.as_ptr(), params.as_ptr(), 1, cb, fun)).ok();
        assert_eq!(r.rows(), [[Some("1".into())], [Some("3".into())]]);
        assert_eq!(cn_fetch_all(&pool, cn, "select count(*) from t").ok().value().as_deref(), Some("2"));
        call(|cb, fun| sqlx4k_sqlite_cn_release(pool.rt, cn, cb, fun)).ok();
    }

    #[test]
    fn a_transaction_is_begun_on_the_connection() {
        let pool = Pool::open(2);
        let cn = call(|cb, fun| sqlx4k_sqlite_cn_acquire(pool.rt, cb, fun)).ok().get().cn;
        let tx = call(|cb, fun| sqlx4k_sqlite_cn_tx_begin(pool.rt, cn, cb, fun)).ok().get().tx;
        assert!(!tx.is_null());
        call(|cb, fun| sqlx4k_sqlite_tx_commit(pool.rt, tx, cb, fun)).ok();
        call(|cb, fun| sqlx4k_sqlite_cn_release(pool.rt, cn, cb, fun)).ok();
    }

    #[test]
    fn a_failing_statement_leaves_the_connection_usable() {
        let pool = Pool::open(1);
        let cn = call(|cb, fun| sqlx4k_sqlite_cn_acquire(pool.rt, cb, fun)).ok().get().cn;
        assert_eq!(cn_fetch_all(&pool, cn, "select * from missing_table").get().error, ERROR_DATABASE);
        assert_eq!(cn_fetch_all(&pool, cn, "select 1").ok().value().as_deref(), Some("1"));
        call(|cb, fun| sqlx4k_sqlite_cn_release(pool.rt, cn, cb, fun)).ok();
    }
}
