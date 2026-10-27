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

#[cfg(test)]
mod tests {
    use super::*;
    use crate::ffi::pool::sqlx4k_postgresql_fetch_all;
    use crate::params::{PARAM_DATE, PARAM_INT8};
    use crate::test_support::{c, call, param, text_param, unique, Pool, Res};
    use crate::types::ERROR_DATABASE;

    fn tx_query(pool: &Pool, tx: *mut c_void, sql: &str) -> Res {
        let sql = c(sql);
        call(|cb, fun| sqlx4k_postgresql_tx_query(pool.rt, tx, sql.as_ptr(), cb, fun))
    }

    fn count(pool: &Pool, table: &str) -> Option<String> {
        let sql = c(&format!("select count(*) from {table}"));
        call(|cb, fun| sqlx4k_postgresql_fetch_all(pool.rt, sql.as_ptr(), cb, fun))
            .ok()
            .value()
    }

    fn create(pool: &Pool) -> String {
        let table = unique("t_ffi_tx");
        let tx = call(|cb, fun| sqlx4k_postgresql_tx_begin(pool.rt, cb, fun))
            .ok()
            .get()
            .tx;
        let tx = tx_query(pool, tx, &format!("create table {table}(id int8)"))
            .ok()
            .get()
            .tx;
        call(|cb, fun| sqlx4k_postgresql_tx_commit(pool.rt, tx, cb, fun)).ok();
        table
    }

    #[test]
    #[ignore = "needs the PostgreSQL of docker-compose.yml"]
    fn the_statements_of_a_transaction_are_committed_together() {
        let pool = Pool::open(2);
        let table = create(&pool);
        let tx = call(|cb, fun| sqlx4k_postgresql_tx_begin(pool.rt, cb, fun))
            .ok()
            .get()
            .tx;
        // The transaction moves into each call and comes back in its result.
        let tx = tx_query(&pool, tx, &format!("insert into {table} values (1)"))
            .ok()
            .get()
            .tx;
        let sql = c(&format!("insert into {table} values ($1)"));
        let params = [param(PARAM_INT8, |p| p.i64_val = 2)];
        let r = call(|cb, fun| {
            sqlx4k_postgresql_tx_query_with_params(
                pool.rt,
                tx,
                sql.as_ptr(),
                params.as_ptr(),
                1,
                cb,
                fun,
            )
        })
        .ok();
        assert_eq!(r.get().rows_affected, 1);
        let tx = r.get().tx;
        let sql = c(&format!(
            "select id from {table} where id >= $1 order by id"
        ));
        let params = [param(PARAM_INT8, |p| p.i64_val = 1)];
        let r = call(|cb, fun| {
            sqlx4k_postgresql_tx_fetch_all_with_params(
                pool.rt,
                tx,
                sql.as_ptr(),
                params.as_ptr(),
                1,
                cb,
                fun,
            )
        })
        .ok();
        assert_eq!(r.rows(), [[Some("1".into())], [Some("2".into())]]);
        let tx = r.get().tx;
        // Not committed yet: another connection sees none.
        assert_eq!(count(&pool, &table).as_deref(), Some("0"));
        let sql = c(&format!("select count(*) from {table}"));
        let r =
            call(|cb, fun| sqlx4k_postgresql_tx_fetch_all(pool.rt, tx, sql.as_ptr(), cb, fun)).ok();
        assert_eq!(r.value().as_deref(), Some("2"));
        let tx = r.get().tx;
        call(|cb, fun| sqlx4k_postgresql_tx_commit(pool.rt, tx, cb, fun)).ok();
        assert_eq!(count(&pool, &table).as_deref(), Some("2"));
    }

    #[test]
    #[ignore = "needs the PostgreSQL of docker-compose.yml"]
    fn a_transaction_rolled_back_leaves_nothing() {
        let pool = Pool::open(2);
        let table = create(&pool);
        let tx = call(|cb, fun| sqlx4k_postgresql_tx_begin(pool.rt, cb, fun))
            .ok()
            .get()
            .tx;
        let tx = tx_query(&pool, tx, &format!("insert into {table} values (1)"))
            .ok()
            .get()
            .tx;
        call(|cb, fun| sqlx4k_postgresql_tx_rollback(pool.rt, tx, cb, fun)).ok();
        assert_eq!(count(&pool, &table).as_deref(), Some("0"));
    }

    #[test]
    #[ignore = "needs the PostgreSQL of docker-compose.yml"]
    fn a_failing_or_unreadable_statement_hands_the_transaction_back() {
        let pool = Pool::open(1);
        let tx = call(|cb, fun| sqlx4k_postgresql_tx_begin(pool.rt, cb, fun))
            .ok()
            .get()
            .tx;
        let bad = c("not a date");
        let params = [text_param(PARAM_DATE, &bad)];
        let sql = c("select $1::date");
        let r = call(|cb, fun| {
            sqlx4k_postgresql_tx_fetch_all_with_params(
                pool.rt,
                tx,
                sql.as_ptr(),
                params.as_ptr(),
                1,
                cb,
                fun,
            )
        });
        assert_eq!(r.get().error, ERROR_DATABASE);
        let tx = r.get().tx;
        assert!(!tx.is_null());
        let r = tx_query(&pool, tx, "select * from missing_table");
        assert_eq!(r.get().error, ERROR_DATABASE);
        let tx = r.get().tx;
        assert!(!tx.is_null());
        // PostgreSQL aborted the transaction: it can only be rolled back.
        call(|cb, fun| sqlx4k_postgresql_tx_rollback(pool.rt, tx, cb, fun)).ok();
    }
}
