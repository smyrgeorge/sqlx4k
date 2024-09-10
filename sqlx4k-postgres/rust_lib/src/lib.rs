use sqlx::postgres::{
    PgListener, PgNotification, PgPool, PgPoolOptions, PgRow, PgTypeInfo, PgValueRef,
};
use sqlx::{Column, Executor, Postgres, Row, Transaction, TypeInfo, ValueRef};
use sqlx4k::{c_chars_to_str, sqlx4k_error_result_of, Ptr, Sqlx4kColumn, Sqlx4kResult, Sqlx4kRow};
use std::{
    ffi::{c_char, c_int, c_void, CString},
    sync::OnceLock,
};
use tokio::runtime::Runtime;

static RUNTIME: OnceLock<Runtime> = OnceLock::new();
static mut SQLX4K: OnceLock<Sqlx4k> = OnceLock::new();

#[derive(Debug)]
struct Sqlx4k {
    pool: PgPool,
}

impl Sqlx4k {
    async fn query(&self, sql: &str) -> *mut Sqlx4kResult {
        let result = self.pool.execute(sql).await;
        let result = match result {
            Ok(res) => {
                res.rows_affected();
                Sqlx4kResult {
                    rows_affected: res.rows_affected(),
                    ..Default::default()
                }
            }
            Err(err) => sqlx4k_error_result_of(err),
        };
        result.leak()
    }

    async fn fetch_all(&self, sql: &str) -> *mut Sqlx4kResult {
        let result = self.pool.fetch_all(sql).await;
        sqlx4k_result_of(result).leak()
    }

    async fn tx_begin(&mut self) -> *mut Sqlx4kResult {
        let tx = self.pool.begin().await;
        let tx = match tx {
            Ok(tx) => tx,
            Err(err) => {
                return sqlx4k_error_result_of(err).leak();
            }
        };

        let tx = Box::new(tx);
        let tx = Box::leak(tx);
        let result = Sqlx4kResult {
            tx: tx as *mut _ as *mut c_void,
            ..Default::default()
        };
        result.leak()
    }

