//! Shared SQLite (SQLCipher) engine.
//!
//! Holds the connection pool, the async query methods, the C-ABI result types, parameter
//! binding, error mapping, and the byte-buffer (de)serialization used by the JNI bridge.
//! Both [`crate::ffi`] (cinterop) and [`crate::jni`] (JVM/Android) call into here, so this is
//! the single source of truth for query execution and result shaping.

use futures_core::Stream;
use futures_util::TryStreamExt;
use sqlx::error::ErrorKind;
use sqlx::pool::PoolConnection;
use sqlx::sqlite::{
    SqliteConnectOptions, SqliteConnection, SqlitePool, SqlitePoolOptions, SqliteRow,
    SqliteTypeInfo, SqliteValueRef,
};
use sqlx::{
    Acquire, AssertSqlSafe, Column, Error, Executor, Row, Sqlite, Transaction, TypeInfo, ValueRef,
};
use std::{
    ffi::{c_char, c_int, c_ulonglong, c_void, CStr, CString},
    pin::Pin,
    ptr::null_mut,
    slice,
    sync::OnceLock,
    time::Duration,
};
use tokio::runtime::Runtime;
use tokio::sync::{mpsc, oneshot};
use tokio::task::JoinHandle;

// ============================================================================
// Shared constants and types
// ============================================================================

pub const OK: c_int = -1;
pub const ERROR_DATABASE: c_int = 0;
pub const ERROR_POOL_TIMED_OUT: c_int = 1;
pub const ERROR_POOL_CLOSED: c_int = 2;
pub const ERROR_WORKER_CRASHED: c_int = 3;
pub const ERROR_POOL: c_int = 5;

/// Reported in `error_native_code` when the database has no numeric error code.
pub const NO_NATIVE_CODE: c_int = -1;

// `SQLError.Kind` ordinals (IMPORTANT: keep in sync with the Kotlin enum; do not reorder).
pub const KIND_UNIQUE_VIOLATION: c_int = 0;
pub const KIND_FOREIGN_KEY_VIOLATION: c_int = 1;
pub const KIND_NOT_NULL_VIOLATION: c_int = 2;
pub const KIND_CHECK_VIOLATION: c_int = 3;
pub const KIND_EXCLUSION_VIOLATION: c_int = 4;
#[allow(dead_code)] // SQLite has no deadlock detection; kept so the ordinals stay in sync.
pub const KIND_DEADLOCK: c_int = 5;
#[allow(dead_code)] // SQLite has no serializable isolation failures; kept so the ordinals stay in sync.
pub const KIND_SERIALIZATION_FAILURE: c_int = 6;
pub const KIND_LOCK_TIMEOUT: c_int = 7;
pub const KIND_OTHER: c_int = 8;

#[repr(C)]
pub struct Sqlx4kSqliteCipherPtr {
    pub ptr: *mut c_void,
}
unsafe impl Send for Sqlx4kSqliteCipherPtr {}
unsafe impl Sync for Sqlx4kSqliteCipherPtr {}

impl Sqlx4kSqliteCipherPtr {
    /// The wrapped pointer. Going through a method (rather than the `ptr` field) inside a spawned
    /// task captures the whole `Send` wrapper, not the raw pointer field (edition-2021 captures).
    fn raw(&self) -> *mut c_void {
        self.ptr
    }
}

