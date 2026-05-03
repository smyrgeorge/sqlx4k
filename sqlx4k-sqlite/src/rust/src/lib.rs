use sqlx::migrate::MigrateDatabase;
use sqlx::pool::PoolConnection;
use sqlx::sqlite::{
    SqliteConnectOptions, SqlitePool, SqlitePoolOptions, SqliteRow, SqliteTypeInfo, SqliteValueRef,
};
use sqlx::{Acquire, Column, Error, Executor, Row, Sqlite, Transaction, TypeInfo, ValueRef};
use std::{
    ffi::{c_char, c_int, c_ulonglong, c_void, CStr, CString},
    ptr::null_mut,
    slice,
    sync::OnceLock,
    time::Duration,
};
use tokio::runtime::Runtime;

// ============================================================================
// Shared constants and types (inlined from sqlx4k)
// ============================================================================

pub const OK: c_int = -1;
pub const ERROR_DATABASE: c_int = 0;
pub const ERROR_POOL_TIMED_OUT: c_int = 1;
pub const ERROR_POOL_CLOSED: c_int = 2;
pub const ERROR_WORKER_CRASHED: c_int = 3;

#[repr(C)]
pub struct Sqlx4kSqlitePtr {
    pub ptr: *mut c_void,
}
unsafe impl Send for Sqlx4kSqlitePtr {}
unsafe impl Sync for Sqlx4kSqlitePtr {}

#[repr(C)]
pub struct Sqlx4kSqliteResult {
    pub error: c_int,
    pub error_message: *mut c_char,
    pub rows_affected: c_ulonglong,
    pub cn: *mut c_void,
    pub tx: *mut c_void,
    pub rt: *mut c_void,
    pub schema: *mut Sqlx4kSqliteSchema,
    pub size: c_int,
    pub rows: *mut Sqlx4kSqliteRow,
}

impl Sqlx4kSqliteResult {
    pub fn leak(self) -> *mut Sqlx4kSqliteResult {
        let result = Box::new(self);
        let result = Box::leak(result);
        result
    }
}

impl Default for Sqlx4kSqliteResult {
    fn default() -> Self {
        Self {
            error: OK,
            error_message: null_mut(),
            rows_affected: 0,
            cn: null_mut(),
            tx: null_mut(),
            rt: null_mut(),
            schema: null_mut(),
            size: 0,
            rows: null_mut(),
        }
    }
}

#[repr(C)]
pub struct Sqlx4kSqliteSchema {
    pub size: c_int,
    pub columns: *mut Sqlx4kSqliteSchemaColumn,
}

impl Default for Sqlx4kSqliteSchema {
    fn default() -> Self {
        Self {
            size: 0,
            columns: null_mut(),
        }
    }
}

#[repr(C)]
pub struct Sqlx4kSqliteSchemaColumn {
    pub ordinal: c_int,
    pub name: *mut c_char,
    pub kind: *mut c_char,
}

#[repr(C)]
pub struct Sqlx4kSqliteRow {
    pub size: c_int,
    pub columns: *mut Sqlx4kSqliteColumn,
}

impl Default for Sqlx4kSqliteRow {
    fn default() -> Self {
        Self {
            size: 0,
            columns: null_mut(),
        }
    }
}

#[repr(C)]
pub struct Sqlx4kSqliteColumn {
    pub ordinal: c_int,
    pub value: *mut c_char,
}

// ----------------------------------------------------------------------------
// Prepared statement parameter binding
// ----------------------------------------------------------------------------

pub const PARAM_NULL: c_int = 0;
pub const PARAM_INT: c_int = 1;
pub const PARAM_REAL: c_int = 2;
pub const PARAM_TEXT: c_int = 3;
pub const PARAM_BLOB: c_int = 4;

/// FFI representation of a single bound parameter. Only the field
/// matching `kind` is read; the rest are ignored.
#[repr(C)]
pub struct Sqlx4kSqliteParam {
    pub kind: c_int,
    pub i64_val: i64,
    pub f64_val: f64,
    pub text: *const c_char,
    pub blob: *const u8,
    pub blob_len: c_int,
}

