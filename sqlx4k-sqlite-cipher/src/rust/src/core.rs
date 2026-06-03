//! Shared SQLite (SQLCipher) engine.
//!
//! Holds the connection pool, the async query methods, the C-ABI result types, parameter
//! binding, error mapping, and the byte-buffer (de)serialization used by the JNI bridge.
//! Both [`crate::ffi`] (cinterop) and [`crate::jni`] (JVM/Android) call into here, so this is
//! the single source of truth for query execution and result shaping.

use sqlx::pool::PoolConnection;
use sqlx::sqlite::{
    SqliteConnectOptions, SqlitePool, SqlitePoolOptions, SqliteRow, SqliteTypeInfo, SqliteValueRef,
};
use sqlx::{
    Acquire, AssertSqlSafe, Column, Error, Executor, Row, Sqlite, Transaction, TypeInfo, ValueRef,
};
use std::{
    ffi::{c_char, c_int, c_ulonglong, c_void, CStr, CString},
    ptr::null_mut,
    slice,
    sync::OnceLock,
    time::Duration,
};
use tokio::runtime::Runtime;

// ============================================================================
// Shared constants and types
// ============================================================================

pub const OK: c_int = -1;
pub const ERROR_DATABASE: c_int = 0;
pub const ERROR_POOL_TIMED_OUT: c_int = 1;
pub const ERROR_POOL_CLOSED: c_int = 2;
pub const ERROR_WORKER_CRASHED: c_int = 3;

#[repr(C)]
pub struct Sqlx4kSqliteCipherPtr {
    pub ptr: *mut c_void,
}
unsafe impl Send for Sqlx4kSqliteCipherPtr {}
unsafe impl Sync for Sqlx4kSqliteCipherPtr {}

#[repr(C)]
pub struct Sqlx4kSqliteCipherResult {
    pub error: c_int,
    pub error_message: *mut c_char,
    pub rows_affected: c_ulonglong,
    pub cn: *mut c_void,
    pub tx: *mut c_void,
    pub rt: *mut c_void,
    pub schema: *mut Sqlx4kSqliteCipherSchema,
    pub size: c_int,
    pub rows: *mut Sqlx4kSqliteCipherRow,
}

impl Sqlx4kSqliteCipherResult {
    pub fn leak(self) -> *mut Sqlx4kSqliteCipherResult {
        let result = Box::new(self);
        Box::leak(result)
    }
}

impl Default for Sqlx4kSqliteCipherResult {
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
pub struct Sqlx4kSqliteCipherSchema {
    pub size: c_int,
    pub columns: *mut Sqlx4kSqliteCipherSchemaColumn,
}

impl Default for Sqlx4kSqliteCipherSchema {
    fn default() -> Self {
        Self {
            size: 0,
            columns: null_mut(),
        }
    }
}

#[repr(C)]
pub struct Sqlx4kSqliteCipherSchemaColumn {
    pub ordinal: c_int,
    pub name: *mut c_char,
    pub kind: *mut c_char,
}

#[repr(C)]
pub struct Sqlx4kSqliteCipherRow {
    pub size: c_int,
    pub columns: *mut Sqlx4kSqliteCipherColumn,
}

impl Default for Sqlx4kSqliteCipherRow {
    fn default() -> Self {
        Self {
            size: 0,
            columns: null_mut(),
        }
    }
}

#[repr(C)]
pub struct Sqlx4kSqliteCipherColumn {
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
pub struct Sqlx4kSqliteCipherParam {
    pub kind: c_int,
    pub i64_val: i64,
    pub f64_val: f64,
    pub text: *const c_char,
    pub blob: *const u8,
    pub blob_len: c_int,
}

pub enum OwnedParam {
    Null,
    Int(i64),
    Real(f64),
    Text(String),
    Blob(Vec<u8>),
}

/// Reads `count` params from a C array and copies their data into owned
/// Rust values, so the resulting Vec can be moved into an async block
/// without referencing the caller's memory.
pub fn read_params(params: *const Sqlx4kSqliteCipherParam, count: c_int) -> Vec<OwnedParam> {
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
            other => panic!("Unknown Sqlx4kSqliteCipherParam kind: {}", other),
        })
        .collect()
}

