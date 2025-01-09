use sqlx::postgres::{
    PgConnectOptions, PgListener, PgNotification, PgPool, PgPoolOptions, PgRow, PgTypeInfo,
    PgValueRef,
};
use sqlx::{Column, Executor, Postgres, Row, Transaction, TypeInfo, ValueRef};
use sqlx4k::{
    c_chars_to_str, sqlx4k_error_result_of, Ptr, Sqlx4kColumn, Sqlx4kResult, Sqlx4kRow,
    Sqlx4kSchema, Sqlx4kSchemaColumn,
};
use std::{
    ffi::{c_char, c_int, c_void, CString},
    ptr::null_mut,
    sync::OnceLock,
    time::Duration,
};
use tokio::runtime::Runtime;

static RUNTIME: OnceLock<Runtime> = OnceLock::new();
static SQLX4K: OnceLock<Sqlx4k> = OnceLock::new();

#[derive(Debug)]
struct Sqlx4k {
    pool: PgPool,
}

impl Sqlx4k {
    async fn query(&self, sql: &str) -> *mut Sqlx4kResult {
        let result = self.pool.execute(sql).await;
        let result = match result {
            Ok(res) => Sqlx4kResult {
                rows_affected: res.rows_affected(),
                ..Default::default()
            },
            Err(err) => sqlx4k_error_result_of(err),
        };
        result.leak()
    }

    async fn fetch_all(&self, sql: &str) -> *mut Sqlx4kResult {
        let result = self.pool.fetch_all(sql).await;
        sqlx4k_result_of(result).leak()
    }