enum OwnedParam {
    Null,
    Int(i64),
    Real(f64),
    Text(String),
    Blob(Vec<u8>),
}

/// Reads `count` params from a C array and copies their data into owned
/// Rust values, so the resulting Vec can be moved into an async block
/// without referencing the caller's memory.
fn read_params(params: *const Sqlx4kSqliteParam, count: c_int) -> Vec<OwnedParam> {
    if params.is_null() || count <= 0 {
        return Vec::new();
    }
    let slice = unsafe { slice::from_raw_parts(params, count as usize) };
    slice
        .iter()
        .map(|p| match p.kind {
            PARAM_NULL => OwnedParam::Null,
            PARAM_INT => OwnedParam::Int(p.i64_val),
            PARAM_REAL => OwnedParam::Real(p.f64_val),
            PARAM_TEXT => {
                let s = unsafe { CStr::from_ptr(p.text) }
                    .to_str()
                    .expect("Invalid UTF-8 in TEXT parameter")
                    .to_owned();
                OwnedParam::Text(s)
            }
            PARAM_BLOB => {
                let len = p.blob_len.max(0) as usize;
                let bytes = if len == 0 || p.blob.is_null() {
                    Vec::new()
                } else {
                    unsafe { slice::from_raw_parts(p.blob, len) }.to_vec()
                };
                OwnedParam::Blob(bytes)
            }
            other => panic!("Unknown Sqlx4kSqliteParam kind: {}", other),
        })
        .collect()
}

fn bind_params<'q>(
    mut q: sqlx::query::Query<'q, Sqlite, sqlx::sqlite::SqliteArguments<'q>>,
    params: Vec<OwnedParam>,
) -> sqlx::query::Query<'q, Sqlite, sqlx::sqlite::SqliteArguments<'q>> {
    for p in params {
        q = match p {
            OwnedParam::Null => q.bind(None::<i64>),
            OwnedParam::Int(v) => q.bind(v),
            OwnedParam::Real(v) => q.bind(v),
            OwnedParam::Text(s) => q.bind(s),
            OwnedParam::Blob(b) => q.bind(b),
        };
    }
    q
}

#[no_mangle]
pub extern "C" fn auto_generated_for_struct_sqlite_Sqlx4kSqlitePtr(_: Sqlx4kSqlitePtr) {}
#[no_mangle]
pub extern "C" fn auto_generated_for_struct_sqlite_Sqlx4kSqliteResult(_: Sqlx4kSqliteResult) {}
#[no_mangle]
pub extern "C" fn auto_generated_for_struct_sqlite_Sqlx4kSqliteParam(_: Sqlx4kSqliteParam) {}

#[no_mangle]
pub extern "C" fn sqlx4k_sqlite_free_result(ptr: *mut Sqlx4kSqliteResult) {
    let ptr: Sqlx4kSqliteResult = unsafe { *Box::from_raw(ptr) };

    if ptr.error >= 0 {
        let error_message = unsafe { CString::from_raw(ptr.error_message) };
        std::mem::drop(error_message);
    }

    if ptr.schema == null_mut() {
        return;
    }

    let schema: Sqlx4kSqliteSchema = unsafe { *Box::from_raw(ptr.schema) };
    let columns: Vec<Sqlx4kSqliteSchemaColumn> =
        unsafe { Vec::from_raw_parts(schema.columns, schema.size as usize, schema.size as usize) };
    for col in columns {
        let name = unsafe { CString::from_raw(col.name) };
        std::mem::drop(name);
        let kind = unsafe { CString::from_raw(col.kind) };
        std::mem::drop(kind);
    }

    if ptr.rows == null_mut() {
        return;
    }

    let rows: Vec<Sqlx4kSqliteRow> =
        unsafe { Vec::from_raw_parts(ptr.rows, ptr.size as usize, ptr.size as usize) };
    for row in rows {
        let columns: Vec<Sqlx4kSqliteColumn> =
            unsafe { Vec::from_raw_parts(row.columns, row.size as usize, row.size as usize) };
        for col in columns {
            if col.value != null_mut() {
                let value = unsafe { CString::from_raw(col.value) };
                std::mem::drop(value);
            }
        }
    }
}

