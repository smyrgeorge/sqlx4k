//! Exports acting on a transaction.

use std::ffi::{c_char, c_int, c_void};

use crate::client::Sqlx4kMySql;
use crate::params::{params_error, read_params, Sqlx4kMysqlParam};
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
    let owned = match read_params(params, params_len) {
        Ok(owned) => owned,
        Err(err) => return fun(callback, params_error(err, tx.ptr)),
    };
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
    let owned = match read_params(params, params_len) {
        Ok(owned) => owned,
        Err(err) => return fun(callback, params_error(err, tx.ptr)),
    };
    let runtime = RUNTIME.get().unwrap();
    let sqlx4k = unsafe { &*(rt as *mut Sqlx4kMySql) };
    runtime.spawn(async move {
        let result = sqlx4k.tx_fetch_all_with_params(tx, sql, owned).await;
        fun(callback, result)
    });
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::ffi::pool::{sqlx4k_mysql_fetch_all, sqlx4k_mysql_query};
    use crate::params::PARAM_INT;
    use crate::test_support::{c, call, param, unique, Pool, Res};
    use crate::types::ERROR_DATABASE;

    fn tx_query(pool: &Pool, tx: *mut c_void, sql: &str) -> Res {
        let sql = c(sql);
        call(|cb, fun| sqlx4k_mysql_tx_query(pool.rt, tx, sql.as_ptr(), cb, fun))
    }

    fn count(pool: &Pool, table: &str) -> Option<String> {
        let sql = c(&format!("select count(*) from {table}"));
        call(|cb, fun| sqlx4k_mysql_fetch_all(pool.rt, sql.as_ptr(), cb, fun)).ok().value()
    }

    fn create(pool: &Pool) -> String {
        let table = unique("t_ffi_tx");
        let sql = c(&format!("create table {table}(id bigint)"));
        call(|cb, fun| sqlx4k_mysql_query(pool.rt, sql.as_ptr(), cb, fun)).ok();
        table
    }

    #[test]
    #[ignore = "needs the MySQL of docker-compose.yml"]
    fn the_statements_of_a_transaction_are_committed_together() {
        let pool = Pool::open(2);
        let table = create(&pool);
        let tx = call(|cb, fun| sqlx4k_mysql_tx_begin(pool.rt, cb, fun)).ok().get().tx;
        // The transaction moves into each call and comes back in its result.
        let tx = tx_query(&pool, tx, &format!("insert into {table} values (1)")).ok().get().tx;
        let sql = c(&format!("insert into {table} values (?)"));
        let params = [param(PARAM_INT, |p| p.i64_val = 2)];
        let r = call(|cb, fun| sqlx4k_mysql_tx_query_with_params(pool.rt, tx, sql.as_ptr(), params.as_ptr(), 1, cb, fun)).ok();
        assert_eq!(r.get().rows_affected, 1);
        let tx = r.get().tx;
        let sql = c(&format!("select id from {table} where id >= ? order by id"));
        let params = [param(PARAM_INT, |p| p.i64_val = 1)];
        let r = call(|cb, fun| sqlx4k_mysql_tx_fetch_all_with_params(pool.rt, tx, sql.as_ptr(), params.as_ptr(), 1, cb, fun)).ok();
        assert_eq!(r.rows(), [[Some("1".into())], [Some("2".into())]]);
        let tx = r.get().tx;
        // Not committed yet: another connection sees none.
        assert_eq!(count(&pool, &table).as_deref(), Some("0"));
        let sql = c(&format!("select count(*) from {table}"));
        let r = call(|cb, fun| sqlx4k_mysql_tx_fetch_all(pool.rt, tx, sql.as_ptr(), cb, fun)).ok();
        assert_eq!(r.value().as_deref(), Some("2"));
        let tx = r.get().tx;
        call(|cb, fun| sqlx4k_mysql_tx_commit(pool.rt, tx, cb, fun)).ok();
        assert_eq!(count(&pool, &table).as_deref(), Some("2"));
    }

    #[test]
    #[ignore = "needs the MySQL of docker-compose.yml"]
    fn a_transaction_rolled_back_leaves_nothing() {
        let pool = Pool::open(2);
        let table = create(&pool);
        let tx = call(|cb, fun| sqlx4k_mysql_tx_begin(pool.rt, cb, fun)).ok().get().tx;
        let tx = tx_query(&pool, tx, &format!("insert into {table} values (1)")).ok().get().tx;
        call(|cb, fun| sqlx4k_mysql_tx_rollback(pool.rt, tx, cb, fun)).ok();
        assert_eq!(count(&pool, &table).as_deref(), Some("0"));
    }

    #[test]
    #[ignore = "needs the MySQL of docker-compose.yml"]
    fn a_parameter_of_an_unknown_kind_is_an_error_handing_the_transaction_back() {
        let pool = Pool::open(1);
        let tx = call(|cb, fun| sqlx4k_mysql_tx_begin(pool.rt, cb, fun)).ok().get().tx;
        let sql = c("select ?");
        let params = [param(9, |_| {})];
        let r = call(|cb, fun| sqlx4k_mysql_tx_fetch_all_with_params(pool.rt, tx, sql.as_ptr(), params.as_ptr(), 1, cb, fun));
        assert_eq!(r.get().error, ERROR_DATABASE);
        assert!(r.error_message().unwrap().contains("kind: 9"), "{:?}", r.error_message());
        assert_eq!(r.get().tx, tx);
        call(|cb, fun| sqlx4k_mysql_tx_rollback(pool.rt, tx, cb, fun)).ok();
    }

    #[test]
    #[ignore = "needs the MySQL of docker-compose.yml"]
    fn a_failing_statement_hands_the_transaction_back() {
        let pool = Pool::open(1);
        let tx = call(|cb, fun| sqlx4k_mysql_tx_begin(pool.rt, cb, fun)).ok().get().tx;
        let r = tx_query(&pool, tx, "select * from missing_table");
        assert_eq!(r.get().error, ERROR_DATABASE);
        let tx = r.get().tx;
        assert!(!tx.is_null());
        // MySQL keeps the transaction going after a failed statement.
        let tx = tx_query(&pool, tx, "select 1").ok().get().tx;
        call(|cb, fun| sqlx4k_mysql_tx_rollback(pool.rt, tx, cb, fun)).ok();
    }
}