    async fn tx_begin(&self) -> *mut Sqlx4kResult {
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

    async fn tx_commit(&self, tx: Ptr) -> *mut Sqlx4kResult {
        let tx = unsafe { &mut *(tx.ptr as *mut Transaction<'_, Postgres>) };
        let tx = unsafe { *Box::from_raw(tx) };
        let result = match tx.commit().await {
            Ok(_) => Sqlx4kResult::default(),
            Err(err) => sqlx4k_error_result_of(err),
        };
        result.leak()
    }

    async fn tx_rollback(&self, tx: Ptr) -> *mut Sqlx4kResult {
        let tx = unsafe { &mut *(tx.ptr as *mut Transaction<'_, Postgres>) };
        let tx = unsafe { *Box::from_raw(tx) };
        let result = match tx.rollback().await {
            Ok(_) => Sqlx4kResult::default(),
            Err(err) => sqlx4k_error_result_of(err),
        };
        result.leak()
    }

    async fn tx_query(&self, tx: Ptr, sql: &str) -> *mut Sqlx4kResult {
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

    async fn tx_fetch_all(&self, tx: Ptr, sql: &str) -> *mut Sqlx4kResult {
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

    async fn close(&self) -> *mut Sqlx4kResult {
        self.pool.close().await;
        Sqlx4kResult::default().leak()
    }
}

#[no_mangle]
pub extern "C" fn sqlx4k_of(
    url: *const c_char,
    username: *const c_char,
    password: *const c_char,
    min_connections: c_int,
    max_connections: c_int,
    acquire_timeout_milis: c_int,
    idle_timeout_milis: c_int,
    max_lifetime_milis: c_int,
) -> *mut Sqlx4kResult {
    let url = c_chars_to_str(url);
    let username = c_chars_to_str(username);
    let password = c_chars_to_str(password);
    let options: PgConnectOptions = url.parse().unwrap();
    let options = options.username(username).password(password);

    // Create the tokio runtime.
    let runtime = Runtime::new().unwrap();

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

    let pool = pool.connect_with(options);

    // Create the pool here.
    let pool: PgPool = runtime.block_on(pool).unwrap();
    let sqlx4k = Sqlx4k { pool };

    RUNTIME.set(runtime).unwrap();
    SQLX4K.set(sqlx4k).unwrap();

    Sqlx4kResult::default().leak()
}

#[no_mangle]
pub extern "C" fn sqlx4k_pool_size() -> c_int {
    SQLX4K.get().unwrap().pool.size() as c_int
}

#[no_mangle]
pub extern "C" fn sqlx4k_pool_idle_size() -> c_int {
    SQLX4K.get().unwrap().pool.num_idle() as c_int
}

#[no_mangle]
pub extern "C" fn sqlx4k_close(callback: *mut c_void, fun: extern "C" fn(Ptr, *mut Sqlx4kResult)) {
    let callback = Ptr { ptr: callback };
    let runtime = RUNTIME.get().unwrap();
    let sqlx4k = SQLX4K.get().unwrap();
    runtime.spawn(async move {
        let result = sqlx4k.close().await;
        fun(callback, result)
    });
}

#[no_mangle]
pub extern "C" fn sqlx4k_query(
    sql: *const c_char,
    callback: *mut c_void,
    fun: extern "C" fn(Ptr, *mut Sqlx4kResult),
) {
    let callback = Ptr { ptr: callback };
    let sql = c_chars_to_str(sql).to_owned();
    let runtime = RUNTIME.get().unwrap();
    let sqlx4k = SQLX4K.get().unwrap();
    runtime.spawn(async move {
        let result = sqlx4k.query(&sql).await;
        fun(callback, result)
    });
}

#[no_mangle]
pub extern "C" fn sqlx4k_fetch_all(
    sql: *const c_char,
    callback: *mut c_void,
    fun: extern "C" fn(Ptr, *mut Sqlx4kResult),
) {
    let callback = Ptr { ptr: callback };
    let sql = c_chars_to_str(sql).to_owned();
    let runtime = RUNTIME.get().unwrap();
    let sqlx4k = SQLX4K.get().unwrap();
    runtime.spawn(async move {
        let result = sqlx4k.fetch_all(&sql).await;
        fun(callback, result)
    });
}

#[no_mangle]
pub extern "C" fn sqlx4k_tx_begin(
    callback: *mut c_void,
    fun: extern "C" fn(Ptr, *mut Sqlx4kResult),
) {
    let callback = Ptr { ptr: callback };
    let runtime = RUNTIME.get().unwrap();
    let sqlx4k = SQLX4K.get().unwrap();
    runtime.spawn(async move {
        let result = sqlx4k.tx_begin().await;
        fun(callback, result)
    });
}

#[no_mangle]
pub extern "C" fn sqlx4k_tx_commit(
    tx: *mut c_void,
    callback: *mut c_void,
    fun: extern "C" fn(Ptr, *mut Sqlx4kResult),
) {
    let tx = Ptr { ptr: tx };
    let callback = Ptr { ptr: callback };
    let runtime = RUNTIME.get().unwrap();
    let sqlx4k = SQLX4K.get().unwrap();
    runtime.spawn(async move {
        let result = sqlx4k.tx_commit(tx).await;
        fun(callback, result)
    });
}

#[no_mangle]
pub extern "C" fn sqlx4k_tx_rollback(
    tx: *mut c_void,
    callback: *mut c_void,
    fun: extern "C" fn(Ptr, *mut Sqlx4kResult),
) {
    let tx = Ptr { ptr: tx };
    let callback = Ptr { ptr: callback };
    let runtime = RUNTIME.get().unwrap();
    let sqlx4k = SQLX4K.get().unwrap();
    runtime.spawn(async move {
        let result = sqlx4k.tx_rollback(tx).await;
        fun(callback, result)
    });
}

#[no_mangle]
pub extern "C" fn sqlx4k_tx_query(
    tx: *mut c_void,
    sql: *const c_char,
    callback: *mut c_void,
    fun: extern "C" fn(Ptr, *mut Sqlx4kResult),
) {
    let tx = Ptr { ptr: tx };
    let callback = Ptr { ptr: callback };
    let sql = c_chars_to_str(sql).to_owned();
    let runtime = RUNTIME.get().unwrap();
    let sqlx4k = SQLX4K.get().unwrap();
    runtime.spawn(async move {
        let result = sqlx4k.tx_query(tx, &sql).await;
        fun(callback, result)
    });
}

#[no_mangle]
pub extern "C" fn sqlx4k_tx_fetch_all(
    tx: *mut c_void,
    sql: *const c_char,
    callback: *mut c_void,
    fun: extern "C" fn(Ptr, *mut Sqlx4kResult),
) {
    let tx = Ptr { ptr: tx };
    let callback = Ptr { ptr: callback };
    let sql = c_chars_to_str(sql).to_owned();
    let runtime = RUNTIME.get().unwrap();
    let sqlx4k = SQLX4K.get().unwrap();
    runtime.spawn(async move {
        let result = sqlx4k.tx_fetch_all(tx, &sql).await;
        fun(callback, result)
    });
}

#[no_mangle]
pub extern "C" fn sqlx4k_listen(
    channels: *const c_char,
    notify_id: c_int,
    notify: extern "C" fn(c_int, *mut Sqlx4kResult),
    callback: *mut c_void,
    fun: extern "C" fn(Ptr, *mut Sqlx4kResult),
) {
    let callback = Ptr { ptr: callback };
    let channels = c_chars_to_str(channels).to_owned();
    let runtime = RUNTIME.get().unwrap();
    let sqlx4k = SQLX4K.get().unwrap();
    runtime.spawn(async move {
        let mut listener = PgListener::connect_with(&sqlx4k.pool).await.unwrap();
        let channels: Vec<&str> = channels.split(',').collect();
        listener.listen_all(channels).await.unwrap();

        // Return OK as soon as the stream is ready.
        let result = Sqlx4kResult::default().leak();
        fun(callback, result);

        loop {
            while let Some(item) = listener.try_recv().await.unwrap() {
                let result = sqlx4k_result_of_pg_notification(item).leak();
                notify(notify_id, result)
            }
            // Automatically reconnect if connection closes.
        }
    });
}

fn sqlx4k_result_of_pg_notification(item: PgNotification) -> Sqlx4kResult {
    let column = Sqlx4kSchemaColumn {
        ordinal: 0,
        name: CString::new(item.channel()).unwrap().into_raw(),
        kind: CString::new("TEXT").unwrap().into_raw(),
    };
    let columns = vec![column];
    let columns: Box<[Sqlx4kSchemaColumn]> = columns.into_boxed_slice();
    let columns: &mut [Sqlx4kSchemaColumn] = Box::leak(columns);
    let columns: *mut Sqlx4kSchemaColumn = columns.as_mut_ptr();
    let schema = Sqlx4kSchema { size: 1, columns };
    let schema = Box::new(schema);
    let schema = Box::leak(schema);

    let column = Sqlx4kColumn {
        ordinal: 0,
        value: CString::new(item.payload()).unwrap().into_raw(),
    };

    let columns = vec![column];
    let columns: Box<[Sqlx4kColumn]> = columns.into_boxed_slice();
    let columns: &mut [Sqlx4kColumn] = Box::leak(columns);
    let columns: *mut Sqlx4kColumn = columns.as_mut_ptr();

    let row = Sqlx4kRow { size: 1, columns };
    let rows = vec![row];
    let rows: Box<[Sqlx4kRow]> = rows.into_boxed_slice();
    let rows: &mut [Sqlx4kRow] = Box::leak(rows);
    let rows: *mut Sqlx4kRow = rows.as_mut_ptr();

    Sqlx4kResult {
        schema,
        size: 1,
        rows,
        ..Default::default()
    }
}

fn sqlx4k_result_of(result: Result<Vec<PgRow>, sqlx::Error>) -> Sqlx4kResult {
    match result {
        Ok(rows) => {
            let schema: Sqlx4kSchema = if rows.len() > 0 {
                sqlx4k_schema_of(rows.get(0).unwrap())
            } else {
                Sqlx4kSchema::default()
            };

            let schema = Box::new(schema);
            let schema = Box::leak(schema);

            let rows: Vec<Sqlx4kRow> = rows.iter().map(|r| sqlx4k_row_of(r)).collect();
            let size = rows.len();
            let rows: Box<[Sqlx4kRow]> = rows.into_boxed_slice();
            let rows: &mut [Sqlx4kRow] = Box::leak(rows);
            let rows: *mut Sqlx4kRow = rows.as_mut_ptr();

            Sqlx4kResult {
                schema,
                size: size as c_int,
                rows,
                ..Default::default()
            }
        }
        Err(err) => sqlx4k_error_result_of(err),
    }
}

fn sqlx4k_schema_of(row: &PgRow) -> Sqlx4kSchema {
    let columns = row.columns();
    if columns.is_empty() {
        Sqlx4kSchema::default()
    } else {
        let columns: Vec<Sqlx4kSchemaColumn> = row
            .columns()
            .iter()
            .map(|c| {
                let name: &str = c.name();
                let value_ref: PgValueRef = row.try_get_raw(c.ordinal()).unwrap();
                let info: std::borrow::Cow<PgTypeInfo> = value_ref.type_info();
                let kind: &str = info.name();
                Sqlx4kSchemaColumn {
                    ordinal: c.ordinal() as c_int,
                    name: CString::new(name).unwrap().into_raw(),
                    kind: CString::new(kind).unwrap().into_raw(),
                }
            })
            .collect();

        let size = columns.len();
        let columns: Box<[Sqlx4kSchemaColumn]> = columns.into_boxed_slice();
        let columns: &mut [Sqlx4kSchemaColumn] = Box::leak(columns);
        let columns: *mut Sqlx4kSchemaColumn = columns.as_mut_ptr();

        Sqlx4kSchema {
            size: size as c_int,
            columns,
        }
    }
}

fn sqlx4k_row_of(row: &PgRow) -> Sqlx4kRow {
    let columns = row.columns();
    if columns.is_empty() {
        Sqlx4kRow::default()
    } else {
        let columns: Vec<Sqlx4kColumn> = row
            .columns()
            .iter()
            .map(|c| {
                let value: Option<&str> = row.get_unchecked(c.ordinal());
                Sqlx4kColumn {
                    ordinal: c.ordinal() as c_int,
                    value: if value.is_none() {
                        null_mut()
                    } else {
                        CString::new(value.unwrap()).unwrap().into_raw()
                    },
                }
            })
            .collect();

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