pub fn sqlx4k_sqlite_error_result_of(err: sqlx::Error) -> Sqlx4kSqliteResult {
    let (code, message) = match err {
        Error::Configuration(_) => panic!("Unexpected error occurred."),
        Error::Database(e) => match e.code() {
            Some(code) => (ERROR_DATABASE, format!("[{}] {}", code, e.to_string())),
            None => (ERROR_DATABASE, format!("{}", e.to_string())),
        },
        Error::Io(_) => panic!("Io :: Unexpected error occurred."),
        Error::Tls(_) => panic!("Tls :: Unexpected error occurred."),
        Error::Protocol(_) => panic!("Protocol :: Unexpected error occurred."),
        Error::RowNotFound => panic!("RowNotFound :: Unexpected error occurred."),
        Error::TypeNotFound { type_name: _ } => {
            panic!("TypeNotFound :: Unexpected error occurred.")
        }
        Error::ColumnIndexOutOfBounds { index: _, len: _ } => {
            panic!("ColumnIndexOutOfBounds :: Unexpected error occurred.")
        }
        Error::ColumnNotFound(_) => panic!("ColumnNotFound :: Unexpected error occurred."),
        Error::ColumnDecode {
            index: _,
            source: _,
        } => {
            panic!("ColumnDecode :: Unexpected error occurred.")
        }
        Error::Decode(_) => panic!("Decode :: Unexpected error occurred."),
        Error::AnyDriverError(_) => panic!("AnyDriverError :: Unexpected error occurred."),
        Error::PoolTimedOut => (ERROR_POOL_TIMED_OUT, "PoolTimedOut".to_string()),
        Error::PoolClosed => (
            ERROR_POOL_CLOSED,
            "The connection pool is already closed".to_string(),
        ),
        Error::WorkerCrashed => (ERROR_WORKER_CRASHED, "WorkerCrashed".to_string()),
        Error::Migrate(_) => panic!("Migrate :: Unexpected error occurred."),
        _ => panic!("Unexpected error occurred."),
    };

    Sqlx4kSqliteResult {
        error: code,
        error_message: CString::new(message).unwrap().into_raw(),
        ..Default::default()
    }
}

pub fn c_chars_to_str_sqlite<'a>(c_chars: *const c_char) -> &'a str {
    unsafe { CStr::from_ptr(c_chars).to_str().unwrap() }
}

// ============================================================================
// SQLite-specific implementation
// ============================================================================

static RUNTIME: OnceLock<Runtime> = OnceLock::new();

#[derive(Debug)]
struct Sqlx4kSqlite {
    pool: SqlitePool,
}

impl Sqlx4kSqlite {
    async fn query(&self, sql: &str) -> *mut Sqlx4kSqliteResult {
        let result = self.pool.execute(sql).await;
        let result = match result {
            Ok(res) => Sqlx4kSqliteResult {
                rows_affected: res.rows_affected(),
                ..Default::default()
            },
            Err(err) => sqlx4k_sqlite_error_result_of(err),
        };
        result.leak()
    }

    async fn fetch_all(&self, sql: &str) -> *mut Sqlx4kSqliteResult {
        let result = self.pool.fetch_all(sql).await;
        sqlx4k_sqlite_result_of(result).leak()
    }

    async fn cn_acquire(&self) -> *mut Sqlx4kSqliteResult {
        let cn = self.pool.acquire().await;
        let cn: PoolConnection<Sqlite> = match cn {
            Ok(cn) => cn,
            Err(err) => return sqlx4k_sqlite_error_result_of(err).leak(),
        };

        let cn = Box::new(cn);
        let cn = Box::leak(cn);
        let result = Sqlx4kSqliteResult {
            cn: cn as *mut _ as *mut c_void,
            ..Default::default()
        };
        result.leak()
    }

