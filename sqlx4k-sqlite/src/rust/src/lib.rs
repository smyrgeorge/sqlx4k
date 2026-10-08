use futures_core::Stream;
use futures_util::TryStreamExt;
use sqlx::error::ErrorKind;
use sqlx::migrate::MigrateDatabase;
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

// Linux-only: provides the `fcntl64` symbol that the bundled `sqlite3.c` references but
// Kotlin/Native's bundled glibc sysroot doesn't export. See the module docs for details.
#[cfg(target_os = "linux")]
mod compat;

// ============================================================================
// Shared constants and types (inlined from sqlx4k)
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
pub const KIND_DEADLOCK: c_int = 5;
pub const KIND_SERIALIZATION_FAILURE: c_int = 6;
pub const KIND_LOCK_TIMEOUT: c_int = 7;
pub const KIND_OTHER: c_int = 8;

#[repr(C)]
pub struct Sqlx4kSqlitePtr {
    pub ptr: *mut c_void,
}
unsafe impl Send for Sqlx4kSqlitePtr {}
unsafe impl Sync for Sqlx4kSqlitePtr {}

impl Sqlx4kSqlitePtr {
    /// The wrapped pointer. Going through a method (rather than the `ptr` field) inside a spawned
    /// task captures the whole `Send` wrapper, not the raw pointer field (edition-2021 captures).
    fn raw(&self) -> *mut c_void {
        self.ptr
    }
}

