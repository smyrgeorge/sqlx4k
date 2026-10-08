use futures_core::Stream;
use futures_util::TryStreamExt;
use sqlx::error::ErrorKind;
use sqlx::mysql::{
    MySqlConnectOptions, MySqlConnection, MySqlDatabaseError, MySqlPool, MySqlPoolOptions,
    MySqlRow, MySqlTypeInfo, MySqlValueRef,
};
use sqlx::pool::PoolConnection;
// Re-export sqlx's chrono feature shim so we don't need a direct dependency on
// the chrono crate — sqlx already pulls it in when the matching feature is on.
use sqlx::types::chrono;
use sqlx::{
    Acquire, AssertSqlSafe, Column, Connection, Error, Executor, MySql, Row, Transaction, TypeInfo,
    ValueRef,
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
// Shared constants and types (inlined from sqlx4k)
// ============================================================================

pub const OK: c_int = -1;
pub const ERROR_DATABASE: c_int = 0;
pub const ERROR_POOL_TIMED_OUT: c_int = 1;
pub const ERROR_POOL_CLOSED: c_int = 2;
pub const ERROR_WORKER_CRASHED: c_int = 3;

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
pub struct Sqlx4kMysqlPtr {
    pub ptr: *mut c_void,
}
unsafe impl Send for Sqlx4kMysqlPtr {}
unsafe impl Sync for Sqlx4kMysqlPtr {}

impl Sqlx4kMysqlPtr {
    /// The wrapped pointer. Going through a method (rather than the `ptr` field) inside a spawned
    /// task captures the whole `Send` wrapper, not the raw pointer field (edition-2021 captures).
    fn raw(&self) -> *mut c_void {
        self.ptr
    }
}

#[repr(C)]
pub struct Sqlx4kMysqlResult {
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
    /// A row stream opened by `sqlx4k_mysql_*_stream_open` (see `Sqlx4kMysqlStream`), or null.
    pub stream: *mut c_void,
    pub schema: *mut Sqlx4kMysqlSchema,
    pub size: c_int,
    pub rows: *mut Sqlx4kMysqlRow,
}

impl Sqlx4kMysqlResult {
    pub fn leak(self) -> *mut Sqlx4kMysqlResult {
        let result = Box::new(self);
        let result = Box::leak(result);
        result
    }
}

impl Default for Sqlx4kMysqlResult {
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
pub struct Sqlx4kMysqlSchema {
    pub size: c_int,
    pub columns: *mut Sqlx4kMysqlSchemaColumn,
}

impl Default for Sqlx4kMysqlSchema {
    fn default() -> Self {
        Self {
            size: 0,
            columns: null_mut(),
        }
    }
}

#[repr(C)]
pub struct Sqlx4kMysqlSchemaColumn {
    pub ordinal: c_int,
    pub name: *mut c_char,
    pub kind: *mut c_char,
}

#[repr(C)]
pub struct Sqlx4kMysqlRow {
    pub size: c_int,
    pub columns: *mut Sqlx4kMysqlColumn,
}

impl Default for Sqlx4kMysqlRow {
    fn default() -> Self {
        Self {
            size: 0,
            columns: null_mut(),
        }
    }
}

#[repr(C)]
pub struct Sqlx4kMysqlColumn {
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
pub struct Sqlx4kMysqlParam {
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

fn read_params(params: *const Sqlx4kMysqlParam, count: c_int) -> Vec<OwnedParam> {
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
            other => panic!("Unknown Sqlx4kMysqlParam kind: {}", other),
        })
        .collect()
}

fn bind_params<'q>(
    mut q: sqlx::query::Query<'q, MySql, sqlx::mysql::MySqlArguments>,
    params: Vec<OwnedParam>,
) -> sqlx::query::Query<'q, MySql, sqlx::mysql::MySqlArguments> {
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
pub extern "C" fn auto_generated_for_struct_mysql_Sqlx4kMysqlPtr(_: Sqlx4kMysqlPtr) {}
#[no_mangle]
pub extern "C" fn auto_generated_for_struct_mysql_Sqlx4kMysqlResult(_: Sqlx4kMysqlResult) {}
#[no_mangle]
pub extern "C" fn auto_generated_for_struct_mysql_Sqlx4kMysqlParam(_: Sqlx4kMysqlParam) {}

#[no_mangle]
pub extern "C" fn sqlx4k_mysql_free_result(ptr: *mut Sqlx4kMysqlResult) {
    let ptr: Sqlx4kMysqlResult = unsafe { *Box::from_raw(ptr) };

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

    let schema: Sqlx4kMysqlSchema = unsafe { *Box::from_raw(ptr.schema) };
    let columns: Vec<Sqlx4kMysqlSchemaColumn> =
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

    let rows: Vec<Sqlx4kMysqlRow> =
        unsafe { Vec::from_raw_parts(ptr.rows, ptr.size as usize, ptr.size as usize) };
    for row in rows {
        let columns: Vec<Sqlx4kMysqlColumn> =
            unsafe { Vec::from_raw_parts(row.columns, row.size as usize, row.size as usize) };
        for col in columns {
            if col.value != null_mut() {
                let value = unsafe { CString::from_raw(col.value) };
                std::mem::drop(value);
            }
        }
    }
}

pub fn sqlx4k_mysql_error_result_of(err: sqlx::Error) -> Sqlx4kMysqlResult {
    // A database error also carries what the server reported: the SQLSTATE, the MySQL error number
    // and a portable kind (unique violation, foreign key violation, ...), surfaced on `SQLError`.
    let (sql_state, native_code, kind): (Option<String>, c_int, c_int) = match &err {
        Error::Database(e) => {
            let sql_state = e.code().map(|c| c.to_string());
            let number = e
                .try_downcast_ref::<MySqlDatabaseError>()
                .map(|e| e.number());
            let kind = sqlx4k_mysql_kind_of(e.kind(), number);
            (
                sql_state,
                number.map(c_int::from).unwrap_or(NO_NATIVE_CODE),
                kind,
            )
        }
        _ => (None, NO_NATIVE_CODE, KIND_OTHER),
    };

    let (code, message) = match err {
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
        // Any other error (Io, Tls, Protocol, Decode, ...) is reported as a database error.
        // Never panic here: with `panic = "abort"`, a dropped connection would terminate the process.
        e => (ERROR_DATABASE, e.to_string()),
    };

    // CString::new fails on interior NUL bytes, which may appear in values echoed back by the server.
    let message = CString::new(message.replace('\0', "")).unwrap();
    let sql_state = sql_state
        .map(|s| CString::new(s.replace('\0', "")).unwrap().into_raw())
        .unwrap_or(null_mut());
    Sqlx4kMysqlResult {
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
fn sqlx4k_mysql_constraint_kind_of(kind: ErrorKind) -> Option<c_int> {
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

/// Classifies a MySQL error as a `SQLError.Kind` ordinal: constraint violations come from sqlx,
/// concurrency failures from the error number. Mirrors `mysqlKindOf` in the JVM driver.
/// https://dev.mysql.com/doc/mysql-errors/8.0/en/server-error-reference.html
fn sqlx4k_mysql_kind_of(kind: ErrorKind, number: Option<u16>) -> c_int {
    if let Some(kind) = sqlx4k_mysql_constraint_kind_of(kind) {
        return kind;
    }
    match number {
        // ER_LOCK_DEADLOCK
        Some(1213) => KIND_DEADLOCK,
        // ER_LOCK_WAIT_TIMEOUT
        Some(1205) => KIND_LOCK_TIMEOUT,
        _ => KIND_OTHER,
    }
}

pub fn c_chars_to_str_mysql<'a>(c_chars: *const c_char) -> &'a str {
    unsafe { CStr::from_ptr(c_chars).to_str().unwrap() }
}

// ============================================================================
// MySQL-specific implementation
// ============================================================================

// ============================================================================
// Streaming (fetch)
// ============================================================================

/// Rows are moved to Kotlin in chunks of the requested fetch size; the channel holds at most this
/// many ready chunks, which is the backpressure that keeps a slow consumer from buffering a large
/// result on the Rust side.
const STREAM_CHANNEL_CAPACITY: usize = 2;
const STREAM_DEFAULT_FETCH_SIZE: usize = 1_000;

type StreamChunk = Result<Vec<MySqlRow>, sqlx::Error>;
type RowStream<'e> = Pin<Box<dyn Stream<Item = Result<MySqlRow, sqlx::Error>> + Send + 'e>>;

/// A result set being streamed to Kotlin, row chunk by row chunk.
///
/// sqlx streams rows straight off the wire (no server-side cursor), and the stream borrows the
/// connection it runs on, so both live in a spawned task that owns them and sends the chunks through
/// `rx`. `sqlx4k_mysql_stream_next` receives one chunk; `sqlx4k_mysql_stream_close` stops the task
/// (dropping `cancel`) and joins it before freeing the handle, so the connection or transaction the
/// task was borrowing is no longer in use once Kotlin continues.
struct Sqlx4kMysqlStream {
    rx: tokio::sync::Mutex<mpsc::Receiver<StreamChunk>>,
    cancel: oneshot::Sender<()>,
    task: JoinHandle<()>,
}

/// Opens the row stream of a query on the given connection: the text protocol for a plain query
/// (as `fetch_all` does), a prepared statement when there are parameters to bind.
fn stream_rows_of<'e>(
    cn: &'e mut MySqlConnection,
    sql: String,
    params: Vec<OwnedParam>,
) -> RowStream<'e> {
    if params.is_empty() {
        cn.fetch(AssertSqlSafe(sql))
    } else {
        bind_params(sqlx::query::<MySql>(AssertSqlSafe(sql)), params).fetch(cn)
    }
}

/// Drives a row stream, sending chunks of `fetch_size` rows until the stream ends or fails, or the
/// consumer closes it (`cancel` fires, or the channel is dropped). Returns whether the stream was
/// consumed to its end (an error counts as the end: the connection is in a clean state again).
async fn stream_rows(
    mut rows: RowStream<'_>,
    fetch_size: usize,
    tx: mpsc::Sender<StreamChunk>,
    mut cancel: oneshot::Receiver<()>,
) -> bool {
    let mut chunk: Vec<MySqlRow> = Vec::with_capacity(fetch_size);
    loop {
        let next = tokio::select! {
            _ = &mut cancel => return false,
            next = rows.try_next() => next,
        };
        match next {
            Ok(Some(row)) => {
                chunk.push(row);
                if chunk.len() >= fetch_size {
                    let full = std::mem::replace(&mut chunk, Vec::with_capacity(fetch_size));
                    tokio::select! {
                        _ = &mut cancel => return false,
                        sent = tx.send(Ok(full)) => if sent.is_err() { return false },
                    }
                }
            }
            Ok(None) => {
                if !chunk.is_empty() {
                    let _ = tx.send(Ok(chunk)).await;
                }
                return true;
            }
            Err(err) => {
                let _ = tx.send(Err(err)).await;
                return true;
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

fn stream_result_of(stream: Sqlx4kMysqlStream) -> *mut Sqlx4kMysqlResult {
    Sqlx4kMysqlResult {
        stream: Box::into_raw(Box::new(stream)) as *mut c_void,
        ..Default::default()
    }
    .leak()
}

static RUNTIME: OnceLock<Runtime> = OnceLock::new();

#[derive(Debug)]
struct Sqlx4kMySql {
    pool: MySqlPool,
}

impl Sqlx4kMySql {
    async fn query(&self, sql: String) -> *mut Sqlx4kMysqlResult {
        let result = self.pool.execute(AssertSqlSafe(sql)).await;
        let result = match result {
            Ok(res) => Sqlx4kMysqlResult {
                rows_affected: res.rows_affected(),
                ..Default::default()
            },
            Err(err) => sqlx4k_mysql_error_result_of(err),
        };
        result.leak()
    }

    async fn fetch_all(&self, sql: String) -> *mut Sqlx4kMysqlResult {
        let result = self.pool.fetch_all(AssertSqlSafe(sql)).await;
        sqlx4k_mysql_result_of(result).leak()
    }

    async fn cn_acquire(&self) -> *mut Sqlx4kMysqlResult {
        let cn = self.pool.acquire().await;
        let cn: PoolConnection<MySql> = match cn {
            Ok(cn) => cn,
            Err(err) => return sqlx4k_mysql_error_result_of(err).leak(),
        };

        let cn = Box::new(cn);
        let cn = Box::leak(cn);
        let result = Sqlx4kMysqlResult {
            cn: cn as *mut _ as *mut c_void,
            ..Default::default()
        };
        result.leak()
    }

    async fn cn_release(&self, cn: Sqlx4kMysqlPtr) -> *mut Sqlx4kMysqlResult {
        if cn.ptr.is_null() {
            return Sqlx4kMysqlResult::default().leak();
        }

        let cn_ptr = cn.ptr as *mut PoolConnection<MySql>;
        unsafe {
            // Recreate the Box and drop it, returning connection to pool
            let _boxed: Box<PoolConnection<MySql>> = Box::from_raw(cn_ptr);
        }

        Sqlx4kMysqlResult::default().leak()
    }

    async fn cn_query(&self, cn: Sqlx4kMysqlPtr, sql: String) -> *mut Sqlx4kMysqlResult {
        let cn = unsafe { &mut *(cn.ptr as *mut PoolConnection<MySql>) };
        let result = cn.execute(AssertSqlSafe(sql)).await;
        let result = match result {
            Ok(res) => Sqlx4kMysqlResult {
                rows_affected: res.rows_affected(),
                ..Default::default()
            },
            Err(err) => sqlx4k_mysql_error_result_of(err),
        };
        result.leak()
    }

    async fn cn_fetch_all(&self, cn: Sqlx4kMysqlPtr, sql: String) -> *mut Sqlx4kMysqlResult {
        let cn = unsafe { &mut *(cn.ptr as *mut PoolConnection<MySql>) };
        let result = cn.fetch_all(AssertSqlSafe(sql)).await;
        sqlx4k_mysql_result_of(result).leak()
    }

    async fn cn_tx_begin(&self, cn: Sqlx4kMysqlPtr) -> *mut Sqlx4kMysqlResult {
        let cn = unsafe { &mut *(cn.ptr as *mut PoolConnection<MySql>) };
        let tx = cn.begin().await;
        let tx = match tx {
            Ok(tx) => tx,
            Err(err) => {
                return sqlx4k_mysql_error_result_of(err).leak();
            }
        };

        let tx = Box::new(tx);
        let tx = Box::leak(tx);

        let result = Sqlx4kMysqlResult {
            tx: tx as *mut _ as *mut c_void,
            ..Default::default()
        };
        result.leak()
    }

    async fn tx_begin(&self) -> *mut Sqlx4kMysqlResult {
        let tx = self.pool.begin().await;
        let tx = match tx {
            Ok(tx) => tx,
            Err(err) => {
                return sqlx4k_mysql_error_result_of(err).leak();
            }
        };

        let tx = Box::new(tx);
        let tx = Box::leak(tx);
        let result = Sqlx4kMysqlResult {
            tx: tx as *mut _ as *mut c_void,
            ..Default::default()
        };
        result.leak()
    }

    async fn tx_commit(&self, tx: Sqlx4kMysqlPtr) -> *mut Sqlx4kMysqlResult {
        let tx = unsafe { *Box::from_raw(tx.ptr as *mut Transaction<'_, MySql>) };
        let result = match tx.commit().await {
            Ok(_) => Sqlx4kMysqlResult::default(),
            Err(err) => sqlx4k_mysql_error_result_of(err),
        };
        result.leak()
    }

    async fn tx_rollback(&self, tx: Sqlx4kMysqlPtr) -> *mut Sqlx4kMysqlResult {
        let tx = unsafe { *Box::from_raw(tx.ptr as *mut Transaction<'_, MySql>) };
        let result = match tx.rollback().await {
            Ok(_) => Sqlx4kMysqlResult::default(),
            Err(err) => sqlx4k_mysql_error_result_of(err),
        };
        result.leak()
    }

    async fn tx_query(&self, tx: Sqlx4kMysqlPtr, sql: String) -> *mut Sqlx4kMysqlResult {
        let mut tx = unsafe { *Box::from_raw(tx.ptr as *mut Transaction<'_, MySql>) };
        let result = tx.execute(AssertSqlSafe(sql)).await;
        let tx = Box::new(tx);
        let tx = Box::into_raw(tx);
        let result = match result {
            Ok(res) => Sqlx4kMysqlResult {
                rows_affected: res.rows_affected(),
                ..Default::default()
            },
            Err(err) => sqlx4k_mysql_error_result_of(err),
        };
        let result = Sqlx4kMysqlResult {
            tx: tx as *mut c_void,
            ..result
        };
        result.leak()
    }

    async fn tx_fetch_all(&self, tx: Sqlx4kMysqlPtr, sql: String) -> *mut Sqlx4kMysqlResult {
        let mut tx = unsafe { *Box::from_raw(tx.ptr as *mut Transaction<'_, MySql>) };
        let result = tx.fetch_all(AssertSqlSafe(sql)).await;
        let tx = Box::new(tx);
        let tx = Box::into_raw(tx);
        let result = sqlx4k_mysql_result_of(result);
        let result = Sqlx4kMysqlResult {
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
    ) -> *mut Sqlx4kMysqlResult {
        let mut cn: PoolConnection<MySql> = match self.pool.acquire().await {
            Ok(cn) => cn,
            Err(err) => return sqlx4k_mysql_error_result_of(err).leak(),
        };
        let (tx, rx) = mpsc::channel(STREAM_CHANNEL_CAPACITY);
        let (cancel, cancel_rx) = oneshot::channel();
        let task = RUNTIME.get().unwrap().spawn(async move {
            let exhausted = {
                let rows = stream_rows_of(&mut cn, sql, params);
                stream_rows(rows, fetch_size, tx, cancel_rx).await
            };
            if !exhausted {
                // Cancelled with rows pending: sqlx would read and discard the remainder on the next
                // use of this connection, which for a large result costs far more than a reconnect,
                // so the connection is closed instead of being returned to the pool.
                let _ = cn.detach().close().await;
            }
        });
        stream_result_of(Sqlx4kMysqlStream {
            rx: tokio::sync::Mutex::new(rx),
            cancel,
            task,
        })
    }

    async fn cn_stream_open(
        &self,
        cn: Sqlx4kMysqlPtr,
        sql: String,
        params: Vec<OwnedParam>,
        fetch_size: usize,
    ) -> *mut Sqlx4kMysqlResult {
        let (tx, rx) = mpsc::channel(STREAM_CHANNEL_CAPACITY);
        let (cancel, cancel_rx) = oneshot::channel();
        let task = RUNTIME.get().unwrap().spawn(async move {
            // The connection stays with Kotlin, which serializes its use and closes this stream
            // before any other statement; a cancelled stream leaves sqlx to drain the remaining
            // rows on the connection's next use.
            let cn = unsafe { &mut *(cn.raw() as *mut PoolConnection<MySql>) };
            let rows = stream_rows_of(&mut **cn, sql, params);
            stream_rows(rows, fetch_size, tx, cancel_rx).await;
        });
        stream_result_of(Sqlx4kMysqlStream {
            rx: tokio::sync::Mutex::new(rx),
            cancel,
            task,
        })
    }

    async fn tx_stream_open(
        &self,
        tx: Sqlx4kMysqlPtr,
        sql: String,
        params: Vec<OwnedParam>,
        fetch_size: usize,
    ) -> *mut Sqlx4kMysqlResult {
        let (chunks, rx) = mpsc::channel(STREAM_CHANNEL_CAPACITY);
        let (cancel, cancel_rx) = oneshot::channel();
        let task = RUNTIME.get().unwrap().spawn(async move {
            // Borrowed in place (not re-boxed like `tx_fetch_all`), so the Kotlin-held pointer stays valid.
            let tx = unsafe { &mut *(tx.raw() as *mut Transaction<'static, MySql>) };
            let rows = stream_rows_of(&mut **tx, sql, params);
            stream_rows(rows, fetch_size, chunks, cancel_rx).await;
        });
        stream_result_of(Sqlx4kMysqlStream {
            rx: tokio::sync::Mutex::new(rx),
            cancel,
            task,
        })
    }

    async fn stream_next(&self, stream: Sqlx4kMysqlPtr) -> *mut Sqlx4kMysqlResult {
        let stream = unsafe { &*(stream.ptr as *const Sqlx4kMysqlStream) };
        let mut rx = stream.rx.lock().await;
        match rx.recv().await {
            Some(chunk) => sqlx4k_mysql_result_of(chunk).leak(),
            // End of the stream: an empty result (size 0, no schema).
            None => Sqlx4kMysqlResult::default().leak(),
        }
    }

    async fn stream_close(&self, stream: Sqlx4kMysqlPtr) -> *mut Sqlx4kMysqlResult {
        let stream = unsafe { Box::from_raw(stream.ptr as *mut Sqlx4kMysqlStream) };
        let Sqlx4kMysqlStream { rx, cancel, task } = *stream;
        // Stop the producer (dropping the sender fires its cancel branch, dropping the receiver fails
        // its next send) and wait for it: only then is the connection it borrowed free again.
        drop(cancel);
        drop(rx);
        let _ = task.await;
        Sqlx4kMysqlResult::default().leak()
    }

    async fn close(&self) -> *mut Sqlx4kMysqlResult {
        self.pool.close().await;
        Sqlx4kMysqlResult::default().leak()
    }

    async fn query_with_params(
        &self,
        sql: String,
        params: Vec<OwnedParam>,
    ) -> *mut Sqlx4kMysqlResult {
        let q = bind_params(sqlx::query::<MySql>(AssertSqlSafe(sql)), params);
        let result = q.execute(&self.pool).await;
        let result = match result {
            Ok(res) => Sqlx4kMysqlResult {
                rows_affected: res.rows_affected(),
                ..Default::default()
            },
            Err(err) => sqlx4k_mysql_error_result_of(err),
        };
        result.leak()
    }

    async fn fetch_all_with_params(
        &self,
        sql: String,
        params: Vec<OwnedParam>,
    ) -> *mut Sqlx4kMysqlResult {
        let q = bind_params(sqlx::query::<MySql>(AssertSqlSafe(sql)), params);
        let result = q.fetch_all(&self.pool).await;
        sqlx4k_mysql_result_of(result).leak()
    }

    async fn cn_query_with_params(
        &self,
        cn: Sqlx4kMysqlPtr,
        sql: String,
        params: Vec<OwnedParam>,
    ) -> *mut Sqlx4kMysqlResult {
        let cn = unsafe { &mut *(cn.ptr as *mut PoolConnection<MySql>) };
        let q = bind_params(sqlx::query::<MySql>(AssertSqlSafe(sql)), params);
        let result = q.execute(&mut **cn).await;
        let result = match result {
            Ok(res) => Sqlx4kMysqlResult {
                rows_affected: res.rows_affected(),
                ..Default::default()
            },
            Err(err) => sqlx4k_mysql_error_result_of(err),
        };
        result.leak()
    }

    async fn cn_fetch_all_with_params(
        &self,
        cn: Sqlx4kMysqlPtr,
        sql: String,
        params: Vec<OwnedParam>,
    ) -> *mut Sqlx4kMysqlResult {
        let cn = unsafe { &mut *(cn.ptr as *mut PoolConnection<MySql>) };
        let q = bind_params(sqlx::query::<MySql>(AssertSqlSafe(sql)), params);
        let result = q.fetch_all(&mut **cn).await;
        sqlx4k_mysql_result_of(result).leak()
    }

    async fn tx_query_with_params(
        &self,
        tx: Sqlx4kMysqlPtr,
        sql: String,
        params: Vec<OwnedParam>,
    ) -> *mut Sqlx4kMysqlResult {
        let mut tx = unsafe { *Box::from_raw(tx.ptr as *mut Transaction<'_, MySql>) };
        let q = bind_params(sqlx::query::<MySql>(AssertSqlSafe(sql)), params);
        let result = q.execute(&mut *tx).await;
        let tx = Box::new(tx);
        let tx = Box::into_raw(tx);
        let result = match result {
            Ok(res) => Sqlx4kMysqlResult {
                rows_affected: res.rows_affected(),
                ..Default::default()
            },
            Err(err) => sqlx4k_mysql_error_result_of(err),
        };
        let result = Sqlx4kMysqlResult {
            tx: tx as *mut c_void,
            ..result
        };
        result.leak()
    }

    async fn tx_fetch_all_with_params(
        &self,
        tx: Sqlx4kMysqlPtr,
        sql: String,
        params: Vec<OwnedParam>,
    ) -> *mut Sqlx4kMysqlResult {
        let mut tx = unsafe { *Box::from_raw(tx.ptr as *mut Transaction<'_, MySql>) };
        let q = bind_params(sqlx::query::<MySql>(AssertSqlSafe(sql)), params);
        let result = q.fetch_all(&mut *tx).await;
        let tx = Box::new(tx);
        let tx = Box::into_raw(tx);
        let result = sqlx4k_mysql_result_of(result);
        let result = Sqlx4kMysqlResult {
            tx: tx as *mut c_void,
            ..result
        };
        result.leak()
    }
}

#[no_mangle]
pub extern "C" fn sqlx4k_mysql_of(
    url: *const c_char,
    username: *const c_char,
    password: *const c_char,
    min_connections: c_int,
    max_connections: c_int,
    acquire_timeout_milis: c_int,
    idle_timeout_milis: c_int,
    max_lifetime_milis: c_int,
) -> *mut Sqlx4kMysqlResult {
    let url = c_chars_to_str_mysql(url);
    let username = c_chars_to_str_mysql(username);
    let password = c_chars_to_str_mysql(password);
    let options: MySqlConnectOptions = url.parse().unwrap();
    let options = options.username(username).password(password);

    // Create the tokio runtime.
    let runtime = if RUNTIME.get().is_some() {
        RUNTIME.get().unwrap()
    } else {
        let rt = Runtime::new().unwrap();
        RUNTIME.set(rt).unwrap();
        RUNTIME.get().unwrap()
    };

    // Create the db pool options.
    let pool = MySqlPoolOptions::new().max_connections(max_connections as u32);

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
    let pool: MySqlPool = match pool {
        Ok(pool) => pool,
        Err(err) => return sqlx4k_mysql_error_result_of(err).leak(),
    };
    let sqlx4k = Sqlx4kMySql { pool };
    let sqlx4k = Box::new(sqlx4k);
    let sqlx4k = Box::leak(sqlx4k);

    Sqlx4kMysqlResult {
        rt: sqlx4k as *mut _ as *mut c_void,
        ..Default::default()
    }
    .leak()
}

#[no_mangle]
pub extern "C" fn sqlx4k_mysql_pool_size(rt: *mut c_void) -> c_int {
    let sqlx4k = unsafe { &*(rt as *mut Sqlx4kMySql) };
    sqlx4k.pool.size() as c_int
}

#[no_mangle]
pub extern "C" fn sqlx4k_mysql_pool_idle_size(rt: *mut c_void) -> c_int {
    let sqlx4k = unsafe { &*(rt as *mut Sqlx4kMySql) };
    sqlx4k.pool.num_idle() as c_int
}

#[no_mangle]
pub extern "C" fn sqlx4k_mysql_close(
    rt: *mut c_void,
    callback: *mut c_void,
    fun: extern "C" fn(Sqlx4kMysqlPtr, *mut Sqlx4kMysqlResult),
) {
    let callback = Sqlx4kMysqlPtr { ptr: callback };
    let runtime = RUNTIME.get().unwrap();
    let sqlx4k = unsafe { &*(rt as *mut Sqlx4kMySql) };
    runtime.spawn(async move {
        let result = sqlx4k.close().await;
        fun(callback, result)
    });
}

#[no_mangle]
pub extern "C" fn sqlx4k_mysql_query(
    rt: *mut c_void,
    sql: *const c_char,
    callback: *mut c_void,
    fun: extern "C" fn(Sqlx4kMysqlPtr, *mut Sqlx4kMysqlResult),
) {
    let callback = Sqlx4kMysqlPtr { ptr: callback };
    let sql = c_chars_to_str_mysql(sql).to_owned();
    let runtime = RUNTIME.get().unwrap();
    let sqlx4k = unsafe { &*(rt as *mut Sqlx4kMySql) };
    runtime.spawn(async move {
        let result = sqlx4k.query(sql).await;
        fun(callback, result)
    });
}

#[no_mangle]
pub extern "C" fn sqlx4k_mysql_fetch_all(
    rt: *mut c_void,
    sql: *const c_char,
    callback: *mut c_void,
    fun: extern "C" fn(Sqlx4kMysqlPtr, *mut Sqlx4kMysqlResult),
) {
    let callback = Sqlx4kMysqlPtr { ptr: callback };
    let sql = c_chars_to_str_mysql(sql).to_owned();
    let runtime = RUNTIME.get().unwrap();
    let sqlx4k = unsafe { &*(rt as *mut Sqlx4kMySql) };
    runtime.spawn(async move {
        let result = sqlx4k.fetch_all(sql).await;
        fun(callback, result)
    });
}

#[no_mangle]
pub extern "C" fn sqlx4k_mysql_stream_open(
    rt: *mut c_void,
    sql: *const c_char,
    params: *const Sqlx4kMysqlParam,
    params_len: c_int,
    fetch_size: c_int,
    callback: *mut c_void,
    fun: extern "C" fn(Sqlx4kMysqlPtr, *mut Sqlx4kMysqlResult),
) {
    let callback = Sqlx4kMysqlPtr { ptr: callback };
    let sql = c_chars_to_str_mysql(sql).to_owned();
    let owned = read_params(params, params_len);
    let fetch_size = stream_fetch_size(fetch_size);
    let runtime = RUNTIME.get().unwrap();
    let sqlx4k = unsafe { &*(rt as *mut Sqlx4kMySql) };
    runtime.spawn(async move {
        let result = sqlx4k.stream_open(sql, owned, fetch_size).await;
        fun(callback, result)
    });
}

#[no_mangle]
pub extern "C" fn sqlx4k_mysql_cn_stream_open(
    rt: *mut c_void,
    cn: *mut c_void,
    sql: *const c_char,
    params: *const Sqlx4kMysqlParam,
    params_len: c_int,
    fetch_size: c_int,
    callback: *mut c_void,
    fun: extern "C" fn(Sqlx4kMysqlPtr, *mut Sqlx4kMysqlResult),
) {
    let callback = Sqlx4kMysqlPtr { ptr: callback };
    let cn = Sqlx4kMysqlPtr { ptr: cn };
    let sql = c_chars_to_str_mysql(sql).to_owned();
    let owned = read_params(params, params_len);
    let fetch_size = stream_fetch_size(fetch_size);
    let runtime = RUNTIME.get().unwrap();
    let sqlx4k = unsafe { &*(rt as *mut Sqlx4kMySql) };
    runtime.spawn(async move {
        let result = sqlx4k.cn_stream_open(cn, sql, owned, fetch_size).await;
        fun(callback, result)
    });
}

#[no_mangle]
pub extern "C" fn sqlx4k_mysql_tx_stream_open(
    rt: *mut c_void,
    tx: *mut c_void,
    sql: *const c_char,
    params: *const Sqlx4kMysqlParam,
    params_len: c_int,
    fetch_size: c_int,
    callback: *mut c_void,
    fun: extern "C" fn(Sqlx4kMysqlPtr, *mut Sqlx4kMysqlResult),
) {
    let callback = Sqlx4kMysqlPtr { ptr: callback };
    let tx = Sqlx4kMysqlPtr { ptr: tx };
    let sql = c_chars_to_str_mysql(sql).to_owned();
    let owned = read_params(params, params_len);
    let fetch_size = stream_fetch_size(fetch_size);
    let runtime = RUNTIME.get().unwrap();
    let sqlx4k = unsafe { &*(rt as *mut Sqlx4kMySql) };
    runtime.spawn(async move {
        let result = sqlx4k.tx_stream_open(tx, sql, owned, fetch_size).await;
        fun(callback, result)
    });
}

#[no_mangle]
pub extern "C" fn sqlx4k_mysql_stream_next(
    rt: *mut c_void,
    stream: *mut c_void,
    callback: *mut c_void,
    fun: extern "C" fn(Sqlx4kMysqlPtr, *mut Sqlx4kMysqlResult),
) {
    let callback = Sqlx4kMysqlPtr { ptr: callback };
    let stream = Sqlx4kMysqlPtr { ptr: stream };
    let runtime = RUNTIME.get().unwrap();
    let sqlx4k = unsafe { &*(rt as *mut Sqlx4kMySql) };
    runtime.spawn(async move {
        let result = sqlx4k.stream_next(stream).await;
        fun(callback, result)
    });
}

#[no_mangle]
pub extern "C" fn sqlx4k_mysql_stream_close(
    rt: *mut c_void,
    stream: *mut c_void,
    callback: *mut c_void,
    fun: extern "C" fn(Sqlx4kMysqlPtr, *mut Sqlx4kMysqlResult),
) {
    let callback = Sqlx4kMysqlPtr { ptr: callback };
    let stream = Sqlx4kMysqlPtr { ptr: stream };
    let runtime = RUNTIME.get().unwrap();
    let sqlx4k = unsafe { &*(rt as *mut Sqlx4kMySql) };
    runtime.spawn(async move {
        let result = sqlx4k.stream_close(stream).await;
        fun(callback, result)
    });
}

#[no_mangle]
pub extern "C" fn sqlx4k_mysql_cn_acquire(
    rt: *mut c_void,
    callback: *mut c_void,
    fun: extern "C" fn(Sqlx4kMysqlPtr, *mut Sqlx4kMysqlResult),
) {
    let callback = Sqlx4kMysqlPtr { ptr: callback };
    let runtime = RUNTIME.get().unwrap();
    let sqlx4k = unsafe { &*(rt as *mut Sqlx4kMySql) };
    runtime.spawn(async move {
        let result = sqlx4k.cn_acquire().await;
        fun(callback, result)
    });
}

#[no_mangle]
pub extern "C" fn sqlx4k_mysql_cn_release(
    rt: *mut c_void,
    cn: *mut c_void,
    callback: *mut c_void,
    fun: extern "C" fn(Sqlx4kMysqlPtr, *mut Sqlx4kMysqlResult),
) {
    let cn = Sqlx4kMysqlPtr { ptr: cn };
    let callback = Sqlx4kMysqlPtr { ptr: callback };
    let runtime = RUNTIME.get().unwrap();
    let sqlx4k = unsafe { &*(rt as *mut Sqlx4kMySql) };
    runtime.spawn(async move {
        let result = sqlx4k.cn_release(cn).await;
        fun(callback, result)
    });
}

#[no_mangle]
pub extern "C" fn sqlx4k_mysql_cn_query(
    rt: *mut c_void,
    cn: *mut c_void,
    sql: *const c_char,
    callback: *mut c_void,
    fun: extern "C" fn(Sqlx4kMysqlPtr, *mut Sqlx4kMysqlResult),
) {
    let cn = Sqlx4kMysqlPtr { ptr: cn };
    let callback = Sqlx4kMysqlPtr { ptr: callback };
    let sql = c_chars_to_str_mysql(sql).to_owned();
    let runtime = RUNTIME.get().unwrap();
    let sqlx4k = unsafe { &*(rt as *mut Sqlx4kMySql) };
    runtime.spawn(async move {
        let result = sqlx4k.cn_query(cn, sql).await;
        fun(callback, result)
    });
}

#[no_mangle]
pub extern "C" fn sqlx4k_mysql_cn_fetch_all(
    rt: *mut c_void,
    cn: *mut c_void,
    sql: *const c_char,
    callback: *mut c_void,
    fun: extern "C" fn(Sqlx4kMysqlPtr, *mut Sqlx4kMysqlResult),
) {
    let cn = Sqlx4kMysqlPtr { ptr: cn };
    let callback = Sqlx4kMysqlPtr { ptr: callback };
    let sql = c_chars_to_str_mysql(sql).to_owned();
    let runtime = RUNTIME.get().unwrap();
    let sqlx4k = unsafe { &*(rt as *mut Sqlx4kMySql) };
    runtime.spawn(async move {
        let result = sqlx4k.cn_fetch_all(cn, sql).await;
        fun(callback, result)
    });
}

#[no_mangle]
pub extern "C" fn sqlx4k_mysql_cn_tx_begin(
    rt: *mut c_void,
    cn: *mut c_void,
    callback: *mut c_void,
    fun: extern "C" fn(Sqlx4kMysqlPtr, *mut Sqlx4kMysqlResult),
) {
    let cn = Sqlx4kMysqlPtr { ptr: cn };
    let callback = Sqlx4kMysqlPtr { ptr: callback };
    let runtime = RUNTIME.get().unwrap();
    let sqlx4k = unsafe { &*(rt as *mut Sqlx4kMySql) };
    runtime.spawn(async move {
        let result = sqlx4k.cn_tx_begin(cn).await;
        fun(callback, result)
    });
}

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

fn sqlx4k_mysql_result_of(result: Result<Vec<MySqlRow>, sqlx::Error>) -> Sqlx4kMysqlResult {
    match result {
        Ok(rows) => {
            let schema: Sqlx4kMysqlSchema = if rows.len() > 0 {
                sqlx4k_mysql_schema_of(rows.get(0).unwrap())
            } else {
                Sqlx4kMysqlSchema::default()
            };

            let schema = Box::new(schema);
            let schema = Box::leak(schema);

            let rows: Vec<Sqlx4kMysqlRow> = rows.iter().map(|r| sqlx4k_mysql_row_of(r)).collect();
            let size = rows.len();
            let rows: Box<[Sqlx4kMysqlRow]> = rows.into_boxed_slice();
            let rows: &mut [Sqlx4kMysqlRow] = Box::leak(rows);
            let rows: *mut Sqlx4kMysqlRow = rows.as_mut_ptr();

            Sqlx4kMysqlResult {
                schema,
                size: size as c_int,
                rows,
                ..Default::default()
            }
        }
        Err(err) => sqlx4k_mysql_error_result_of(err),
    }
}

fn sqlx4k_mysql_schema_of(row: &MySqlRow) -> Sqlx4kMysqlSchema {
    let columns = row.columns();
    if columns.is_empty() {
        Sqlx4kMysqlSchema::default()
    } else {
        let columns: Vec<Sqlx4kMysqlSchemaColumn> = row
            .columns()
            .iter()
            .map(|c| {
                let name: &str = c.name();
                let value_ref: MySqlValueRef = row.try_get_raw(c.ordinal()).unwrap();
                let info: std::borrow::Cow<MySqlTypeInfo> = value_ref.type_info();
                let kind: &str = info.name();
                Sqlx4kMysqlSchemaColumn {
                    ordinal: c.ordinal() as c_int,
                    name: CString::new(name).unwrap().into_raw(),
                    kind: CString::new(kind).unwrap().into_raw(),
                }
            })
            .collect();

        let size = columns.len();
        let columns: Box<[Sqlx4kMysqlSchemaColumn]> = columns.into_boxed_slice();
        let columns: &mut [Sqlx4kMysqlSchemaColumn] = Box::leak(columns);
        let columns: *mut Sqlx4kMysqlSchemaColumn = columns.as_mut_ptr();

        Sqlx4kMysqlSchema {
            size: size as c_int,
            columns,
        }
    }
}

/// Decodes a single MySQL column value into a heap-allocated C string.
///
/// `row.get_unchecked::<&str>` only works reliably when the result is in the
/// text protocol used by `pool.execute(sql)`. The `_with_params` path uses
/// MySQL's binary protocol, where INT/BIGINT/FLOAT/DOUBLE/temporal/binary
/// values come back as raw bytes — `from_utf8` happens to succeed on integer
/// payloads but the resulting string contains embedded NULs that crash
/// `CString::new`. Decode by type so both protocols round-trip identically.
fn decode_mysql_column_value(row: &MySqlRow, ordinal: usize) -> *mut c_char {
    let type_name: String = {
        let value_ref = row.try_get_raw(ordinal).unwrap();
        if value_ref.is_null() {
            return null_mut();
        }
        value_ref.type_info().name().to_string()
    };

    let s: String = match type_name.as_str() {
        // sqlx-mysql reports `TINYINT(1)` (what `BOOLEAN`/`BOOL` creates) as
        // "BOOLEAN" with `display = 1` rather than the bare "TINYINT" name.
        "BOOLEAN" => row.get_unchecked::<i8, _>(ordinal).to_string(),
        "TINYINT" => row.get_unchecked::<i8, _>(ordinal).to_string(),
        "SMALLINT" => row.get_unchecked::<i16, _>(ordinal).to_string(),
        "MEDIUMINT" | "INT" => row.get_unchecked::<i32, _>(ordinal).to_string(),
        "BIGINT" => row.get_unchecked::<i64, _>(ordinal).to_string(),
        "TINYINT UNSIGNED" => row.get_unchecked::<u8, _>(ordinal).to_string(),
        "SMALLINT UNSIGNED" => row.get_unchecked::<u16, _>(ordinal).to_string(),
        "MEDIUMINT UNSIGNED" | "INT UNSIGNED" => row.get_unchecked::<u32, _>(ordinal).to_string(),
        "BIGINT UNSIGNED" => row.get_unchecked::<u64, _>(ordinal).to_string(),
        "FLOAT" => row.get_unchecked::<f32, _>(ordinal).to_string(),
        "DOUBLE" => row.get_unchecked::<f64, _>(ordinal).to_string(),
        "BLOB" | "TINYBLOB" | "MEDIUMBLOB" | "LONGBLOB" | "VARBINARY" | "BINARY" => {
            let bytes: Vec<u8> = row.get_unchecked(ordinal);
            hex::encode(bytes)
        }
        // Temporal types arrive as packed binary in the prepared-statement
        // protocol; decode via chrono and format to `yyyy-MM-dd HH:mm:ss[.uuuuuu]`
        // so the existing asInstant / asLocalDate* decoders round-trip.
        "DATE" => row
            .get_unchecked::<chrono::NaiveDate, _>(ordinal)
            .to_string(),
        "TIME" => row
            .get_unchecked::<chrono::NaiveTime, _>(ordinal)
            .to_string(),
        "DATETIME" | "TIMESTAMP" => {
            let dt: chrono::NaiveDateTime = row.get_unchecked(ordinal);
            dt.format("%Y-%m-%d %H:%M:%S%.6f").to_string()
        }
        // VARCHAR / CHAR / TEXT (all varieties) / JSON / ENUM / SET / YEAR / BIT
        // and anything else fall through to `&str`. In the text protocol they
        // arrive as text bytes; for text-typed columns this also works in the
        // binary protocol.
        _ => row.get_unchecked::<&str, _>(ordinal).to_string(),
    };
    CString::new(s).unwrap().into_raw()
}

fn sqlx4k_mysql_row_of(row: &MySqlRow) -> Sqlx4kMysqlRow {
    let columns = row.columns();
    if columns.is_empty() {
        Sqlx4kMysqlRow::default()
    } else {
        let columns: Vec<Sqlx4kMysqlColumn> = row
            .columns()
            .iter()
            .map(|c| Sqlx4kMysqlColumn {
                ordinal: c.ordinal() as c_int,
                value: decode_mysql_column_value(row, c.ordinal()),
            })
            .collect();

        let size = columns.len();
        let columns: Box<[Sqlx4kMysqlColumn]> = columns.into_boxed_slice();
        let columns: &mut [Sqlx4kMysqlColumn] = Box::leak(columns);
        let columns: *mut Sqlx4kMysqlColumn = columns.as_mut_ptr();

        Sqlx4kMysqlRow {
            size: size as c_int,
            columns,
        }
    }
}

// ----------------------------------------------------------------------------
// Parameterized FFI exports (used by the Statement-based execute/fetchAll path)
// ----------------------------------------------------------------------------

#[no_mangle]
pub extern "C" fn sqlx4k_mysql_query_with_params(
    rt: *mut c_void,
    sql: *const c_char,
    params: *const Sqlx4kMysqlParam,
    params_len: c_int,
    callback: *mut c_void,
    fun: extern "C" fn(Sqlx4kMysqlPtr, *mut Sqlx4kMysqlResult),
) {
    let callback = Sqlx4kMysqlPtr { ptr: callback };
    let sql = c_chars_to_str_mysql(sql).to_owned();
    let owned = read_params(params, params_len);
    let runtime = RUNTIME.get().unwrap();
    let sqlx4k = unsafe { &*(rt as *mut Sqlx4kMySql) };
    runtime.spawn(async move {
        let result = sqlx4k.query_with_params(sql, owned).await;
        fun(callback, result)
    });
}

#[no_mangle]
pub extern "C" fn sqlx4k_mysql_fetch_all_with_params(
    rt: *mut c_void,
    sql: *const c_char,
    params: *const Sqlx4kMysqlParam,
    params_len: c_int,
    callback: *mut c_void,
    fun: extern "C" fn(Sqlx4kMysqlPtr, *mut Sqlx4kMysqlResult),
) {
    let callback = Sqlx4kMysqlPtr { ptr: callback };
    let sql = c_chars_to_str_mysql(sql).to_owned();
    let owned = read_params(params, params_len);
    let runtime = RUNTIME.get().unwrap();
    let sqlx4k = unsafe { &*(rt as *mut Sqlx4kMySql) };
    runtime.spawn(async move {
        let result = sqlx4k.fetch_all_with_params(sql, owned).await;
        fun(callback, result)
    });
}

#[no_mangle]
pub extern "C" fn sqlx4k_mysql_cn_query_with_params(
    rt: *mut c_void,
    cn: *mut c_void,
    sql: *const c_char,
    params: *const Sqlx4kMysqlParam,
    params_len: c_int,
    callback: *mut c_void,
    fun: extern "C" fn(Sqlx4kMysqlPtr, *mut Sqlx4kMysqlResult),
) {
    let cn = Sqlx4kMysqlPtr { ptr: cn };
    let callback = Sqlx4kMysqlPtr { ptr: callback };
    let sql = c_chars_to_str_mysql(sql).to_owned();
    let owned = read_params(params, params_len);
    let runtime = RUNTIME.get().unwrap();
    let sqlx4k = unsafe { &*(rt as *mut Sqlx4kMySql) };
    runtime.spawn(async move {
        let result = sqlx4k.cn_query_with_params(cn, sql, owned).await;
        fun(callback, result)
    });
}

#[no_mangle]
pub extern "C" fn sqlx4k_mysql_cn_fetch_all_with_params(
    rt: *mut c_void,
    cn: *mut c_void,
    sql: *const c_char,
    params: *const Sqlx4kMysqlParam,
    params_len: c_int,
    callback: *mut c_void,
    fun: extern "C" fn(Sqlx4kMysqlPtr, *mut Sqlx4kMysqlResult),
) {
    let cn = Sqlx4kMysqlPtr { ptr: cn };
    let callback = Sqlx4kMysqlPtr { ptr: callback };
    let sql = c_chars_to_str_mysql(sql).to_owned();
    let owned = read_params(params, params_len);
    let runtime = RUNTIME.get().unwrap();
    let sqlx4k = unsafe { &*(rt as *mut Sqlx4kMySql) };
    runtime.spawn(async move {
        let result = sqlx4k.cn_fetch_all_with_params(cn, sql, owned).await;
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
    let owned = read_params(params, params_len);
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
    let owned = read_params(params, params_len);
    let runtime = RUNTIME.get().unwrap();
    let sqlx4k = unsafe { &*(rt as *mut Sqlx4kMySql) };
    runtime.spawn(async move {
        let result = sqlx4k.tx_fetch_all_with_params(tx, sql, owned).await;
        fun(callback, result)
    });
}