    async fn cn_release(&self, cn: Sqlx4kSqlitePtr) -> *mut Sqlx4kSqliteResult {
        if cn.ptr.is_null() {
            return Sqlx4kSqliteResult::default().leak();
        }

        let cn_ptr = cn.ptr as *mut PoolConnection<Sqlite>;
        unsafe {
            // Recreate the Box and drop it, returning connection to pool
            let _boxed: Box<PoolConnection<Sqlite>> = Box::from_raw(cn_ptr);
        }

        Sqlx4kSqliteResult::default().leak()
    }

    async fn cn_query(&self, cn: Sqlx4kSqlitePtr, sql: &str) -> *mut Sqlx4kSqliteResult {
        let cn = unsafe { &mut *(cn.ptr as *mut PoolConnection<Sqlite>) };
        let result = cn.execute(sql).await;
        let result = match result {
            Ok(res) => Sqlx4kSqliteResult {
                rows_affected: res.rows_affected(),
                ..Default::default()
            },
            Err(err) => sqlx4k_sqlite_error_result_of(err),
        };
        result.leak()
    }

    async fn cn_fetch_all(&self, cn: Sqlx4kSqlitePtr, sql: &str) -> *mut Sqlx4kSqliteResult {
        let cn = unsafe { &mut *(cn.ptr as *mut PoolConnection<Sqlite>) };
        let result = cn.fetch_all(sql).await;
        sqlx4k_sqlite_result_of(result).leak()
    }

    async fn cn_tx_begin(&self, cn: Sqlx4kSqlitePtr) -> *mut Sqlx4kSqliteResult {
        let cn = unsafe { &mut *(cn.ptr as *mut PoolConnection<Sqlite>) };
        let tx = cn.begin().await;
        let tx = match tx {
            Ok(tx) => tx,
            Err(err) => {
                return sqlx4k_sqlite_error_result_of(err).leak();
            }
        };

        let tx = Box::new(tx);
        let tx = Box::leak(tx);

        let result = Sqlx4kSqliteResult {
            tx: tx as *mut _ as *mut c_void,
            ..Default::default()
        };
        result.leak()
    }

    async fn tx_begin(&self) -> *mut Sqlx4kSqliteResult {
        let tx = self.pool.begin().await;
        let tx = match tx {
            Ok(tx) => tx,
            Err(err) => {
                return sqlx4k_sqlite_error_result_of(err).leak();
            }
        };

        let tx = Box::new(tx);
        let tx = Box::leak(tx);
        let result = Sqlx4kSqliteResult {
            tx: tx as *mut _ as *mut c_void,
            ..Default::default()
        };
        result.leak()
    }