#[repr(C)]
pub struct Sqlx4kSqliteResult {
    pub error: c_int,
    pub error_message: *mut c_char,
    /// SQLSTATE reported by the database, or null (SQLite has none).
    pub error_sql_state: *mut c_char,
    /// The database's own numeric error code, or `NO_NATIVE_CODE` (PostgreSQL has none).
    pub error_native_code: c_int,
    /// `SQLError.Kind` ordinal (`KIND_*`).
    pub error_kind: c_int,
    pub rows_affected: c_ulonglong,
    pub cn: *mut c_void,
    pub tx: *mut c_void,
    pub rt: *mut c_void,
    /// A row stream opened by `sqlx4k_sqlite_*_stream_open` (see `Sqlx4kSqliteStream`), or null.
    pub stream: *mut c_void,
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
            error_sql_state: null_mut(),
            error_native_code: NO_NATIVE_CODE,
            error_kind: KIND_OTHER,
            rows_affected: 0,
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
        if !ptr.error_sql_state.is_null() {
            let error_sql_state = unsafe { CString::from_raw(ptr.error_sql_state) };
            std::mem::drop(error_sql_state);
        }
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
    // A database error also carries what SQLite reported: the extended result code (which sqlx
    // exposes through `code()` as a decimal string) and a portable kind (unique violation, foreign
    // key violation, ...), surfaced on `SQLError`. SQLite has no SQLSTATE.
    let (sql_state, native_code, kind): (Option<String>, c_int, c_int) = match &err {
        Error::Database(e) => {
            let code = e.code().and_then(|c| c.parse::<c_int>().ok());
            let kind = sqlx4k_sqlite_kind_of(e.kind(), code);
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
    Sqlx4kSqliteResult {
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
fn sqlx4k_sqlite_constraint_kind_of(kind: ErrorKind) -> Option<c_int> {
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
fn sqlx4k_sqlite_kind_of(kind: ErrorKind, code: Option<c_int>) -> c_int {
    if let Some(kind) = sqlx4k_sqlite_constraint_kind_of(kind) {
        return kind;
    }
    match code.map(|c| c & 0xff) {
        // SQLITE_BUSY, SQLITE_LOCKED
        Some(5 | 6) => KIND_LOCK_TIMEOUT,
        _ => KIND_OTHER,
    }
}

pub fn c_chars_to_str_sqlite<'a>(c_chars: *const c_char) -> &'a str {
    unsafe { CStr::from_ptr(c_chars).to_str().unwrap() }
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

// ============================================================================
// SQLite-specific implementation
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
/// sends the chunks through `rx`. `sqlx4k_sqlite_stream_next` receives one chunk;
/// `sqlx4k_sqlite_stream_close` stops the task (dropping `cancel`) and joins it before freeing the
/// handle, so the connection or transaction the task was borrowing is no longer in use once Kotlin
/// continues. Dropping the stream resets the statement, so a cancelled stream costs nothing and
/// the connection is simply returned to the pool.
struct Sqlx4kSqliteStream {
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

fn stream_fetch_size(fetch_size: c_int) -> usize {
    if fetch_size > 0 {
        fetch_size as usize
    } else {
        STREAM_DEFAULT_FETCH_SIZE
    }
}

fn stream_result_of(stream: Sqlx4kSqliteStream) -> *mut Sqlx4kSqliteResult {
    Sqlx4kSqliteResult {
        stream: Box::into_raw(Box::new(stream)) as *mut c_void,
        ..Default::default()
    }
    .leak()
}

static RUNTIME: OnceLock<Runtime> = OnceLock::new();

#[derive(Debug)]
struct Sqlx4kSqlite {
    pool: SqlitePool,
}

impl Sqlx4kSqlite {
    async fn query(&self, sql: String) -> *mut Sqlx4kSqliteResult {
        let result = self.pool.execute(AssertSqlSafe(sql)).await;
        let result = match result {
            Ok(res) => Sqlx4kSqliteResult {
                rows_affected: res.rows_affected(),
                ..Default::default()
            },
            Err(err) => sqlx4k_sqlite_error_result_of(err),
        };
        result.leak()
    }

    async fn fetch_all(&self, sql: String) -> *mut Sqlx4kSqliteResult {
        let result = self.pool.fetch_all(AssertSqlSafe(sql)).await;
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

    async fn cn_query(&self, cn: Sqlx4kSqlitePtr, sql: String) -> *mut Sqlx4kSqliteResult {
        let cn = unsafe { &mut *(cn.ptr as *mut PoolConnection<Sqlite>) };
        let result = cn.execute(AssertSqlSafe(sql)).await;
        let result = match result {
            Ok(res) => Sqlx4kSqliteResult {
                rows_affected: res.rows_affected(),
                ..Default::default()
            },
            Err(err) => sqlx4k_sqlite_error_result_of(err),
        };
        result.leak()
    }

    async fn cn_fetch_all(&self, cn: Sqlx4kSqlitePtr, sql: String) -> *mut Sqlx4kSqliteResult {
        let cn = unsafe { &mut *(cn.ptr as *mut PoolConnection<Sqlite>) };
        let result = cn.fetch_all(AssertSqlSafe(sql)).await;
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

    async fn tx_query(&self, tx: Sqlx4kSqlitePtr, sql: String) -> *mut Sqlx4kSqliteResult {
        let mut tx = unsafe { *Box::from_raw(tx.ptr as *mut Transaction<'_, Sqlite>) };
        let result = tx.execute(AssertSqlSafe(sql)).await;
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

    async fn tx_fetch_all(&self, tx: Sqlx4kSqlitePtr, sql: String) -> *mut Sqlx4kSqliteResult {
        let mut tx = unsafe { *Box::from_raw(tx.ptr as *mut Transaction<'_, Sqlite>) };
        let result = tx.fetch_all(AssertSqlSafe(sql)).await;
        let tx = Box::new(tx);
        let tx = Box::into_raw(tx);
        let result = sqlx4k_sqlite_result_of(result);
        let result = Sqlx4kSqliteResult {
            tx: tx as *mut c_void,
            ..result
        };
        result.leak()
    }

    async fn stream_open(
        &self,
        sql: String,
        params: Vec<OwnedParam>,
        fetch_size: usize,
    ) -> *mut Sqlx4kSqliteResult {
        let mut cn: PoolConnection<Sqlite> = match self.pool.acquire().await {
            Ok(cn) => cn,
            Err(err) => return sqlx4k_sqlite_error_result_of(err).leak(),
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
        stream_result_of(Sqlx4kSqliteStream {
            rx: tokio::sync::Mutex::new(rx),
            cancel,
            task,
        })
    }

    async fn cn_stream_open(
        &self,
        cn: Sqlx4kSqlitePtr,
        sql: String,
        params: Vec<OwnedParam>,
        fetch_size: usize,
    ) -> *mut Sqlx4kSqliteResult {
        let (tx, rx) = mpsc::channel(STREAM_CHANNEL_CAPACITY);
        let (cancel, cancel_rx) = oneshot::channel();
        let task = RUNTIME.get().unwrap().spawn(async move {
            // The connection stays with Kotlin, which serializes its use and closes this stream
            // before any other statement.
            let cn = unsafe { &mut *(cn.raw() as *mut PoolConnection<Sqlite>) };
            let rows = stream_rows_of(&mut **cn, sql, params);
            stream_rows(rows, fetch_size, tx, cancel_rx).await;
        });
        stream_result_of(Sqlx4kSqliteStream {
            rx: tokio::sync::Mutex::new(rx),
            cancel,
            task,
        })
    }

    async fn tx_stream_open(
        &self,
        tx: Sqlx4kSqlitePtr,
        sql: String,
        params: Vec<OwnedParam>,
        fetch_size: usize,
    ) -> *mut Sqlx4kSqliteResult {
        let (chunks, rx) = mpsc::channel(STREAM_CHANNEL_CAPACITY);
        let (cancel, cancel_rx) = oneshot::channel();
        let task = RUNTIME.get().unwrap().spawn(async move {
            // Borrowed in place (not re-boxed like `tx_fetch_all`), so the Kotlin-held pointer stays valid.
            let tx = unsafe { &mut *(tx.raw() as *mut Transaction<'static, Sqlite>) };
            let rows = stream_rows_of(&mut **tx, sql, params);
            stream_rows(rows, fetch_size, chunks, cancel_rx).await;
        });
        stream_result_of(Sqlx4kSqliteStream {
            rx: tokio::sync::Mutex::new(rx),
            cancel,
            task,
        })
    }

    async fn stream_next(&self, stream: Sqlx4kSqlitePtr) -> *mut Sqlx4kSqliteResult {
        let stream = unsafe { &*(stream.ptr as *const Sqlx4kSqliteStream) };
        let mut rx = stream.rx.lock().await;
        match rx.recv().await {
            Some(chunk) => sqlx4k_sqlite_result_of(chunk).leak(),
            // End of the stream: an empty result (size 0, no schema).
            None => Sqlx4kSqliteResult::default().leak(),
        }
    }

    async fn stream_close(&self, stream: Sqlx4kSqlitePtr) -> *mut Sqlx4kSqliteResult {
        let stream = unsafe { Box::from_raw(stream.ptr as *mut Sqlx4kSqliteStream) };
        let Sqlx4kSqliteStream { rx, cancel, task } = *stream;
        // Stop the producer (dropping the sender fires its cancel branch, dropping the receiver fails
        // its next send) and wait for it: only then is the connection it borrowed free again.
        drop(cancel);
        drop(rx);
        let _ = task.await;
        Sqlx4kSqliteResult::default().leak()
    }

    async fn close(&self) -> *mut Sqlx4kSqliteResult {
        self.pool.close().await;
        Sqlx4kSqliteResult::default().leak()
    }

    async fn query_with_params(
        &self,
        sql: String,
        params: Vec<OwnedParam>,
    ) -> *mut Sqlx4kSqliteResult {
        let q = bind_params(sqlx::query::<Sqlite>(AssertSqlSafe(sql)), params);
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
        sql: String,
        params: Vec<OwnedParam>,
    ) -> *mut Sqlx4kSqliteResult {
        let q = bind_params(sqlx::query::<Sqlite>(AssertSqlSafe(sql)), params);
        let result = q.fetch_all(&self.pool).await;
        sqlx4k_sqlite_result_of(result).leak()
    }

    async fn cn_query_with_params(
        &self,
        cn: Sqlx4kSqlitePtr,
        sql: String,
        params: Vec<OwnedParam>,
    ) -> *mut Sqlx4kSqliteResult {
        let cn = unsafe { &mut *(cn.ptr as *mut PoolConnection<Sqlite>) };
        let q = bind_params(sqlx::query::<Sqlite>(AssertSqlSafe(sql)), params);
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
        sql: String,
        params: Vec<OwnedParam>,
    ) -> *mut Sqlx4kSqliteResult {
        let cn = unsafe { &mut *(cn.ptr as *mut PoolConnection<Sqlite>) };
        let q = bind_params(sqlx::query::<Sqlite>(AssertSqlSafe(sql)), params);
        let result = q.fetch_all(&mut **cn).await;
        sqlx4k_sqlite_result_of(result).leak()
    }

    async fn tx_query_with_params(
        &self,
        tx: Sqlx4kSqlitePtr,
        sql: String,
        params: Vec<OwnedParam>,
    ) -> *mut Sqlx4kSqliteResult {
        let mut tx = unsafe { *Box::from_raw(tx.ptr as *mut Transaction<'_, Sqlite>) };
        let q = bind_params(sqlx::query::<Sqlite>(AssertSqlSafe(sql)), params);
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
        sql: String,
        params: Vec<OwnedParam>,
    ) -> *mut Sqlx4kSqliteResult {
        let mut tx = unsafe { *Box::from_raw(tx.ptr as *mut Transaction<'_, Sqlite>) };
        let q = bind_params(sqlx::query::<Sqlite>(AssertSqlSafe(sql)), params);
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
    let options: SqliteConnectOptions = match url.parse() {
        Ok(o) => o,
        Err(err) => return sqlx4k_sqlite_error_result_of(err).leak(),
    };
    // `mode` decides whether a missing file may be created and whether the connection is writable,
    // exactly as on the JVM and Android drivers (and as SQLite defines `ro` / `rw` / `rwc`).
    let mode = sqlite_url_mode(url);
    let read_only = mode == Some("ro");
    let create_if_missing = matches!(mode, None | Some("rwc"));

    // Create the tokio runtime.
    let runtime = if RUNTIME.get().is_some() {
        RUNTIME.get().unwrap()
    } else {
        let rt = Runtime::new().unwrap();
        RUNTIME.set(rt).unwrap();
        RUNTIME.get().unwrap()
    };

    // Create the db pool options.
    //
    // Enable WAL via `after_connect` (which runs once the connection is fully established) rather
    // than the built-in `journal_mode` option, keeping this identical to the encrypted
    // `sqlx4k-sqlite-cipher` driver. There the built-in option can't be used — it runs before
    // `PRAGMA key` and silently fails on the still-encrypted file — so both drivers set WAL the same
    // way. WAL is sqlx's default anyway, and this is a no-op for in-memory databases ("memory").
    // It is skipped for `mode=ro`: changing the journal mode writes to the database header, which a
    // read-only connection cannot do (it would fail with "attempt to write a readonly database").
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

    // Create the database file if it does not exist, but only for the default mode and `mode=rwc`:
    // `ro` and `rw` must fail on a missing file instead (the pool below then reports that error).
    // Creation can legitimately fail too (e.g. an unwritable directory), so surface it as an error.
    if create_if_missing {
        let created = runtime.block_on(async {
            if !sqlx::Sqlite::database_exists(&url).await? {
                sqlx::Sqlite::create_database(&url).await?;
            }
            Ok::<(), sqlx::Error>(())
        });
        if let Err(err) = created {
            return sqlx4k_sqlite_error_result_of(err).leak();
        }
    }

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
        let result = sqlx4k.query(sql).await;
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
        let result = sqlx4k.fetch_all(sql).await;
        fun(callback, result)
    });
}

#[no_mangle]
pub extern "C" fn sqlx4k_sqlite_stream_open(
    rt: *mut c_void,
    sql: *const c_char,
    params: *const Sqlx4kSqliteParam,
    params_len: c_int,
    fetch_size: c_int,
    callback: *mut c_void,
    fun: extern "C" fn(Sqlx4kSqlitePtr, *mut Sqlx4kSqliteResult),
) {
    let callback = Sqlx4kSqlitePtr { ptr: callback };
    let sql = c_chars_to_str_sqlite(sql).to_owned();
    let owned = read_params(params, params_len);
    let fetch_size = stream_fetch_size(fetch_size);
    let runtime = RUNTIME.get().unwrap();
    let sqlx4k = unsafe { &*(rt as *mut Sqlx4kSqlite) };
    runtime.spawn(async move {
        let result = sqlx4k.stream_open(sql, owned, fetch_size).await;
        fun(callback, result)
    });
}

#[no_mangle]
pub extern "C" fn sqlx4k_sqlite_cn_stream_open(
    rt: *mut c_void,
    cn: *mut c_void,
    sql: *const c_char,
    params: *const Sqlx4kSqliteParam,
    params_len: c_int,
    fetch_size: c_int,
    callback: *mut c_void,
    fun: extern "C" fn(Sqlx4kSqlitePtr, *mut Sqlx4kSqliteResult),
) {
    let callback = Sqlx4kSqlitePtr { ptr: callback };
    let cn = Sqlx4kSqlitePtr { ptr: cn };
    let sql = c_chars_to_str_sqlite(sql).to_owned();
    let owned = read_params(params, params_len);
    let fetch_size = stream_fetch_size(fetch_size);
    let runtime = RUNTIME.get().unwrap();
    let sqlx4k = unsafe { &*(rt as *mut Sqlx4kSqlite) };
    runtime.spawn(async move {
        let result = sqlx4k.cn_stream_open(cn, sql, owned, fetch_size).await;
        fun(callback, result)
    });
}

#[no_mangle]
pub extern "C" fn sqlx4k_sqlite_tx_stream_open(
    rt: *mut c_void,
    tx: *mut c_void,
    sql: *const c_char,
    params: *const Sqlx4kSqliteParam,
    params_len: c_int,
    fetch_size: c_int,
    callback: *mut c_void,
    fun: extern "C" fn(Sqlx4kSqlitePtr, *mut Sqlx4kSqliteResult),
) {
    let callback = Sqlx4kSqlitePtr { ptr: callback };
    let tx = Sqlx4kSqlitePtr { ptr: tx };
    let sql = c_chars_to_str_sqlite(sql).to_owned();
    let owned = read_params(params, params_len);
    let fetch_size = stream_fetch_size(fetch_size);
    let runtime = RUNTIME.get().unwrap();
    let sqlx4k = unsafe { &*(rt as *mut Sqlx4kSqlite) };
    runtime.spawn(async move {
        let result = sqlx4k.tx_stream_open(tx, sql, owned, fetch_size).await;
        fun(callback, result)
    });
}

#[no_mangle]
pub extern "C" fn sqlx4k_sqlite_stream_next(
    rt: *mut c_void,
    stream: *mut c_void,
    callback: *mut c_void,
    fun: extern "C" fn(Sqlx4kSqlitePtr, *mut Sqlx4kSqliteResult),
) {
    let callback = Sqlx4kSqlitePtr { ptr: callback };
    let stream = Sqlx4kSqlitePtr { ptr: stream };
    let runtime = RUNTIME.get().unwrap();
    let sqlx4k = unsafe { &*(rt as *mut Sqlx4kSqlite) };
    runtime.spawn(async move {
        let result = sqlx4k.stream_next(stream).await;
        fun(callback, result)
    });
}

#[no_mangle]
pub extern "C" fn sqlx4k_sqlite_stream_close(
    rt: *mut c_void,
    stream: *mut c_void,
    callback: *mut c_void,
    fun: extern "C" fn(Sqlx4kSqlitePtr, *mut Sqlx4kSqliteResult),
) {
    let callback = Sqlx4kSqlitePtr { ptr: callback };
    let stream = Sqlx4kSqlitePtr { ptr: stream };
    let runtime = RUNTIME.get().unwrap();
    let sqlx4k = unsafe { &*(rt as *mut Sqlx4kSqlite) };
    runtime.spawn(async move {
        let result = sqlx4k.stream_close(stream).await;
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
        let result = sqlx4k.tx_query(tx, sql).await;
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
        let result = sqlx4k.tx_fetch_all(tx, sql).await;
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
        let result = sqlx4k.query_with_params(sql, owned).await;
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
        let result = sqlx4k.fetch_all_with_params(sql, owned).await;
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
    let owned = read_params(params, params_len);
    let runtime = RUNTIME.get().unwrap();
    let sqlx4k = unsafe { &*(rt as *mut Sqlx4kSqlite) };
    runtime.spawn(async move {
        let result = sqlx4k.cn_fetch_all_with_params(cn, sql, owned).await;
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
        let result = sqlx4k.tx_query_with_params(tx, sql, owned).await;
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
        let result = sqlx4k.tx_fetch_all_with_params(tx, sql, owned).await;
        fun(callback, result)
    });
}