#[repr(C)]
pub struct Sqlx4kSqliteCipherResult {
    pub error: c_int,
    pub error_message: *mut c_char,
    /// SQLSTATE reported by the database, or null (SQLite has none).
    pub error_sql_state: *mut c_char,
    /// The database's own numeric error code, or `NO_NATIVE_CODE` (PostgreSQL has none).
    pub error_native_code: c_int,
    /// `SQLError.Kind` ordinal (`KIND_*`).
    pub error_kind: c_int,
    pub rows_affected: c_ulonglong,
    /// The rowid of the last row inserted on the connection (SQLite tracks it per connection, not per statement).
    pub last_insert_id: i64,
    pub cn: *mut c_void,
    pub tx: *mut c_void,
    pub rt: *mut c_void,
    /// A row stream opened by `*_stream_open` (see `Sqlx4kSqliteCipherStream`), or null.
    pub stream: *mut c_void,
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
            error_sql_state: null_mut(),
            error_native_code: NO_NATIVE_CODE,
            error_kind: KIND_OTHER,
            rows_affected: 0,
            last_insert_id: 0,
            cn: null_mut(),
            tx: null_mut(),
            rt: null_mut(),
            stream: null_mut(),
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
    /// NUL-terminated, but TEXT may itself contain NUL bytes: read `value_len` bytes.
    pub value: *mut c_char,
    /// Length of `value` in bytes, excluding the terminator.
    pub value_len: c_int,
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
    /// Length of `text` in bytes; TEXT may contain NUL, so it is not read as a C string.
    pub text_len: c_int,
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
                let len = p.text_len.max(0) as usize;
                let bytes = if len == 0 || p.text.is_null() {
                    &[][..]
                } else {
                    unsafe { slice::from_raw_parts(p.text as *const u8, len) }
                };
                OwnedParam::Text(String::from_utf8_lossy(bytes).into_owned())
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
        if !ptr.error_sql_state.is_null() {
            let error_sql_state = unsafe { CString::from_raw(ptr.error_sql_state) };
            std::mem::drop(error_sql_state);
        }
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
            free_column_value(col.value, col.value_len);
        }
    }
}

pub fn error_result_of(err: sqlx::Error) -> Sqlx4kSqliteCipherResult {
    // A database error also carries what SQLite reported: the extended result code (which sqlx
    // exposes through `code()` as a decimal string) and a portable kind (unique violation, foreign
    // key violation, ...), surfaced on `SQLError`. SQLite has no SQLSTATE.
    let (sql_state, native_code, kind): (Option<String>, c_int, c_int) = match &err {
        Error::Database(e) => {
            let code = e.code().and_then(|c| c.parse::<c_int>().ok());
            let kind = kind_of(e.kind(), code);
            (None, code.unwrap_or(NO_NATIVE_CODE), kind)
        }
        _ => (None, NO_NATIVE_CODE, KIND_OTHER),
    };

    let (code, message) = match err {
        Error::Configuration(e) => (ERROR_POOL, format!("Invalid SQLite URL :: {}", e)),
        Error::Database(e) => match e.code() {
            Some(code) => (ERROR_DATABASE, format!("[{}] {}", code, e.to_string())),
            None => (ERROR_DATABASE, format!("{}", e.to_string())),
        },
        Error::PoolTimedOut => (ERROR_POOL_TIMED_OUT, "PoolTimedOut".to_string()),
        Error::PoolClosed => (
            ERROR_POOL_CLOSED,
            "The connection pool is already closed".to_string(),
        ),
        Error::WorkerCrashed => (ERROR_WORKER_CRASHED, "WorkerCrashed".to_string()),
        // Any other error (Io, Protocol, Decode, ...) is reported as a database error.
        // Never panic here: with `panic = "abort"`, a failed I/O operation would terminate the process.
        e => (ERROR_DATABASE, e.to_string()),
    };

    // CString::new fails on interior NUL bytes, which may appear in values echoed back by the database.
    let message = CString::new(message.replace('\0', "")).unwrap();
    let sql_state = sql_state
        .map(|s| CString::new(s.replace('\0', "")).unwrap().into_raw())
        .unwrap_or(null_mut());
    Sqlx4kSqliteCipherResult {
        error: code,
        error_message: message.into_raw(),
        error_sql_state: sql_state,
        error_native_code: native_code,
        error_kind: kind,
        ..Default::default()
    }
}