fn bind_params<'q>(
    mut q: sqlx::query::Query<'q, Sqlite, sqlx::sqlite::SqliteArguments>,
    params: Vec<OwnedParam>,
) -> sqlx::query::Query<'q, Sqlite, sqlx::sqlite::SqliteArguments> {
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

// ----------------------------------------------------------------------------
// Result lifecycle
// ----------------------------------------------------------------------------

/// Frees a leaked [`Sqlx4kSqliteCipherResult`] and every nested allocation it owns.
pub fn free_result(ptr: *mut Sqlx4kSqliteCipherResult) {
    let ptr: Sqlx4kSqliteCipherResult = unsafe { *Box::from_raw(ptr) };

    if ptr.error >= 0 {
        let error_message = unsafe { CString::from_raw(ptr.error_message) };
        std::mem::drop(error_message);
    }

    if ptr.schema == null_mut() {
        return;
    }

    let schema: Sqlx4kSqliteCipherSchema = unsafe { *Box::from_raw(ptr.schema) };
    let columns: Vec<Sqlx4kSqliteCipherSchemaColumn> =
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

    let rows: Vec<Sqlx4kSqliteCipherRow> =
        unsafe { Vec::from_raw_parts(ptr.rows, ptr.size as usize, ptr.size as usize) };
    for row in rows {
        let columns: Vec<Sqlx4kSqliteCipherColumn> =
            unsafe { Vec::from_raw_parts(row.columns, row.size as usize, row.size as usize) };
        for col in columns {
            if col.value != null_mut() {
                let value = unsafe { CString::from_raw(col.value) };
                std::mem::drop(value);
            }
        }
    }
}

pub fn error_result_of(err: sqlx::Error) -> Sqlx4kSqliteCipherResult {
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

    Sqlx4kSqliteCipherResult {
        error: code,
        error_message: CString::new(message).unwrap().into_raw(),
        ..Default::default()
    }
}

pub fn c_chars_to_str<'a>(c_chars: *const c_char) -> &'a str {
    unsafe { CStr::from_ptr(c_chars).to_str().unwrap() }
}

// ============================================================================
// SQLite (SQLCipher) implementation
// ============================================================================

pub static RUNTIME: OnceLock<Runtime> = OnceLock::new();

#[derive(Debug)]
pub struct Sqlx4kSqliteCipher {
    pool: SqlitePool,
}

impl Sqlx4kSqliteCipher {
    pub async fn query(&self, sql: String) -> *mut Sqlx4kSqliteCipherResult {
        let result = self.pool.execute(AssertSqlSafe(sql)).await;
        let result = match result {
            Ok(res) => Sqlx4kSqliteCipherResult {
                rows_affected: res.rows_affected(),
                ..Default::default()
            },
            Err(err) => error_result_of(err),
        };
        result.leak()
    }

    pub async fn fetch_all(&self, sql: String) -> *mut Sqlx4kSqliteCipherResult {
        let result = self.pool.fetch_all(AssertSqlSafe(sql)).await;
        result_of(result).leak()
    }

    pub async fn cn_acquire(&self) -> *mut Sqlx4kSqliteCipherResult {
        let cn = self.pool.acquire().await;
        let cn: PoolConnection<Sqlite> = match cn {
            Ok(cn) => cn,
            Err(err) => return error_result_of(err).leak(),
        };

        let cn = Box::new(cn);
        let cn = Box::leak(cn);
        let result = Sqlx4kSqliteCipherResult {
            cn: cn as *mut _ as *mut c_void,
            ..Default::default()
        };
        result.leak()
    }

    pub async fn cn_release(&self, cn: Sqlx4kSqliteCipherPtr) -> *mut Sqlx4kSqliteCipherResult {
        if cn.ptr.is_null() {
            return Sqlx4kSqliteCipherResult::default().leak();
        }

        let cn_ptr = cn.ptr as *mut PoolConnection<Sqlite>;
        unsafe {
            // Recreate the Box and drop it, returning connection to pool
            let _boxed: Box<PoolConnection<Sqlite>> = Box::from_raw(cn_ptr);
        }

        Sqlx4kSqliteCipherResult::default().leak()
    }