    async fn tx_commit(&self, tx: Sqlx4kSqlitePtr) -> *mut Sqlx4kSqliteResult {
        let tx = unsafe { *Box::from_raw(tx.ptr as *mut Transaction<'_, Sqlite>) };
        let result = match tx.commit().await {
            Ok(_) => Sqlx4kSqliteResult::default(),
            Err(err) => sqlx4k_sqlite_error_result_of(err),
        };
        result.leak()
    }

    async fn tx_rollback(&self, tx: Sqlx4kSqlitePtr) -> *mut Sqlx4kSqliteResult {
        let tx = unsafe { *Box::from_raw(tx.ptr as *mut Transaction<'_, Sqlite>) };
        let result = match tx.rollback().await {
            Ok(_) => Sqlx4kSqliteResult::default(),
            Err(err) => sqlx4k_sqlite_error_result_of(err),
        };
        result.leak()
    }

    async fn tx_query(&self, tx: Sqlx4kSqlitePtr, sql: &str) -> *mut Sqlx4kSqliteResult {
        let mut tx = unsafe { *Box::from_raw(tx.ptr as *mut Transaction<'_, Sqlite>) };
        let result = tx.execute(sql).await;
        let tx = Box::new(tx);
        let tx = Box::into_raw(tx);
        let result = match result {
            Ok(res) => Sqlx4kSqliteResult {
                rows_affected: res.rows_affected(),
                ..Default::default()
            },
            Err(err) => sqlx4k_sqlite_error_result_of(err),
        };
        let result = Sqlx4kSqliteResult {
            tx: tx as *mut c_void,
            ..result
        };
        result.leak()
    }

    async fn tx_fetch_all(&self, tx: Sqlx4kSqlitePtr, sql: &str) -> *mut Sqlx4kSqliteResult {
        let mut tx = unsafe { *Box::from_raw(tx.ptr as *mut Transaction<'_, Sqlite>) };
        let result = tx.fetch_all(sql).await;
        let tx = Box::new(tx);
        let tx = Box::into_raw(tx);
        let result = sqlx4k_sqlite_result_of(result);
        let result = Sqlx4kSqliteResult {
            tx: tx as *mut c_void,
            ..result
        };
        result.leak()
    }

    async fn close(&self) -> *mut Sqlx4kSqliteResult {
        self.pool.close().await;
        Sqlx4kSqliteResult::default().leak()
    }

    async fn query_with_params(
        &self,
        sql: &str,
        params: Vec<OwnedParam>,
    ) -> *mut Sqlx4kSqliteResult {
        let q = bind_params(sqlx::query::<Sqlite>(sql), params);
        let result = q.execute(&self.pool).await;
        let result = match result {
            Ok(res) => Sqlx4kSqliteResult {
                rows_affected: res.rows_affected(),
                ..Default::default()
            },
            Err(err) => sqlx4k_sqlite_error_result_of(err),
        };
        result.leak()
    }

    async fn fetch_all_with_params(
        &self,
        sql: &str,
        params: Vec<OwnedParam>,
    ) -> *mut Sqlx4kSqliteResult {
        let q = bind_params(sqlx::query::<Sqlite>(sql), params);
        let result = q.fetch_all(&self.pool).await;
        sqlx4k_sqlite_result_of(result).leak()
    }

    async fn cn_query_with_params(
        &self,
        cn: Sqlx4kSqlitePtr,
        sql: &str,
        params: Vec<OwnedParam>,
    ) -> *mut Sqlx4kSqliteResult {
        let cn = unsafe { &mut *(cn.ptr as *mut PoolConnection<Sqlite>) };
        let q = bind_params(sqlx::query::<Sqlite>(sql), params);
        let result = q.execute(&mut **cn).await;
        let result = match result {
            Ok(res) => Sqlx4kSqliteResult {
                rows_affected: res.rows_affected(),
                ..Default::default()
            },
            Err(err) => sqlx4k_sqlite_error_result_of(err),
        };
        result.leak()
    }

    async fn cn_fetch_all_with_params(
        &self,
        cn: Sqlx4kSqlitePtr,
        sql: &str,
        params: Vec<OwnedParam>,
    ) -> *mut Sqlx4kSqliteResult {
        let cn = unsafe { &mut *(cn.ptr as *mut PoolConnection<Sqlite>) };
        let q = bind_params(sqlx::query::<Sqlite>(sql), params);
        let result = q.fetch_all(&mut **cn).await;
        sqlx4k_sqlite_result_of(result).leak()
    }

    async fn tx_query_with_params(
        &self,
        tx: Sqlx4kSqlitePtr,
        sql: &str,
        params: Vec<OwnedParam>,
    ) -> *mut Sqlx4kSqliteResult {
        let mut tx = unsafe { *Box::from_raw(tx.ptr as *mut Transaction<'_, Sqlite>) };
        let q = bind_params(sqlx::query::<Sqlite>(sql), params);
        let result = q.execute(&mut *tx).await;
        let tx = Box::new(tx);
        let tx = Box::into_raw(tx);
        let result = match result {
            Ok(res) => Sqlx4kSqliteResult {
                rows_affected: res.rows_affected(),
                ..Default::default()
            },
            Err(err) => sqlx4k_sqlite_error_result_of(err),
        };
        let result = Sqlx4kSqliteResult {
            tx: tx as *mut c_void,
            ..result
        };
        result.leak()
    }

    async fn tx_fetch_all_with_params(
        &self,
        tx: Sqlx4kSqlitePtr,
        sql: &str,
        params: Vec<OwnedParam>,
    ) -> *mut Sqlx4kSqliteResult {
        let mut tx = unsafe { *Box::from_raw(tx.ptr as *mut Transaction<'_, Sqlite>) };
        let q = bind_params(sqlx::query::<Sqlite>(sql), params);
        let result = q.fetch_all(&mut *tx).await;
        let tx = Box::new(tx);
        let tx = Box::into_raw(tx);
        let result = sqlx4k_sqlite_result_of(result);
        let result = Sqlx4kSqliteResult {
            tx: tx as *mut c_void,
            ..result
        };
        result.leak()
    }
}

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
    let options: SqliteConnectOptions = url.parse().unwrap();

    // Create the tokio runtime.
    let runtime = if RUNTIME.get().is_some() {
        RUNTIME.get().unwrap()
    } else {
        let rt = Runtime::new().unwrap();
        RUNTIME.set(rt).unwrap();
        RUNTIME.get().unwrap()
    };

    // Create the db pool options.
    let pool = SqlitePoolOptions::new().max_connections(max_connections as u32);

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

    // Creat the database file if not exists.
    runtime.block_on(async {
        if !sqlx::Sqlite::database_exists(&url).await.unwrap() {
            sqlx::Sqlite::create_database(&url).await.unwrap();
        }
    });

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
        let result = sqlx4k.query(&sql).await;
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
        let result = sqlx4k.fetch_all(&sql).await;
        fun(callback, result)
    });
}

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
        let result = sqlx4k.cn_query(cn, &sql).await;
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
        let result = sqlx4k.cn_fetch_all(cn, &sql).await;
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
pub extern "C" fn sqlx4k_sqlite_tx_begin(
    rt: *mut c_void,
    callback: *mut c_void,
    fun: extern "C" fn(Sqlx4kSqlitePtr, *mut Sqlx4kSqliteResult),
) {
    let callback = Sqlx4kSqlitePtr { ptr: callback };
    let runtime = RUNTIME.get().unwrap();
    let sqlx4k = unsafe { &*(rt as *mut Sqlx4kSqlite) };
    runtime.spawn(async move {
        let result = sqlx4k.tx_begin().await;
        fun(callback, result)
    });
}

#[no_mangle]
pub extern "C" fn sqlx4k_sqlite_tx_commit(
    rt: *mut c_void,
    tx: *mut c_void,
    callback: *mut c_void,
    fun: extern "C" fn(Sqlx4kSqlitePtr, *mut Sqlx4kSqliteResult),
) {
    let tx = Sqlx4kSqlitePtr { ptr: tx };
    let callback = Sqlx4kSqlitePtr { ptr: callback };
    let runtime = RUNTIME.get().unwrap();
    let sqlx4k = unsafe { &*(rt as *mut Sqlx4kSqlite) };
    runtime.spawn(async move {
        let result = sqlx4k.tx_commit(tx).await;
        fun(callback, result)
    });
}

#[no_mangle]
pub extern "C" fn sqlx4k_sqlite_tx_rollback(
    rt: *mut c_void,
    tx: *mut c_void,
    callback: *mut c_void,
    fun: extern "C" fn(Sqlx4kSqlitePtr, *mut Sqlx4kSqliteResult),
) {
    let tx = Sqlx4kSqlitePtr { ptr: tx };
    let callback = Sqlx4kSqlitePtr { ptr: callback };
    let runtime = RUNTIME.get().unwrap();
    let sqlx4k = unsafe { &*(rt as *mut Sqlx4kSqlite) };
    runtime.spawn(async move {
        let result = sqlx4k.tx_rollback(tx).await;
        fun(callback, result)
    });
}

#[no_mangle]
pub extern "C" fn sqlx4k_sqlite_tx_query(
    rt: *mut c_void,
    tx: *mut c_void,
    sql: *const c_char,
    callback: *mut c_void,
    fun: extern "C" fn(Sqlx4kSqlitePtr, *mut Sqlx4kSqliteResult),
) {
    let tx = Sqlx4kSqlitePtr { ptr: tx };
    let callback = Sqlx4kSqlitePtr { ptr: callback };
    let sql = c_chars_to_str_sqlite(sql).to_owned();
    let runtime = RUNTIME.get().unwrap();
    let sqlx4k = unsafe { &*(rt as *mut Sqlx4kSqlite) };
    runtime.spawn(async move {
        let result = sqlx4k.tx_query(tx, &sql).await;
        fun(callback, result)
    });
}

#[no_mangle]
pub extern "C" fn sqlx4k_sqlite_tx_fetch_all(
    rt: *mut c_void,
    tx: *mut c_void,
    sql: *const c_char,
    callback: *mut c_void,
    fun: extern "C" fn(Sqlx4kSqlitePtr, *mut Sqlx4kSqliteResult),
) {
    let tx = Sqlx4kSqlitePtr { ptr: tx };
    let callback = Sqlx4kSqlitePtr { ptr: callback };
    let sql = c_chars_to_str_sqlite(sql).to_owned();
    let runtime = RUNTIME.get().unwrap();
    let sqlx4k = unsafe { &*(rt as *mut Sqlx4kSqlite) };
    runtime.spawn(async move {
        let result = sqlx4k.tx_fetch_all(tx, &sql).await;
        fun(callback, result)
    });
}

fn sqlx4k_sqlite_result_of(result: Result<Vec<SqliteRow>, sqlx::Error>) -> Sqlx4kSqliteResult {
    match result {
        Ok(rows) => {
            let schema: Sqlx4kSqliteSchema = if rows.len() > 0 {
                sqlx4k_sqlite_schema_of(rows.get(0).unwrap())
            } else {
                Sqlx4kSqliteSchema::default()
            };

            let schema = Box::new(schema);
            let schema = Box::leak(schema);

            let rows: Vec<Sqlx4kSqliteRow> = rows.iter().map(|r| sqlx4k_sqlite_row_of(r)).collect();
            let size = rows.len();
            let rows: Box<[Sqlx4kSqliteRow]> = rows.into_boxed_slice();
            let rows: &mut [Sqlx4kSqliteRow] = Box::leak(rows);
            let rows: *mut Sqlx4kSqliteRow = rows.as_mut_ptr();

            Sqlx4kSqliteResult {
                schema,
                size: size as c_int,
                rows,
                ..Default::default()
            }
        }
        Err(err) => sqlx4k_sqlite_error_result_of(err),
    }
}

fn sqlx4k_sqlite_schema_of(row: &SqliteRow) -> Sqlx4kSqliteSchema {
    let columns = row.columns();
    if columns.is_empty() {
        Sqlx4kSqliteSchema::default()
    } else {
        let columns: Vec<Sqlx4kSqliteSchemaColumn> = row
            .columns()
            .iter()
            .map(|c| {
                let name: &str = c.name();
                let value_ref: SqliteValueRef = row.try_get_raw(c.ordinal()).unwrap();
                let info: std::borrow::Cow<SqliteTypeInfo> = value_ref.type_info();
                let kind: &str = info.name();
                Sqlx4kSqliteSchemaColumn {
                    ordinal: c.ordinal() as c_int,
                    name: CString::new(name).unwrap().into_raw(),
                    kind: CString::new(kind).unwrap().into_raw(),
                }
            })
            .collect();

        let size = columns.len();
        let columns: Box<[Sqlx4kSqliteSchemaColumn]> = columns.into_boxed_slice();
        let columns: &mut [Sqlx4kSqliteSchemaColumn] = Box::leak(columns);
        let columns: *mut Sqlx4kSqliteSchemaColumn = columns.as_mut_ptr();

        Sqlx4kSqliteSchema {
            size: size as c_int,
            columns,
        }
    }
}

fn sqlx4k_sqlite_row_of(row: &SqliteRow) -> Sqlx4kSqliteRow {
    let columns = row.columns();
    if columns.is_empty() {
        Sqlx4kSqliteRow::default()
    } else {
        let columns: Vec<Sqlx4kSqliteColumn> = row
            .columns()
            .iter()
            .map(|c| {
                let value_ref: SqliteValueRef = row.try_get_raw(c.ordinal()).unwrap();
                let info: std::borrow::Cow<SqliteTypeInfo> = value_ref.type_info();
                let type_info = info.name();
                let value = if type_info == "BLOB" {
                    let bytes: Option<&[u8]> = row.get_unchecked(c.ordinal());
                    if bytes.is_none() {
                        null_mut()
                    } else {
                        CString::new(hex::encode(bytes.unwrap()))
                            .unwrap()
                            .into_raw()
                    }
                } else {
                    let value: Option<&str> = row.get_unchecked(c.ordinal());
                    if value.is_none() {
                        null_mut()
                    } else {
                        CString::new(value.unwrap()).unwrap().into_raw()
                    }
                };

                Sqlx4kSqliteColumn {
                    ordinal: c.ordinal() as c_int,
                    value,
                }
            })
            .collect();

        let size = columns.len();
        let columns: Box<[Sqlx4kSqliteColumn]> = columns.into_boxed_slice();
        let columns: &mut [Sqlx4kSqliteColumn] = Box::leak(columns);
        let columns: *mut Sqlx4kSqliteColumn = columns.as_mut_ptr();

        Sqlx4kSqliteRow {
            size: size as c_int,
            columns,
        }
    }
}

// ----------------------------------------------------------------------------
// Parameterized FFI exports (used by the Statement-based execute/fetchAll path)
// ----------------------------------------------------------------------------

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
        let result = sqlx4k.query_with_params(&sql, owned).await;
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
        let result = sqlx4k.fetch_all_with_params(&sql, owned).await;
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
    let owned = read_params(params, params_len);
    let runtime = RUNTIME.get().unwrap();
    let sqlx4k = unsafe { &*(rt as *mut Sqlx4kSqlite) };
    runtime.spawn(async move {
        let result = sqlx4k.cn_query_with_params(cn, &sql, owned).await;
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
    let owned = read_params(params, params_len);
    let runtime = RUNTIME.get().unwrap();
    let sqlx4k = unsafe { &*(rt as *mut Sqlx4kSqlite) };
    runtime.spawn(async move {
        let result = sqlx4k.cn_fetch_all_with_params(cn, &sql, owned).await;
        fun(callback, result)
    });
}

#[no_mangle]
pub extern "C" fn sqlx4k_sqlite_tx_query_with_params(
    rt: *mut c_void,
    tx: *mut c_void,
    sql: *const c_char,
    params: *const Sqlx4kSqliteParam,
    params_len: c_int,
    callback: *mut c_void,
    fun: extern "C" fn(Sqlx4kSqlitePtr, *mut Sqlx4kSqliteResult),
) {
    let tx = Sqlx4kSqlitePtr { ptr: tx };
    let callback = Sqlx4kSqlitePtr { ptr: callback };
    let sql = c_chars_to_str_sqlite(sql).to_owned();
    let owned = read_params(params, params_len);
    let runtime = RUNTIME.get().unwrap();
    let sqlx4k = unsafe { &*(rt as *mut Sqlx4kSqlite) };
    runtime.spawn(async move {
        let result = sqlx4k.tx_query_with_params(tx, &sql, owned).await;
        fun(callback, result)
    });
}

#[no_mangle]
pub extern "C" fn sqlx4k_sqlite_tx_fetch_all_with_params(
    rt: *mut c_void,
    tx: *mut c_void,
    sql: *const c_char,
    params: *const Sqlx4kSqliteParam,
    params_len: c_int,
    callback: *mut c_void,
    fun: extern "C" fn(Sqlx4kSqlitePtr, *mut Sqlx4kSqliteResult),
) {
    let tx = Sqlx4kSqlitePtr { ptr: tx };
    let callback = Sqlx4kSqlitePtr { ptr: callback };
    let sql = c_chars_to_str_sqlite(sql).to_owned();
    let owned = read_params(params, params_len);
    let runtime = RUNTIME.get().unwrap();
    let sqlx4k = unsafe { &*(rt as *mut Sqlx4kSqlite) };
    runtime.spawn(async move {
        let result = sqlx4k.tx_fetch_all_with_params(tx, &sql, owned).await;
        fun(callback, result)
    });
}