/// Maps sqlx's portable [`ErrorKind`] (constraint violations) to the `SQLError.Kind` ordinal, or
/// `None` when sqlx does not classify the error.
fn constraint_kind_of(kind: ErrorKind) -> Option<c_int> {
    match kind {
        ErrorKind::UniqueViolation => Some(KIND_UNIQUE_VIOLATION),
        ErrorKind::ForeignKeyViolation => Some(KIND_FOREIGN_KEY_VIOLATION),
        ErrorKind::NotNullViolation => Some(KIND_NOT_NULL_VIOLATION),
        ErrorKind::CheckViolation => Some(KIND_CHECK_VIOLATION),
        ErrorKind::ExclusionViolation => Some(KIND_EXCLUSION_VIOLATION),
        // `ErrorKind` is `#[non_exhaustive]`.
        _ => None,
    }
}

/// Classifies a SQLite error as a `SQLError.Kind` ordinal: constraint violations come from sqlx,
/// lock failures from the primary result code (the low byte of the extended code). Mirrors
/// `sqliteKindOf` in the Kotlin driver.
/// https://www.sqlite.org/rescode.html
fn kind_of(kind: ErrorKind, code: Option<c_int>) -> c_int {
    if let Some(kind) = constraint_kind_of(kind) {
        return kind;
    }
    match code.map(|c| c & 0xff) {
        // SQLITE_BUSY, SQLITE_LOCKED
        Some(5 | 6) => KIND_LOCK_TIMEOUT,
        _ => KIND_OTHER,
    }
}

pub fn c_chars_to_str<'a>(c_chars: *const c_char) -> &'a str {
    unsafe { CStr::from_ptr(c_chars).to_str().unwrap() }
}

// ============================================================================
// SQLite (SQLCipher) implementation
// ============================================================================

// ============================================================================
// Streaming (fetch)
// ============================================================================

/// Rows are moved to Kotlin in chunks of the requested fetch size; the channel holds at most this
/// many ready chunks, which is the backpressure that keeps a slow consumer from buffering a large
/// result on the Rust side.
const STREAM_CHANNEL_CAPACITY: usize = 2;
const STREAM_DEFAULT_FETCH_SIZE: usize = 1_000;

type StreamChunk = Result<Vec<SqliteRow>, sqlx::Error>;
type RowStream<'e> = Pin<Box<dyn Stream<Item = Result<SqliteRow, sqlx::Error>> + Send + 'e>>;

/// A result set being streamed to Kotlin, row chunk by row chunk.
///
/// sqlx steps the statement on its SQLite worker thread and hands the rows over a bounded channel;
/// that stream borrows the connection it runs on, so both live in a spawned task that owns them and
/// sends the chunks through `rx`. `stream_next` receives one chunk; `stream_close` stops the task
/// (dropping `cancel`) and joins it before freeing the handle, so the connection or transaction the
/// task was borrowing is no longer in use once Kotlin continues. Dropping the stream resets the
/// statement, so a cancelled stream costs nothing and the connection is simply returned to the pool.
/// Shared by the FFI (cinterop) and the JNI bridge: the handle crosses both as an opaque pointer.
pub struct Sqlx4kSqliteCipherStream {
    rx: tokio::sync::Mutex<mpsc::Receiver<StreamChunk>>,
    cancel: oneshot::Sender<()>,
    task: JoinHandle<()>,
}

/// Opens the row stream of a query on the given connection: a plain query (as `fetch_all` does),
/// or a prepared statement when there are parameters to bind.
fn stream_rows_of<'e>(
    cn: &'e mut SqliteConnection,
    sql: String,
    params: Vec<OwnedParam>,
) -> RowStream<'e> {
    if params.is_empty() {
        cn.fetch(AssertSqlSafe(sql))
    } else {
        bind_params(sqlx::query::<Sqlite>(AssertSqlSafe(sql)), params).fetch(cn)
    }
}