    pub async fn cn_query(
        &self,
        cn: Sqlx4kSqliteCipherPtr,
        sql: String,
    ) -> *mut Sqlx4kSqliteCipherResult {
        let cn = unsafe { &mut *(cn.ptr as *mut PoolConnection<Sqlite>) };
        let result = cn.execute(AssertSqlSafe(sql)).await;
        let result = match result {
            Ok(res) => Sqlx4kSqliteCipherResult {
                rows_affected: res.rows_affected(),
                ..Default::default()
            },
            Err(err) => error_result_of(err),
        };
        result.leak()
    }

    pub async fn cn_fetch_all(
        &self,
        cn: Sqlx4kSqliteCipherPtr,
        sql: String,
    ) -> *mut Sqlx4kSqliteCipherResult {
        let cn = unsafe { &mut *(cn.ptr as *mut PoolConnection<Sqlite>) };
        let result = cn.fetch_all(AssertSqlSafe(sql)).await;
        result_of(result).leak()
    }

    pub async fn cn_tx_begin(&self, cn: Sqlx4kSqliteCipherPtr) -> *mut Sqlx4kSqliteCipherResult {
        let cn = unsafe { &mut *(cn.ptr as *mut PoolConnection<Sqlite>) };
        let tx = cn.begin().await;
        let tx = match tx {
            Ok(tx) => tx,
            Err(err) => {
                return error_result_of(err).leak();
            }
        };

        let tx = Box::new(tx);
        let tx = Box::leak(tx);

        let result = Sqlx4kSqliteCipherResult {
            tx: tx as *mut _ as *mut c_void,
            ..Default::default()
        };
        result.leak()
    }

    pub async fn tx_begin(&self) -> *mut Sqlx4kSqliteCipherResult {
        let tx = self.pool.begin().await;
        let tx = match tx {
            Ok(tx) => tx,
            Err(err) => {
                return error_result_of(err).leak();
            }
        };

        let tx = Box::new(tx);
        let tx = Box::leak(tx);
        let result = Sqlx4kSqliteCipherResult {
            tx: tx as *mut _ as *mut c_void,
            ..Default::default()
        };
        result.leak()
    }

