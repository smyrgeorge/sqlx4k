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

#[cfg(test)]
mod tests {
    use super::*;
    use crate::ffi::transaction::sqlx4k_postgresql_tx_commit;
    use crate::params::{PARAM_DATE, PARAM_INT8};
    use crate::test_support::{c, call, param, text_param, Pool, Res};
    use crate::types::ERROR_DATABASE;

    fn cn_query(pool: &Pool, cn: *mut c_void, sql: &str) -> Res {
        let sql = c(sql);
        call(|cb, fun| sqlx4k_postgresql_cn_query(pool.rt, cn, sql.as_ptr(), cb, fun))
    }

    fn cn_fetch_all(pool: &Pool, cn: *mut c_void, sql: &str) -> Res {
        let sql = c(sql);
        call(|cb, fun| sqlx4k_postgresql_cn_fetch_all(pool.rt, cn, sql.as_ptr(), cb, fun))
    }

    #[test]
    #[ignore = "needs the PostgreSQL of docker-compose.yml"]
    fn statements_run_on_the_connection_acquired() {
        let pool = Pool::open(2);
        let cn = call(|cb, fun| sqlx4k_postgresql_cn_acquire(pool.rt, cb, fun)).ok().get().cn;
        assert!(!cn.is_null());
        // A temporary table is the connection's own: the statements after see it.
        cn_query(&pool, cn, "create temp table t(id int8)").ok();
        assert_eq!(cn_query(&pool, cn, "insert into t values (1), (2), (3)").ok().get().rows_affected, 3);
        let params = [param(PARAM_INT8, |p| p.i64_val = 2)];
        let sql = c("delete from t where id = $1");
        let r = call(|cb, fun| sqlx4k_postgresql_cn_query_with_params(pool.rt, cn, sql.as_ptr(), params.as_ptr(), 1, cb, fun)).ok();
        assert_eq!(r.get().rows_affected, 1);
        let sql = c("select id from t where id > $1 order by id");
        let params = [param(PARAM_INT8, |p| p.i64_val = 0)];
        let r = call(|cb, fun| sqlx4k_postgresql_cn_fetch_all_with_params(pool.rt, cn, sql.as_ptr(), params.as_ptr(), 1, cb, fun)).ok();
        assert_eq!(r.rows(), [[Some("1".into())], [Some("3".into())]]);
        assert_eq!(cn_fetch_all(&pool, cn, "select count(*) from t").ok().value().as_deref(), Some("2"));
        call(|cb, fun| sqlx4k_postgresql_cn_release(pool.rt, cn, cb, fun)).ok();
    }

    #[test]
    #[ignore = "needs the PostgreSQL of docker-compose.yml"]
    fn a_transaction_is_begun_on_the_connection() {
        let pool = Pool::open(2);
        let cn = call(|cb, fun| sqlx4k_postgresql_cn_acquire(pool.rt, cb, fun)).ok().get().cn;
        cn_query(&pool, cn, "create temp table t(id int8)").ok();
        let tx = call(|cb, fun| sqlx4k_postgresql_cn_tx_begin(pool.rt, cn, cb, fun)).ok().get().tx;
        assert!(!tx.is_null());
        call(|cb, fun| sqlx4k_postgresql_tx_commit(pool.rt, tx, cb, fun)).ok();
        call(|cb, fun| sqlx4k_postgresql_cn_release(pool.rt, cn, cb, fun)).ok();
    }

    #[test]
    #[ignore = "needs the PostgreSQL of docker-compose.yml"]
    fn a_failing_or_unreadable_statement_leaves_the_connection_usable() {
        let pool = Pool::open(1);
        let cn = call(|cb, fun| sqlx4k_postgresql_cn_acquire(pool.rt, cb, fun)).ok().get().cn;
        assert_eq!(cn_fetch_all(&pool, cn, "select * from missing_table").get().error, ERROR_DATABASE);
        let bad = c("not a date");
        let params = [text_param(PARAM_DATE, &bad)];
        let sql = c("select $1::date");
        let r = call(|cb, fun| sqlx4k_postgresql_cn_query_with_params(pool.rt, cn, sql.as_ptr(), params.as_ptr(), 1, cb, fun));
        assert_eq!(r.get().error, ERROR_DATABASE);
        assert_eq!(cn_fetch_all(&pool, cn, "select 1").ok().value().as_deref(), Some("1"));
        call(|cb, fun| sqlx4k_postgresql_cn_release(pool.rt, cn, cb, fun)).ok();
    }
}