/// Drives a row stream, sending chunks of `fetch_size` rows until the stream ends or fails, or the
/// consumer closes it (`cancel` fires, or the channel is dropped).
async fn stream_rows(
    mut rows: RowStream<'_>,
    fetch_size: usize,
    tx: mpsc::Sender<StreamChunk>,
    mut cancel: oneshot::Receiver<()>,
) {
    let mut chunk: Vec<SqliteRow> = Vec::with_capacity(fetch_size);
    loop {
        let next = tokio::select! {
            _ = &mut cancel => return,
            next = rows.try_next() => next,
        };
        match next {
            Ok(Some(row)) => {
                chunk.push(row);
                if chunk.len() >= fetch_size {
                    let full = std::mem::replace(&mut chunk, Vec::with_capacity(fetch_size));
                    tokio::select! {
                        _ = &mut cancel => return,
                        sent = tx.send(Ok(full)) => if sent.is_err() { return },
                    }
                }
            }
            Ok(None) => {
                if !chunk.is_empty() {
                    let _ = tx.send(Ok(chunk)).await;
                }
                return;
            }
            Err(err) => {
                let _ = tx.send(Err(err)).await;
                return;
            }
        }
    }
}

/// The chunk size of a stream: the requested one, or the default when none (`<= 0`) was given.
pub fn stream_fetch_size(fetch_size: c_int) -> usize {
    if fetch_size > 0 {
        fetch_size as usize
    } else {
        STREAM_DEFAULT_FETCH_SIZE
    }
}

