//! Exports opening, reading and closing a row stream.

use std::ffi::{c_char, c_int, c_void};

use crate::client::Sqlx4kPostgreSql;
use crate::params::{params_error, read_params, Sqlx4kPostgresParam};
use crate::result::c_chars_to_str_postgres;
use crate::stream::stream_fetch_size;
use crate::types::{Sqlx4kPostgresPtr, Sqlx4kPostgresResult};
use crate::RUNTIME;

#[no_mangle]
pub extern "C" fn sqlx4k_postgresql_stream_open(
    rt: *mut c_void,
    sql: *const c_char,
    params: *const Sqlx4kPostgresParam,
    params_len: c_int,
    fetch_size: c_int,
    callback: *mut c_void,
    fun: extern "C" fn(Sqlx4kPostgresPtr, *mut Sqlx4kPostgresResult),
) {
    let callback = Sqlx4kPostgresPtr { ptr: callback };
    let sql = c_chars_to_str_postgres(sql).to_owned();
    let owned = match read_params(params, params_len) {
        Ok(owned) => owned,
        Err(err) => return fun(callback, params_error(err, std::ptr::null_mut())),
    };
    let fetch_size = stream_fetch_size(fetch_size);
    let runtime = RUNTIME.get().unwrap();
    let sqlx4k = unsafe { &*(rt as *mut Sqlx4kPostgreSql) };
    runtime.spawn(async move {
        let result = sqlx4k.stream_open(sql, owned, fetch_size).await;
        fun(callback, result)
    });
}

#[no_mangle]
pub extern "C" fn sqlx4k_postgresql_cn_stream_open(
    rt: *mut c_void,
    cn: *mut c_void,
    sql: *const c_char,
    params: *const Sqlx4kPostgresParam,
    params_len: c_int,
    fetch_size: c_int,
    callback: *mut c_void,
    fun: extern "C" fn(Sqlx4kPostgresPtr, *mut Sqlx4kPostgresResult),
) {
    let callback = Sqlx4kPostgresPtr { ptr: callback };
    let cn = Sqlx4kPostgresPtr { ptr: cn };
    let sql = c_chars_to_str_postgres(sql).to_owned();
    let owned = match read_params(params, params_len) {
        Ok(owned) => owned,
        Err(err) => return fun(callback, params_error(err, std::ptr::null_mut())),
    };
    let fetch_size = stream_fetch_size(fetch_size);
    let runtime = RUNTIME.get().unwrap();
    let sqlx4k = unsafe { &*(rt as *mut Sqlx4kPostgreSql) };
    runtime.spawn(async move {
        let result = sqlx4k.cn_stream_open(cn, sql, owned, fetch_size).await;
        fun(callback, result)
    });
}

#[no_mangle]
pub extern "C" fn sqlx4k_postgresql_tx_stream_open(
    rt: *mut c_void,
    tx: *mut c_void,
    sql: *const c_char,
    params: *const Sqlx4kPostgresParam,
    params_len: c_int,
    fetch_size: c_int,
    callback: *mut c_void,
    fun: extern "C" fn(Sqlx4kPostgresPtr, *mut Sqlx4kPostgresResult),
) {
    let callback = Sqlx4kPostgresPtr { ptr: callback };
    let tx = Sqlx4kPostgresPtr { ptr: tx };
    let sql = c_chars_to_str_postgres(sql).to_owned();
    let owned = match read_params(params, params_len) {
        Ok(owned) => owned,
        Err(err) => return fun(callback, params_error(err, tx.ptr)),
    };
    let fetch_size = stream_fetch_size(fetch_size);
    let runtime = RUNTIME.get().unwrap();
    let sqlx4k = unsafe { &*(rt as *mut Sqlx4kPostgreSql) };
    runtime.spawn(async move {
        let result = sqlx4k.tx_stream_open(tx, sql, owned, fetch_size).await;
        fun(callback, result)
    });
}