    pub async fn tx_commit(&self, tx: Sqlx4kSqliteCipherPtr) -> *mut Sqlx4kSqliteCipherResult {
        let tx = unsafe { *Box::from_raw(tx.ptr as *mut Transaction<'_, Sqlite>) };
        let result = match tx.commit().await {
            Ok(_) => Sqlx4kSqliteCipherResult::default(),
            Err(err) => error_result_of(err),
        };
        result.leak()
    }

    pub async fn tx_rollback(&self, tx: Sqlx4kSqliteCipherPtr) -> *mut Sqlx4kSqliteCipherResult {
        let tx = unsafe { *Box::from_raw(tx.ptr as *mut Transaction<'_, Sqlite>) };
        let result = match tx.rollback().await {
            Ok(_) => Sqlx4kSqliteCipherResult::default(),
            Err(err) => error_result_of(err),
        };
        result.leak()
    }

    pub async fn tx_query(
        &self,
        tx: Sqlx4kSqliteCipherPtr,
        sql: String,
    ) -> *mut Sqlx4kSqliteCipherResult {
        let mut tx = unsafe { *Box::from_raw(tx.ptr as *mut Transaction<'_, Sqlite>) };
        let result = tx.execute(AssertSqlSafe(sql)).await;
        let tx = Box::new(tx);
        let tx = Box::into_raw(tx);
        let result = match result {
            Ok(res) => Sqlx4kSqliteCipherResult {
                rows_affected: res.rows_affected(),
                ..Default::default()
            },
            Err(err) => error_result_of(err),
        };
        let result = Sqlx4kSqliteCipherResult {
            tx: tx as *mut c_void,
            ..result
        };
        result.leak()
    }

    pub async fn tx_fetch_all(
        &self,
        tx: Sqlx4kSqliteCipherPtr,
        sql: String,
    ) -> *mut Sqlx4kSqliteCipherResult {
        let mut tx = unsafe { *Box::from_raw(tx.ptr as *mut Transaction<'_, Sqlite>) };
        let result = tx.fetch_all(AssertSqlSafe(sql)).await;
        let tx = Box::new(tx);
        let tx = Box::into_raw(tx);
        let result = result_of(result);
        let result = Sqlx4kSqliteCipherResult {
            tx: tx as *mut c_void,
            ..result
        };
        result.leak()
    }

    pub async fn close(&self) -> *mut Sqlx4kSqliteCipherResult {
        self.pool.close().await;
        Sqlx4kSqliteCipherResult::default().leak()
    }

    pub async fn query_with_params(
        &self,
        sql: String,
        params: Vec<OwnedParam>,
    ) -> *mut Sqlx4kSqliteCipherResult {
        let q = bind_params(sqlx::query::<Sqlite>(AssertSqlSafe(sql)), params);
        let result = q.execute(&self.pool).await;
        let result = match result {
            Ok(res) => Sqlx4kSqliteCipherResult {
                rows_affected: res.rows_affected(),
                ..Default::default()
            },
            Err(err) => error_result_of(err),
        };
        result.leak()
    }

    pub async fn fetch_all_with_params(
        &self,
        sql: String,
        params: Vec<OwnedParam>,
    ) -> *mut Sqlx4kSqliteCipherResult {
        let q = bind_params(sqlx::query::<Sqlite>(AssertSqlSafe(sql)), params);
        let result = q.fetch_all(&self.pool).await;
        result_of(result).leak()
    }

    pub async fn cn_query_with_params(
        &self,
        cn: Sqlx4kSqliteCipherPtr,
        sql: String,
        params: Vec<OwnedParam>,
    ) -> *mut Sqlx4kSqliteCipherResult {
        let cn = unsafe { &mut *(cn.ptr as *mut PoolConnection<Sqlite>) };
        let q = bind_params(sqlx::query::<Sqlite>(AssertSqlSafe(sql)), params);
        let result = q.execute(&mut **cn).await;
        let result = match result {
            Ok(res) => Sqlx4kSqliteCipherResult {
                rows_affected: res.rows_affected(),
                ..Default::default()
            },
            Err(err) => error_result_of(err),
        };
        result.leak()
    }

    pub async fn cn_fetch_all_with_params(
        &self,
        cn: Sqlx4kSqliteCipherPtr,
        sql: String,
        params: Vec<OwnedParam>,
    ) -> *mut Sqlx4kSqliteCipherResult {
        let cn = unsafe { &mut *(cn.ptr as *mut PoolConnection<Sqlite>) };
        let q = bind_params(sqlx::query::<Sqlite>(AssertSqlSafe(sql)), params);
        let result = q.fetch_all(&mut **cn).await;
        result_of(result).leak()
    }

    pub async fn tx_query_with_params(
        &self,
        tx: Sqlx4kSqliteCipherPtr,
        sql: String,
        params: Vec<OwnedParam>,
    ) -> *mut Sqlx4kSqliteCipherResult {
        let mut tx = unsafe { *Box::from_raw(tx.ptr as *mut Transaction<'_, Sqlite>) };
        let q = bind_params(sqlx::query::<Sqlite>(AssertSqlSafe(sql)), params);
        let result = q.execute(&mut *tx).await;
        let tx = Box::new(tx);
        let tx = Box::into_raw(tx);
        let result = match result {
            Ok(res) => Sqlx4kSqliteCipherResult {
                rows_affected: res.rows_affected(),
                ..Default::default()
            },
            Err(err) => error_result_of(err),
        };
        let result = Sqlx4kSqliteCipherResult {
            tx: tx as *mut c_void,
            ..result
        };
        result.leak()
    }

    pub async fn tx_fetch_all_with_params(
        &self,
        tx: Sqlx4kSqliteCipherPtr,
        sql: String,
        params: Vec<OwnedParam>,
    ) -> *mut Sqlx4kSqliteCipherResult {
        let mut tx = unsafe { *Box::from_raw(tx.ptr as *mut Transaction<'_, Sqlite>) };
        let q = bind_params(sqlx::query::<Sqlite>(AssertSqlSafe(sql)), params);
        let result = q.fetch_all(&mut *tx).await;
        let tx = Box::new(tx);
        let tx = Box::into_raw(tx);
        let result = result_of(result);
        let result = Sqlx4kSqliteCipherResult {
            tx: tx as *mut c_void,
            ..result
        };
        result.leak()
    }
}

/// Opens (creating if necessary) an encrypted SQLite database and returns a result whose
/// `rt` field holds the leaked [`Sqlx4kSqliteCipher`] handle, or an error result.
///
/// `password`, when non-empty, is applied as `PRAGMA key` before any other statement, which
/// is how SQLCipher derives the encryption key. `create_if_missing(true)` ensures a freshly
/// created database file is created *encrypted* (using the key), avoiding the trap of creating
/// an unencrypted file first and then failing to open it with a key.
pub fn open(
    url: &str,
    password: &str,
    min_connections: c_int,
    max_connections: c_int,
    acquire_timeout_milis: c_int,
    idle_timeout_milis: c_int,
    max_lifetime_milis: c_int,
) -> *mut Sqlx4kSqliteCipherResult {
    let mut options: SqliteConnectOptions = match url.parse() {
        Ok(o) => o,
        Err(err) => return error_result_of(err).leak(),
    };
    options = options.create_if_missing(true);
    if !password.is_empty() {
        // SQLCipher derives the key from `PRAGMA key = '<passphrase>'`. sqlx inserts the pragma
        // value verbatim, so we must quote it as a SQL string literal ourselves (escaping any
        // embedded single quotes by doubling them); otherwise a passphrase containing characters
        // like '-' produces a SQL syntax error.
        let quoted = format!("'{}'", password.replace('\'', "''"));
        options = options.pragma("key", quoted);
    }

    // Create the tokio runtime.
    let runtime = if RUNTIME.get().is_some() {
        RUNTIME.get().unwrap()
    } else {
        let rt = Runtime::new().unwrap();
        let _ = RUNTIME.set(rt);
        RUNTIME.get().unwrap()
    };

    // Create the db pool options.
    //
    // WAL must be enabled *after* `PRAGMA key`: sqlx applies its built-in `journal_mode` before our
    // custom `key` pragma, so on an encrypted database that switch runs on the still-encrypted file
    // and silently leaves the connection in rollback-journal ("delete") mode. `after_connect` runs
    // once the connection is fully established (key applied), so the WAL switch takes effect. This
    // is a no-op for in-memory databases (they ignore WAL and report "memory").
    let pool = SqlitePoolOptions::new()
        .max_connections(max_connections as u32)
        .after_connect(|conn, _meta| {
            Box::pin(async move {
                sqlx::query("PRAGMA journal_mode = WAL;")
                    .execute(&mut *conn)
                    .await?;
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
    let pool: SqlitePool = match pool {
        Ok(pool) => pool,
        Err(err) => return error_result_of(err).leak(),
    };
    let sqlx4k = Sqlx4kSqliteCipher { pool };
    let sqlx4k = Box::new(sqlx4k);
    let sqlx4k = Box::leak(sqlx4k);

    Sqlx4kSqliteCipherResult {
        rt: sqlx4k as *mut _ as *mut c_void,
        ..Default::default()
    }
    .leak()
}

pub fn pool_size(rt: *mut c_void) -> c_int {
    let sqlx4k = unsafe { &*(rt as *mut Sqlx4kSqliteCipher) };
    sqlx4k.pool.size() as c_int
}

pub fn pool_idle_size(rt: *mut c_void) -> c_int {
    let sqlx4k = unsafe { &*(rt as *mut Sqlx4kSqliteCipher) };
    sqlx4k.pool.num_idle() as c_int
}

pub fn result_of(result: Result<Vec<SqliteRow>, sqlx::Error>) -> Sqlx4kSqliteCipherResult {
    match result {
        Ok(rows) => {
            let schema: Sqlx4kSqliteCipherSchema = if rows.len() > 0 {
                schema_of(rows.get(0).unwrap())
            } else {
                Sqlx4kSqliteCipherSchema::default()
            };

            let schema = Box::new(schema);
            let schema = Box::leak(schema);

            let rows: Vec<Sqlx4kSqliteCipherRow> = rows.iter().map(|r| row_of(r)).collect();
            let size = rows.len();
            let rows: Box<[Sqlx4kSqliteCipherRow]> = rows.into_boxed_slice();
            let rows: &mut [Sqlx4kSqliteCipherRow] = Box::leak(rows);
            let rows: *mut Sqlx4kSqliteCipherRow = rows.as_mut_ptr();

            Sqlx4kSqliteCipherResult {
                schema,
                size: size as c_int,
                rows,
                ..Default::default()
            }
        }
        Err(err) => error_result_of(err),
    }
}

fn schema_of(row: &SqliteRow) -> Sqlx4kSqliteCipherSchema {
    let columns = row.columns();
    if columns.is_empty() {
        Sqlx4kSqliteCipherSchema::default()
    } else {
        let columns: Vec<Sqlx4kSqliteCipherSchemaColumn> = row
            .columns()
            .iter()
            .map(|c| {
                let name: &str = c.name();
                let value_ref: SqliteValueRef = row.try_get_raw(c.ordinal()).unwrap();
                let info: std::borrow::Cow<SqliteTypeInfo> = value_ref.type_info();
                let kind: &str = info.name();
                Sqlx4kSqliteCipherSchemaColumn {
                    ordinal: c.ordinal() as c_int,
                    name: CString::new(name).unwrap().into_raw(),
                    kind: CString::new(kind).unwrap().into_raw(),
                }
            })
            .collect();

        let size = columns.len();
        let columns: Box<[Sqlx4kSqliteCipherSchemaColumn]> = columns.into_boxed_slice();
        let columns: &mut [Sqlx4kSqliteCipherSchemaColumn] = Box::leak(columns);
        let columns: *mut Sqlx4kSqliteCipherSchemaColumn = columns.as_mut_ptr();

        Sqlx4kSqliteCipherSchema {
            size: size as c_int,
            columns,
        }
    }
}

fn row_of(row: &SqliteRow) -> Sqlx4kSqliteCipherRow {
    let columns = row.columns();
    if columns.is_empty() {
        Sqlx4kSqliteCipherRow::default()
    } else {
        let columns: Vec<Sqlx4kSqliteCipherColumn> = row
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

                Sqlx4kSqliteCipherColumn {
                    ordinal: c.ordinal() as c_int,
                    value,
                }
            })
            .collect();

        let size = columns.len();
        let columns: Box<[Sqlx4kSqliteCipherColumn]> = columns.into_boxed_slice();
        let columns: &mut [Sqlx4kSqliteCipherColumn] = Box::leak(columns);
        let columns: *mut Sqlx4kSqliteCipherColumn = columns.as_mut_ptr();

        Sqlx4kSqliteCipherRow {
            size: size as c_int,
            columns,
        }
    }
}

// ============================================================================
// Byte-buffer serialization (JNI bridge)
// ============================================================================
//
// The JVM/Android side cannot dereference the C result graph directly, so the JNI layer
// serializes a whole `Sqlx4kSqliteCipherResult` into one length-prefixed, big-endian byte
// buffer that Kotlin decodes in a single pass. This keeps result shaping (including the
// hex-encoding of BLOBs) identical to the FFI/cinterop path. Mirror of the Kotlin decoder
// in jniMain `jni.kt`.
//
// Layout:
//   i32 error                         (-1 = OK; >= 0 maps to SQLError.Code ordinal)
//   u8  has_error_message; [str]
//   i64 rows_affected
//   i64 cn  (pointer as i64, 0 = null)
//   i64 tx
//   i64 rt
//   u8  has_schema; [i32 col_count; { i32 ordinal, str name, str kind } * col_count]
//   i32 row_count;  [{ i32 col_count; { i32 ordinal, u8 has_value, [str value] } * col_count } * row_count]
// where str = i32 utf8_len + utf8 bytes.
//
// Only compiled where the JNI bridge exists (iOS uses the FFI/cinterop path exclusively, so this
// would otherwise be flagged as dead code there).

#[cfg(not(target_os = "ios"))]
struct ByteBuf {
    inner: Vec<u8>,
}

#[cfg(not(target_os = "ios"))]
impl ByteBuf {
    fn new() -> Self {
        ByteBuf { inner: Vec::new() }
    }
    fn put_u8(&mut self, v: u8) {
        self.inner.push(v);
    }
    fn put_i32(&mut self, v: i32) {
        self.inner.extend_from_slice(&v.to_be_bytes());
    }
    fn put_i64(&mut self, v: i64) {
        self.inner.extend_from_slice(&v.to_be_bytes());
    }
    /// Writes an optional C string as: u8 present-flag, then (when present) i32 len + utf8 bytes.
    fn put_opt_cstr(&mut self, ptr: *const c_char) {
        if ptr.is_null() {
            self.put_u8(0);
        } else {
            self.put_u8(1);
            let bytes = unsafe { CStr::from_ptr(ptr) }.to_bytes();
            self.put_i32(bytes.len() as i32);
            self.inner.extend_from_slice(bytes);
        }
    }
    /// Writes a required C string as: i32 len + utf8 bytes.
    fn put_cstr(&mut self, ptr: *const c_char) {
        let bytes = if ptr.is_null() {
            &[][..]
        } else {
            unsafe { CStr::from_ptr(ptr) }.to_bytes()
        };
        self.put_i32(bytes.len() as i32);
        self.inner.extend_from_slice(bytes);
    }
}

/// Serializes a leaked result into a byte buffer **without** freeing it. The caller is
/// responsible for calling [`free_result`] afterwards.
#[cfg(not(target_os = "ios"))]
pub fn serialize_result(ptr: *const Sqlx4kSqliteCipherResult) -> Vec<u8> {
    let result: &Sqlx4kSqliteCipherResult = unsafe { &*ptr };
    let mut buf = ByteBuf::new();

    buf.put_i32(result.error);
    if result.error >= 0 {
        buf.put_opt_cstr(result.error_message);
    } else {
        buf.put_u8(0);
    }
    buf.put_i64(result.rows_affected as i64);
    buf.put_i64(result.cn as usize as i64);
    buf.put_i64(result.tx as usize as i64);
    buf.put_i64(result.rt as usize as i64);

    // Schema.
    if result.schema.is_null() {
        buf.put_u8(0);
    } else {
        let schema: &Sqlx4kSqliteCipherSchema = unsafe { &*result.schema };
        if schema.size <= 0 || schema.columns.is_null() {
            // Present but empty schema is encoded as "no schema".
            buf.put_u8(0);
        } else {
            buf.put_u8(1);
            let cols = unsafe { slice::from_raw_parts(schema.columns, schema.size as usize) };
            buf.put_i32(schema.size);
            for c in cols {
                buf.put_i32(c.ordinal);
                buf.put_cstr(c.name);
                buf.put_cstr(c.kind);
            }
        }
    }

    // Rows.
    if result.rows.is_null() || result.size <= 0 {
        buf.put_i32(0);
    } else {
        let rows = unsafe { slice::from_raw_parts(result.rows, result.size as usize) };
        buf.put_i32(result.size);
        for row in rows {
            if row.columns.is_null() || row.size <= 0 {
                buf.put_i32(0);
                continue;
            }
            let cols = unsafe { slice::from_raw_parts(row.columns, row.size as usize) };
            buf.put_i32(row.size);
            for c in cols {
                buf.put_i32(c.ordinal);
                buf.put_opt_cstr(c.value);
            }
        }
    }

    buf.inner
}