fn stream_result_of(stream: Sqlx4kSqliteCipherStream) -> *mut Sqlx4kSqliteCipherResult {
    Sqlx4kSqliteCipherResult {
        stream: Box::into_raw(Box::new(stream)) as *mut c_void,
        ..Default::default()
    }
    .leak()
}

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
                last_insert_id: res.last_insert_rowid(),
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
                last_insert_id: res.last_insert_rowid(),
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
                last_insert_id: res.last_insert_rowid(),
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

    pub async fn stream_open(
        &self,
        sql: String,
        params: Vec<OwnedParam>,
        fetch_size: usize,
    ) -> *mut Sqlx4kSqliteCipherResult {
        let mut cn: PoolConnection<Sqlite> = match self.pool.acquire().await {
            Ok(cn) => cn,
            Err(err) => return error_result_of(err).leak(),
        };
        let (tx, rx) = mpsc::channel(STREAM_CHANNEL_CAPACITY);
        let (cancel, cancel_rx) = oneshot::channel();
        let task = RUNTIME.get().unwrap().spawn(async move {
            // The connection goes back to the pool when the task ends, consumed or cancelled alike
            // (unlike the network drivers there is nothing to drain: dropping the stream resets the
            // statement; and closing it would lose an in-memory database).
            let rows = stream_rows_of(&mut cn, sql, params);
            stream_rows(rows, fetch_size, tx, cancel_rx).await;
        });
        stream_result_of(Sqlx4kSqliteCipherStream {
            rx: tokio::sync::Mutex::new(rx),
            cancel,
            task,
        })
    }

    pub async fn cn_stream_open(
        &self,
        cn: Sqlx4kSqliteCipherPtr,
        sql: String,
        params: Vec<OwnedParam>,
        fetch_size: usize,
    ) -> *mut Sqlx4kSqliteCipherResult {
        let (tx, rx) = mpsc::channel(STREAM_CHANNEL_CAPACITY);
        let (cancel, cancel_rx) = oneshot::channel();
        let task = RUNTIME.get().unwrap().spawn(async move {
            // The connection stays with Kotlin, which serializes its use and closes this stream
            // before any other statement.
            let cn = unsafe { &mut *(cn.raw() as *mut PoolConnection<Sqlite>) };
            let rows = stream_rows_of(&mut **cn, sql, params);
            stream_rows(rows, fetch_size, tx, cancel_rx).await;
        });
        stream_result_of(Sqlx4kSqliteCipherStream {
            rx: tokio::sync::Mutex::new(rx),
            cancel,
            task,
        })
    }

    pub async fn tx_stream_open(
        &self,
        tx: Sqlx4kSqliteCipherPtr,
        sql: String,
        params: Vec<OwnedParam>,
        fetch_size: usize,
    ) -> *mut Sqlx4kSqliteCipherResult {
        let (chunks, rx) = mpsc::channel(STREAM_CHANNEL_CAPACITY);
        let (cancel, cancel_rx) = oneshot::channel();
        let task = RUNTIME.get().unwrap().spawn(async move {
            // Borrowed in place (not re-boxed like `tx_fetch_all`), so the Kotlin-held pointer stays valid.
            let tx = unsafe { &mut *(tx.raw() as *mut Transaction<'static, Sqlite>) };
            let rows = stream_rows_of(&mut **tx, sql, params);
            stream_rows(rows, fetch_size, chunks, cancel_rx).await;
        });
        stream_result_of(Sqlx4kSqliteCipherStream {
            rx: tokio::sync::Mutex::new(rx),
            cancel,
            task,
        })
    }

    pub async fn stream_next(
        &self,
        stream: Sqlx4kSqliteCipherPtr,
    ) -> *mut Sqlx4kSqliteCipherResult {
        let stream = unsafe { &*(stream.ptr as *const Sqlx4kSqliteCipherStream) };
        let mut rx = stream.rx.lock().await;
        match rx.recv().await {
            Some(chunk) => result_of(chunk).leak(),
            // End of the stream: an empty result (size 0, no schema).
            None => Sqlx4kSqliteCipherResult::default().leak(),
        }
    }

    pub async fn stream_close(
        &self,
        stream: Sqlx4kSqliteCipherPtr,
    ) -> *mut Sqlx4kSqliteCipherResult {
        let stream = unsafe { Box::from_raw(stream.ptr as *mut Sqlx4kSqliteCipherStream) };
        let Sqlx4kSqliteCipherStream { rx, cancel, task } = *stream;
        // Stop the producer (dropping the sender fires its cancel branch, dropping the receiver fails
        // its next send) and wait for it: only then is the connection it borrowed free again.
        drop(cancel);
        drop(rx);
        let _ = task.await;
        Sqlx4kSqliteCipherResult::default().leak()
    }

    pub async fn close(&self) -> *mut Sqlx4kSqliteCipherResult {
        close_pool(&self.pool).await;
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
                last_insert_id: res.last_insert_rowid(),
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
                last_insert_id: res.last_insert_rowid(),
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
                last_insert_id: res.last_insert_rowid(),
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

/// Closes the pool without waiting for the connections still checked out. `Pool::close()` waits until every
/// checked-out connection is given back (and only then closes the idle ones): one the caller still holds (or abandoned)
/// would hang it for good, and the caller cannot cancel it. The idle connections are closed here instead, and the
/// pool is marked closed; the checked-out ones are closed as they are given back, as the JVM drivers do.
///
/// The idle connections are taken out before the pool is marked closed (a closed pool gives none), but only detached
/// and closed after: a connection detached from an open pool that keeps `min_connections` makes it open a replacement.
async fn close_pool<DB: sqlx::Database>(pool: &sqlx::Pool<DB>) {
    let idle: Vec<_> = std::iter::from_fn(|| pool.try_acquire()).collect();
    // Marks the pool closed when called, before it is awaited: no connection is acquired or opened after this.
    let closing = pool.close();
    for cn in idle {
        let _ = sqlx::Connection::close(cn.detach()).await;
    }
    if pool.size() == 0 {
        closing.await;
    } else {
        drop(closing);
        let closing = pool.clone();
        tokio::spawn(async move { closing.close().await });
    }
}

/// The `mode` query parameter of an sqlx-style SQLite URL (`sqlite:///path.db?mode=ro`), if any.
/// The last occurrence wins, as in sqlx's own parser.
fn sqlite_url_mode(url: &str) -> Option<&str> {
    let query = url.splitn(2, '?').nth(1)?;
    query
        .split('&')
        .filter_map(|kv| {
            let mut it = kv.splitn(2, '=');
            match (it.next(), it.next()) {
                (Some("mode"), Some(v)) => Some(v),
                _ => None,
            }
        })
        .last()
}

/// Opens (creating if necessary) an encrypted SQLite database and returns a result whose
/// `rt` field holds the leaked [`Sqlx4kSqliteCipher`] handle, or an error result.
///
/// `password`, when non-empty, is applied as `PRAGMA key` before any other statement, which
/// is how SQLCipher derives the encryption key. `create_if_missing` (for the default mode and
/// `mode=rwc`) ensures a freshly created database file is created *encrypted* (using the key),
/// avoiding the trap of creating an unencrypted file first and then failing to open it with a key.
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
    // `mode` decides whether a missing file may be created and whether the connection is writable, as on the plain
    // SQLite driver (and as SQLite defines `ro` / `rw` / `rwc`): sqlx parses `mode=rw` as a no-op, so creating the
    // file unconditionally turned a mistyped path into a new, empty database.
    let mode = sqlite_url_mode(url);
    let read_only = mode == Some("ro");
    options = options.create_if_missing(matches!(mode, None | Some("rwc")));
    if !password.is_empty() {
        // SQLCipher derives the key from `PRAGMA key = '<passphrase>'`. sqlx inserts the pragma
        // value verbatim, so we must quote it as a SQL string literal ourselves (escaping any
        // embedded single quotes by doubling them); otherwise a passphrase containing characters
        // like '-' produces a SQL syntax error.
        let quoted = format!("'{}'", password.replace('\'', "''"));
        options = options.pragma("key", quoted);
    }

    // Create the tokio runtime, once: get_or_init also when two pools are created concurrently (checking and then
    // setting raced: a losing set().unwrap() panicked, and the process aborted).
    let runtime = RUNTIME.get_or_init(|| Runtime::new().expect("failed to create the tokio runtime"));

    // Create the db pool options.
    //
    // WAL must be enabled *after* `PRAGMA key`: sqlx applies its built-in `journal_mode` before our
    // custom `key` pragma, so on an encrypted database that switch runs on the still-encrypted file
    // and silently leaves the connection in rollback-journal ("delete") mode. `after_connect` runs
    // once the connection is fully established (key applied), so the WAL switch takes effect. This
    // is a no-op for in-memory databases (they ignore WAL and report "memory"). It is skipped for `mode=ro`: changing
    // the journal mode writes to the database header, which a read-only connection cannot do (it would fail with
    // "attempt to write a readonly database").
    let pool = SqlitePoolOptions::new()
        .max_connections(max_connections as u32)
        .after_connect(move |conn, _meta| {
            Box::pin(async move {
                if !read_only {
                    sqlx::query("PRAGMA journal_mode = WAL;")
                        .execute(&mut *conn)
                        .await?;
                }
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

    let pool = with_pool_timeouts(pool, idle_timeout_milis, max_lifetime_milis);

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
                let value: Option<String> = match type_info {
                    "BLOB" => row.get_unchecked::<Option<&[u8]>, _>(c.ordinal()).map(hex::encode),
                    "REAL" => row.get_unchecked::<Option<f64>, _>(c.ordinal()).map(format_sqlite_real),
                    // TEXT (and INTEGER, which SQLite renders as text). TEXT may hold invalid UTF-8,
                    // which must not abort the host process: replace it with U+FFFD, like the JVM driver.
                    _ => row
                        .get_unchecked::<Option<&[u8]>, _>(c.ordinal())
                        .map(|bytes| String::from_utf8_lossy(bytes).into_owned()),
                };
                let (value, value_len) = match value {
                    Some(v) => column_value_into_raw(v),
                    None => (null_mut(), 0),
                };

                Sqlx4kSqliteCipherColumn {
                    ordinal: c.ordinal() as c_int,
                    value,
                    value_len,
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

/// Hands a column value to the caller as a NUL-terminated buffer plus its length. TEXT may contain
/// NUL bytes, which `CString` cannot hold. Released with `free_column_value`.
fn column_value_into_raw(value: String) -> (*mut c_char, c_int) {
    let len = value.len();
    let mut bytes = value.into_bytes();
    bytes.push(0);
    (Box::into_raw(bytes.into_boxed_slice()) as *mut u8 as *mut c_char, len as c_int)
}

fn free_column_value(value: *mut c_char, len: c_int) {
    if !value.is_null() {
        let bytes = std::ptr::slice_from_raw_parts_mut(value as *mut u8, len as usize + 1);
        drop(unsafe { Box::from_raw(bytes) });
    }
}

/// Renders a REAL in the layout SQLite uses for `cast(x as text)` (`1.5`, `3.0`, `1.0e+300`,
/// `1.23e-05`, `Inf`), but with the shortest digits that round-trip to `value`.
///
/// SQLite's own text depends on its version (the bundled 3.46 keeps only 15 significant digits,
/// so e.g. `avg(..)` loses precision), and every platform ships a different one. Formatting the
/// double here keeps the drivers in agreement; the JVM/Android SQLite driver does the same in Kotlin.
pub(crate) fn format_sqlite_real(value: f64) -> String {
    if value.is_nan() {
        return "NaN".to_string();
    }
    if value.is_infinite() {
        return if value > 0.0 { "Inf" } else { "-Inf" }.to_string();
    }
    if value == 0.0 {
        return "0.0".to_string();
    }
    // `{:e}` prints the shortest round-trip digits, e.g. `2.3333333333333335e0` or `1e300`.
    let sci = format!("{:e}", value.abs());
    let (mantissa, exponent) = sci.split_once('e').unwrap_or((sci.as_str(), "0"));
    let exponent: i32 = exponent.parse().unwrap_or(0);
    let digits: String = mantissa.chars().filter(|c| *c != '.').collect();

    let mut out = String::new();
    if value < 0.0 {
        out.push('-');
    }
    if exponent < -4 || exponent >= 17 {
        out.push_str(&digits[..1]);
        out.push('.');
        out.push_str(if digits.len() > 1 { &digits[1..] } else { "0" });
        out.push_str(&format!("e{}{:02}", if exponent < 0 { '-' } else { '+' }, exponent.abs()));
    } else if exponent >= 0 {
        let int_len = exponent as usize + 1;
        if digits.len() > int_len {
            out.push_str(&digits[..int_len]);
            out.push('.');
            out.push_str(&digits[int_len..]);
        } else {
            out.push_str(&digits);
            out.push_str(&"0".repeat(int_len - digits.len()));
            out.push_str(".0");
        }
    } else {
        out.push_str("0.");
        out.push_str(&"0".repeat((-exponent - 1) as usize));
        out.push_str(&digits);
    }
    out
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
//   u8  has_error_sql_state; [str]   (always absent for SQLite; kept for layout symmetry)
//   i32 error_native_code             (-1 = none; otherwise the SQLite extended result code)
//   i32 error_kind                    (SQLError.Kind ordinal)
//   i64 rows_affected
//   i64 last_insert_id                (the connection's last inserted rowid)
//   i64 cn  (pointer as i64, 0 = null)
//   i64 tx
//   i64 rt
//   i64 stream (pointer as i64, 0 = null; see `Sqlx4kSqliteCipherStream`)
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
    /// Writes an optional buffer of `len` bytes, in the same layout as `put_opt_cstr`.
    fn put_opt_bytes(&mut self, ptr: *const c_char, len: c_int) {
        if ptr.is_null() {
            self.put_u8(0);
        } else {
            self.put_u8(1);
            self.put_i32(len);
            self.inner.extend_from_slice(unsafe { slice::from_raw_parts(ptr as *const u8, len as usize) });
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
        buf.put_opt_cstr(result.error_sql_state);
    } else {
        buf.put_u8(0);
        buf.put_u8(0);
    }
    buf.put_i32(result.error_native_code);
    buf.put_i32(result.error_kind);
    buf.put_i64(result.rows_affected as i64);
    buf.put_i64(result.last_insert_id);
    buf.put_i64(result.cn as usize as i64);
    buf.put_i64(result.tx as usize as i64);
    buf.put_i64(result.rt as usize as i64);
    buf.put_i64(result.stream as usize as i64);

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
                buf.put_opt_bytes(c.value, c.value_len);
            }
        }
    }

    buf.inner
}

/// Applies the idle timeout and max lifetime. A value of 0 or less (Kotlin passes -1 for "none") disables the
/// setting, as on the JVM. Keeping sqlx's defaults (10 minutes idle, 30 minutes lifetime) closed the connections of an
/// in-memory database, which only lives as long as one of them is open, and so silently wiped it.
fn with_pool_timeouts(
    pool: SqlitePoolOptions,
    idle_timeout_milis: c_int,
    max_lifetime_milis: c_int,
) -> SqlitePoolOptions {
    let millis = |m: c_int| (m > 0).then(|| Duration::from_millis(m as u64));
    pool.idle_timeout(millis(idle_timeout_milis)).max_lifetime(millis(max_lifetime_milis))
}

#[cfg(test)]
mod pool_options_tests {
    use super::*;

    #[test]
    fn a_missing_timeout_disables_the_setting() {
        let pool = with_pool_timeouts(SqlitePoolOptions::new(), -1, -1);
        assert_eq!(pool.get_idle_timeout(), None);
        assert_eq!(pool.get_max_lifetime(), None);
    }

    #[test]
    fn a_timeout_is_applied() {
        let pool = with_pool_timeouts(SqlitePoolOptions::new(), 1_000, 2_000);
        assert_eq!(pool.get_idle_timeout(), Some(Duration::from_secs(1)));
        assert_eq!(pool.get_max_lifetime(), Some(Duration::from_secs(2)));
    }
}

#[cfg(test)]
mod real_tests {
    use super::format_sqlite_real;

    #[test]
    fn formats_reals_like_sqlite_with_shortest_digits() {
        let cases: &[(f64, &str)] = &[
            (1.5, "1.5"),
            (3.0, "3.0"),
            (-2.5, "-2.5"),
            (0.0, "0.0"),
            (-0.0, "0.0"),
            (10000.0, "10000.0"),
            (-0.0042, "-0.0042"),
            (0.0001234, "0.0001234"),
            (0.00001234, "1.234e-05"),
            (1.23e-6, "1.23e-06"),
            (123456789.0001, "123456789.0001"),
            (7.0 / 3.0, "2.3333333333333335"),
            (1.0 / 3.0, "0.3333333333333333"),
            (0.1 + 0.2, "0.30000000000000004"),
            (0.1, "0.1"),
            (4.35, "4.35"),
            (1e16, "10000000000000000.0"),
            (9.99e16, "99900000000000000.0"),
            (1e17, "1.0e+17"),
            (123456789012345678.0, "1.2345678901234568e+17"),
            (1e300, "1.0e+300"),
            (-1e-300, "-1.0e-300"),
            (5e-324, "5.0e-324"),
            (f64::MAX, "1.7976931348623157e+308"),
            (f64::INFINITY, "Inf"),
            (f64::NEG_INFINITY, "-Inf"),
        ];
        for (value, expected) in cases {
            assert_eq!(format_sqlite_real(*value), *expected, "formatting {value:e}");
        }
    }
}