#[no_mangle]
pub extern "C" fn sqlx4k_postgresql_stream_next(
    rt: *mut c_void,
    stream: *mut c_void,
    callback: *mut c_void,
    fun: extern "C" fn(Sqlx4kPostgresPtr, *mut Sqlx4kPostgresResult),
) {
    let callback = Sqlx4kPostgresPtr { ptr: callback };
    let stream = Sqlx4kPostgresPtr { ptr: stream };
    let runtime = RUNTIME.get().unwrap();
    let sqlx4k = unsafe { &*(rt as *mut Sqlx4kPostgreSql) };
    runtime.spawn(async move {
        let result = sqlx4k.stream_next(stream).await;
        fun(callback, result)
    });
}

#[no_mangle]
pub extern "C" fn sqlx4k_postgresql_stream_close(
    rt: *mut c_void,
    stream: *mut c_void,
    callback: *mut c_void,
    fun: extern "C" fn(Sqlx4kPostgresPtr, *mut Sqlx4kPostgresResult),
) {
    let callback = Sqlx4kPostgresPtr { ptr: callback };
    let stream = Sqlx4kPostgresPtr { ptr: stream };
    let runtime = RUNTIME.get().unwrap();
    let sqlx4k = unsafe { &*(rt as *mut Sqlx4kPostgreSql) };
    runtime.spawn(async move {
        let result = sqlx4k.stream_close(stream).await;
        fun(callback, result)
    });
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::ffi::connection::{sqlx4k_postgresql_cn_acquire, sqlx4k_postgresql_cn_fetch_all, sqlx4k_postgresql_cn_query, sqlx4k_postgresql_cn_release};
    use crate::ffi::transaction::{sqlx4k_postgresql_tx_begin, sqlx4k_postgresql_tx_commit, sqlx4k_postgresql_tx_query};
    use crate::params::{PARAM_DATE, PARAM_INT4};
    use crate::test_support::{c, call, param, text_param, Pool};
    use crate::types::ERROR_DATABASE;
    use std::ptr::null;

    /// Reads a stream to its end and closes it: the rows of each chunk.
    fn read(pool: &Pool, stream: *mut c_void) -> Vec<Vec<Vec<Option<String>>>> {
        let mut chunks = Vec::new();
        loop {
            let chunk = call(|cb, fun| sqlx4k_postgresql_stream_next(pool.rt, stream, cb, fun)).ok();
            if chunk.get().size == 0 {
                break;
            }
            chunks.push(chunk.rows());
        }
        call(|cb, fun| sqlx4k_postgresql_stream_close(pool.rt, stream, cb, fun)).ok();
        chunks
    }

    #[test]
    #[ignore = "needs the PostgreSQL of docker-compose.yml"]
    fn the_rows_of_a_pool_stream_come_in_chunks() {
        let pool = Pool::open(1);
        let sql = c("select g from generate_series(1, 5) g");
        let stream = call(|cb, fun| sqlx4k_postgresql_stream_open(pool.rt, sql.as_ptr(), null(), 0, 2, cb, fun)).ok().get().stream;
        let chunks = read(&pool, stream);
        assert_eq!(chunks.iter().map(Vec::len).collect::<Vec<_>>(), [2, 2, 1]);
        let values: Vec<String> = chunks.concat().into_iter().map(|r| r[0].clone().unwrap()).collect();
        assert_eq!(values, ["1", "2", "3", "4", "5"]);
    }

    #[test]
    #[ignore = "needs the PostgreSQL of docker-compose.yml"]
    fn a_prepared_statement_is_streamed() {
        let pool = Pool::open(1);
        let sql = c("select g from generate_series(1, $1) g");
        let params = [param(PARAM_INT4, |p| p.i64_val = 3)];
        let stream = call(|cb, fun| sqlx4k_postgresql_stream_open(pool.rt, sql.as_ptr(), params.as_ptr(), 1, 10, cb, fun))
            .ok()
            .get()
            .stream;
        assert_eq!(read(&pool, stream).concat().len(), 3);
    }

    #[test]
    #[ignore = "needs the PostgreSQL of docker-compose.yml"]
    fn an_unreadable_parameter_or_a_failing_query_is_an_error() {
        let pool = Pool::open(1);
        let bad = c("not a date");
        let params = [text_param(PARAM_DATE, &bad)];
        let sql = c("select $1::date");
        let r = call(|cb, fun| sqlx4k_postgresql_stream_open(pool.rt, sql.as_ptr(), params.as_ptr(), 1, 10, cb, fun));
        assert_eq!(r.get().error, ERROR_DATABASE);
        assert!(r.get().stream.is_null());
        // A query that fails fails its first chunk.
        let sql = c("select * from missing_table");
        let stream = call(|cb, fun| sqlx4k_postgresql_stream_open(pool.rt, sql.as_ptr(), null(), 0, 10, cb, fun)).ok().get().stream;
        let chunk = call(|cb, fun| sqlx4k_postgresql_stream_next(pool.rt, stream, cb, fun));
        assert_eq!(chunk.get().error, ERROR_DATABASE);
        call(|cb, fun| sqlx4k_postgresql_stream_close(pool.rt, stream, cb, fun)).ok();
    }

    #[test]
    #[ignore = "needs the PostgreSQL of docker-compose.yml"]
    fn a_stream_of_a_connection_leaves_it_usable_also_when_closed_early() {
        let pool = Pool::open(1);
        let cn = call(|cb, fun| sqlx4k_postgresql_cn_acquire(pool.rt, cb, fun)).ok().get().cn;
        let sql = c("create temp table t as select g from generate_series(1, 1000) g");
        call(|cb, fun| sqlx4k_postgresql_cn_query(pool.rt, cn, sql.as_ptr(), cb, fun)).ok();
        let sql = c("select g from t order by g");
        let stream = call(|cb, fun| sqlx4k_postgresql_cn_stream_open(pool.rt, cn, sql.as_ptr(), null(), 0, 10, cb, fun))
            .ok()
            .get()
            .stream;
        let first = call(|cb, fun| sqlx4k_postgresql_stream_next(pool.rt, stream, cb, fun)).ok();
        assert_eq!(first.rows()[0], [Some("1".into())]);
        call(|cb, fun| sqlx4k_postgresql_stream_close(pool.rt, stream, cb, fun)).ok();
        let sql = c("select count(*) from t");
        let r = call(|cb, fun| sqlx4k_postgresql_cn_fetch_all(pool.rt, cn, sql.as_ptr(), cb, fun)).ok();
        assert_eq!(r.value().as_deref(), Some("1000"));
        call(|cb, fun| sqlx4k_postgresql_cn_release(pool.rt, cn, cb, fun)).ok();
    }

    #[test]
    #[ignore = "needs the PostgreSQL of docker-compose.yml"]
    fn a_stream_of_a_transaction_sees_its_writes() {
        let pool = Pool::open(1);
        let tx = call(|cb, fun| sqlx4k_postgresql_tx_begin(pool.rt, cb, fun)).ok().get().tx;
        let sql = c("create temp table t as select g from generate_series(1, 3) g");
        let tx = call(|cb, fun| sqlx4k_postgresql_tx_query(pool.rt, tx, sql.as_ptr(), cb, fun)).ok().get().tx;
        let sql = c("select g from t");
        // The stream borrows the transaction in place: the same pointer is used after it.
        let stream = call(|cb, fun| sqlx4k_postgresql_tx_stream_open(pool.rt, tx, sql.as_ptr(), null(), 0, 10, cb, fun))
            .ok()
            .get()
            .stream;
        assert_eq!(read(&pool, stream).concat().len(), 3);
        call(|cb, fun| sqlx4k_postgresql_tx_commit(pool.rt, tx, cb, fun)).ok();
    }
}