    async fn tx_commit(&mut self, tx: Ptr) -> *mut Sqlx4kResult {
        let tx = unsafe { &mut *(tx.ptr as *mut Transaction<'_, Postgres>) };
        let tx = unsafe { *Box::from_raw(tx) };
        let result = match tx.commit().await {
            Ok(_) => Sqlx4kResult::default(),
            Err(err) => sqlx4k_error_result_of(err),
        };
        result.leak()
    }

    async fn tx_rollback(&mut self, tx: Ptr) -> *mut Sqlx4kResult {
        let tx = unsafe { &mut *(tx.ptr as *mut Transaction<'_, Postgres>) };
        let tx = unsafe { *Box::from_raw(tx) };
        let result = match tx.rollback().await {
            Ok(_) => Sqlx4kResult::default(),
            Err(err) => sqlx4k_error_result_of(err),
        };
        result.leak()
    }

    async fn tx_query(&mut self, tx: Ptr, sql: &str) -> *mut Sqlx4kResult {
        let tx = unsafe { &mut *(tx.ptr as *mut Transaction<'_, Postgres>) };
        let mut tx = unsafe { *Box::from_raw(tx) };
        let result = tx.execute(sql).await;
        let tx = Box::new(tx);
        let tx = Box::leak(tx);
        let result = match result {
            Ok(res) => {
                res.rows_affected();
                Sqlx4kResult {
                    rows_affected: res.rows_affected(),
                    ..Default::default()
                }
            }
            Err(err) => sqlx4k_error_result_of(err),
        };
        let result = Sqlx4kResult {
            tx: tx as *mut _ as *mut c_void,
            ..result
        };
        result.leak()
    }

    async fn tx_fetch_all(&mut self, tx: Ptr, sql: &str) -> *mut Sqlx4kResult {
        let tx = unsafe { &mut *(tx.ptr as *mut Transaction<'_, Postgres>) };
        let mut tx = unsafe { *Box::from_raw(tx) };
        let result = tx.fetch_all(sql).await;
        let tx = Box::new(tx);
        let tx = Box::leak(tx);
        let result = sqlx4k_result_of(result);
        let result = Sqlx4kResult {
            tx: tx as *mut _ as *mut c_void,
            ..result
        };
        result.leak()
    }
}

#[no_mangle]
pub extern "C" fn sqlx4k_of(
    host: *const c_char,
    port: c_int,
    username: *const c_char,
    password: *const c_char,
    database: *const c_char,
    max_connections: c_int,
) -> *mut Sqlx4kResult {
    let host = c_chars_to_str(host);
    let username = c_chars_to_str(username);
    let password = c_chars_to_str(password);
    let database = c_chars_to_str(database);

    let url = format!(
        "postgres://{}:{}@{}:{}/{}",
        username, password, host, port, database
    );

    // Create the tokio runtime.
    let runtime = Runtime::new().unwrap();

    // Create the db pool options.
    let pool = PgPoolOptions::new()
        .max_connections(max_connections as u32)
        .connect(&url);

    // Create the pool here.
    let pool: PgPool = runtime.block_on(pool).unwrap();
    let sqlx4k = Sqlx4k { pool };

    RUNTIME.set(runtime).unwrap();
    unsafe { SQLX4K.set(sqlx4k).unwrap() };

    Sqlx4kResult::default().leak()
}

#[no_mangle]
pub extern "C" fn sqlx4k_pool_size() -> c_int {
    unsafe { SQLX4K.get().unwrap() }.pool.size() as c_int
}

#[no_mangle]
pub extern "C" fn sqlx4k_pool_idle_size() -> c_int {
    unsafe { SQLX4K.get().unwrap() }.pool.num_idle() as c_int
}

#[no_mangle]
pub extern "C" fn sqlx4k_query(
    sql: *const c_char,
    callback: *mut c_void,
    fun: unsafe extern "C" fn(Ptr, *mut Sqlx4kResult),
) {
    let callback = Ptr { ptr: callback };
    let sql = c_chars_to_str(sql).to_owned();
    let runtime = RUNTIME.get().unwrap();
    let sqlx4k = unsafe { SQLX4K.get().unwrap() };
    runtime.spawn(async move {
        let result = sqlx4k.query(&sql).await;
        unsafe { fun(callback, result) }
    });
}

#[no_mangle]
pub extern "C" fn sqlx4k_fetch_all(
    sql: *const c_char,
    callback: *mut c_void,
    fun: unsafe extern "C" fn(Ptr, *mut Sqlx4kResult),
) {
    let callback = Ptr { ptr: callback };
    let sql = c_chars_to_str(sql).to_owned();
    let runtime = RUNTIME.get().unwrap();
    let sqlx4k = unsafe { SQLX4K.get().unwrap() };
    runtime.spawn(async move {
        let result = sqlx4k.fetch_all(&sql).await;
        unsafe { fun(callback, result) }
    });
}

#[no_mangle]
pub extern "C" fn sqlx4k_tx_begin(
    callback: *mut c_void,
    fun: unsafe extern "C" fn(Ptr, *mut Sqlx4kResult),
) {
    let callback = Ptr { ptr: callback };
    let runtime = RUNTIME.get().unwrap();
    let sqlx4k = unsafe { SQLX4K.get_mut().unwrap() };
    runtime.spawn(async move {
        let result = sqlx4k.tx_begin().await;
        unsafe { fun(callback, result) }
    });
}

#[no_mangle]
pub extern "C" fn sqlx4k_tx_commit(
    tx: *mut c_void,
    callback: *mut c_void,
    fun: unsafe extern "C" fn(Ptr, *mut Sqlx4kResult),
) {
    let tx = Ptr { ptr: tx };
    let callback = Ptr { ptr: callback };
    let runtime = RUNTIME.get().unwrap();
    let sqlx4k = unsafe { SQLX4K.get_mut().unwrap() };
    runtime.spawn(async move {
        let result = sqlx4k.tx_commit(tx).await;
        unsafe { fun(callback, result) }
    });
}

#[no_mangle]
pub extern "C" fn sqlx4k_tx_rollback(
    tx: *mut c_void,
    callback: *mut c_void,
    fun: unsafe extern "C" fn(Ptr, *mut Sqlx4kResult),
) {
    let tx = Ptr { ptr: tx };
    let callback = Ptr { ptr: callback };
    let runtime = RUNTIME.get().unwrap();
    let sqlx4k = unsafe { SQLX4K.get_mut().unwrap() };
    runtime.spawn(async move {
        let result = sqlx4k.tx_rollback(tx).await;
        unsafe { fun(callback, result) }
    });
}

#[no_mangle]
pub extern "C" fn sqlx4k_tx_query(
    tx: *mut c_void,
    sql: *const c_char,
    callback: *mut c_void,
    fun: unsafe extern "C" fn(Ptr, *mut Sqlx4kResult),
) {
    let tx = Ptr { ptr: tx };
    let callback = Ptr { ptr: callback };
    let sql = c_chars_to_str(sql).to_owned();
    let runtime = RUNTIME.get().unwrap();
    let sqlx4k = unsafe { SQLX4K.get_mut().unwrap() };
    runtime.spawn(async move {
        let result = sqlx4k.tx_query(tx, &sql).await;
        unsafe { fun(callback, result) }
    });
}

#[no_mangle]
pub extern "C" fn sqlx4k_tx_fetch_all(
    tx: *mut c_void,
    sql: *const c_char,
    callback: *mut c_void,
    fun: unsafe extern "C" fn(Ptr, *mut Sqlx4kResult),
) {
    let tx = Ptr { ptr: tx };
    let callback = Ptr { ptr: callback };
    let sql = c_chars_to_str(sql).to_owned();
    let runtime = RUNTIME.get().unwrap();
    let sqlx4k = unsafe { SQLX4K.get_mut().unwrap() };
    runtime.spawn(async move {
        let result = sqlx4k.tx_fetch_all(tx, &sql).await;
        unsafe { fun(callback, result) }
    });
}

#[no_mangle]
pub extern "C" fn sqlx4k_listen(
    channels: *const c_char,
    notify_id: c_int,
    notify: unsafe extern "C" fn(c_int, *mut Sqlx4kResult),
    callback: *mut c_void,
    fun: unsafe extern "C" fn(Ptr, *mut Sqlx4kResult),
) {
    let callback = Ptr { ptr: callback };
    let channels = c_chars_to_str(channels).to_owned();
    let runtime = RUNTIME.get().unwrap();
    let sqlx4k = unsafe { SQLX4K.get_mut().unwrap() };
    runtime.spawn(async move {
        let mut listener = PgListener::connect_with(&sqlx4k.pool).await.unwrap();
        let channels: Vec<&str> = channels.split(',').collect();
        listener.listen_all(channels).await.unwrap();

        // Return OK as soon as the stream is ready.
        let result = Sqlx4kResult::default().leak();
        unsafe { fun(callback, result) }

        loop {
            while let Some(item) = listener.try_recv().await.unwrap() {
                let result = sqlx4k_result_of_pg_notification(item).leak();
                unsafe { notify(notify_id, result) }
            }
            // Automatically reconnect if connection closes.
        }
    });
}

fn sqlx4k_result_of_pg_notification(item: PgNotification) -> Sqlx4kResult {
    let column = Sqlx4kColumn {
        ordinal: 0,
        name: CString::new(item.channel()).unwrap().into_raw(),
        kind: CString::new("TEXT").unwrap().into_raw(),
        value: CString::new(item.payload()).unwrap().into_raw(),
    };

    let mut columns = vec![column];
    // Make sure we're not wasting space.
    columns.shrink_to_fit();
    assert!(columns.len() == columns.capacity());
    let columns: Box<[Sqlx4kColumn]> = columns.into_boxed_slice();
    let columns: &mut [Sqlx4kColumn] = Box::leak(columns);
    let columns: *mut Sqlx4kColumn = columns.as_mut_ptr();

    let row = Sqlx4kRow { size: 1, columns };
    let mut rows = vec![row];
    // Make sure we're not wasting space.
    rows.shrink_to_fit();
    assert!(rows.len() == rows.capacity());
    let rows: Box<[Sqlx4kRow]> = rows.into_boxed_slice();
    let rows: &mut [Sqlx4kRow] = Box::leak(rows);
    let rows: *mut Sqlx4kRow = rows.as_mut_ptr();

    Sqlx4kResult {
        size: 1,
        rows,
        ..Default::default()
    }
}

fn sqlx4k_result_of(result: Result<Vec<PgRow>, sqlx::Error>) -> Sqlx4kResult {
    match result {
        Ok(rows) => {
            let mut rows: Vec<Sqlx4kRow> = rows.iter().map(|r| sqlx4k_row_of(r)).collect();

            // Make sure we're not wasting space.
            rows.shrink_to_fit();
            assert!(rows.len() == rows.capacity());

            let size = rows.len();
            let rows: Box<[Sqlx4kRow]> = rows.into_boxed_slice();
            let rows: &mut [Sqlx4kRow] = Box::leak(rows);
            let rows: *mut Sqlx4kRow = rows.as_mut_ptr();

            Sqlx4kResult {
                size: size as c_int,
                rows,
                ..Default::default()
            }
        }
        Err(err) => sqlx4k_error_result_of(err),
    }
}

fn sqlx4k_row_of(row: &PgRow) -> Sqlx4kRow {
    let columns = row.columns();
    if columns.is_empty() {
        Sqlx4kRow::default()
    } else {
        let mut columns: Vec<Sqlx4kColumn> = row
            .columns()
            .iter()
            .map(|c| {
                let name: &str = c.name();
                let value_ref: PgValueRef = row.try_get_raw(c.ordinal()).unwrap();
                let info: std::borrow::Cow<PgTypeInfo> = value_ref.type_info();
                let kind: &str = info.name();
                let value: &str = row.get_unchecked(c.ordinal());
                Sqlx4kColumn {
                    ordinal: c.ordinal() as c_int,
                    name: CString::new(name).unwrap().into_raw(),
                    kind: CString::new(kind).unwrap().into_raw(),
                    value: CString::new(value).unwrap().into_raw(),
                }
            })
            .collect();

        // Make sure we're not wasting space.
        columns.shrink_to_fit();
        assert!(columns.len() == columns.capacity());

        let size = columns.len();
        let columns: Box<[Sqlx4kColumn]> = columns.into_boxed_slice();
        let columns: &mut [Sqlx4kColumn] = Box::leak(columns);
        let columns: *mut Sqlx4kColumn = columns.as_mut_ptr();

        Sqlx4kRow {
            size: size as c_int,
            columns,
        }
    }
}
