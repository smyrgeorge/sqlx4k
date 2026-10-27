use futures_core::Stream;
use futures_util::TryStreamExt;
use sqlx::error::ErrorKind;
use sqlx::mysql::{
    MySqlConnectOptions, MySqlConnection, MySqlDatabaseError, MySqlPool, MySqlPoolOptions,
    MySqlQueryResult, MySqlRow, MySqlTypeInfo, MySqlValueRef,
};
use sqlx::pool::PoolConnection;
// Re-export sqlx's chrono feature shim so we don't need a direct dependency on
// the chrono crate — sqlx already pulls it in when the matching feature is on.
use sqlx::types::chrono;
use sqlx::{
    Acquire, AssertSqlSafe, Column, Connection, Error, Execute, Executor, MySql, Row, Transaction,
    Type, TypeInfo, ValueRef,
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
    /// `LAST_INSERT_ID()` of the statement: the id generated for an inserted row, or 0.
    pub last_insert_id: c_ulonglong,
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
    /// NUL-terminated, but read with `value_len`: a MySQL text value may hold NUL characters itself.
    pub value: *mut c_char,
    /// The length of `value` in bytes, without its terminating NUL.
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
pub struct Sqlx4kMysqlParam {
    pub kind: c_int,
    pub i64_val: i64,
    pub f64_val: f64,
    pub text: *const c_char,
    /// Length of `text` in bytes; a string may contain NUL, so it is not read as a C string.
    pub text_len: c_int,
    pub blob: *const u8,
    pub blob_len: c_int,
}

/// Reads a TEXT-shaped parameter by its length, not as a C string: a string may contain NUL, at which a C string would
/// silently cut it.
fn read_text(p: &Sqlx4kMysqlParam) -> String {
    let len = p.text_len.max(0) as usize;
    let bytes = if len == 0 || p.text.is_null() {
        &[][..]
    } else {
        unsafe { slice::from_raw_parts(p.text as *const u8, len) }
    };
    String::from_utf8_lossy(bytes).into_owned()
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
            PARAM_TEXT => OwnedParam::Text(read_text(p)),
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

/// How long closing a pool waits for its checked-out connections to be given back (see [close_pool]).
const CLOSE_GRACE: Duration = Duration::from_secs(1);

/// Closes the pool without waiting for good for the connections still checked out. `Pool::close()` waits until every
/// checked-out connection is given back (and only then closes the idle ones): one the caller still holds (or abandoned)
/// would hang it for good, and the caller cannot cancel it. The idle connections are closed here instead, and the
/// pool is marked closed; the checked-out ones are closed as they are given back, as the JVM drivers do.
///
/// The idle connections are taken out before the pool is marked closed (a closed pool gives none), but only detached
/// and closed after: a connection detached from an open pool that keeps `min_connections` makes it open a replacement.
///
/// The close is then given [CLOSE_GRACE] to finish: sqlx gives a released connection back in a task of its own, so
/// the connection of a query (or of a connection) released just before is usually still on its way back, and would
/// otherwise stay open after this returns (with its database file, on SQLite). A connection the caller still holds is
/// closed once given back.
async fn close_pool<DB: sqlx::Database>(pool: &sqlx::Pool<DB>) {
    let idle: Vec<_> = std::iter::from_fn(|| pool.try_acquire()).collect();
    // Marks the pool closed when called, before it is awaited: no connection is acquired or opened after this.
    let closing = pool.close();
    for cn in idle {
        let _ = sqlx::Connection::close(cn.detach()).await;
    }
    if tokio::time::timeout(CLOSE_GRACE, closing).await.is_err() {
        let closing = pool.clone();
        tokio::spawn(async move { closing.close().await });
    }
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
                // Allocated by `column_value_of` with its length (the value may hold NUL characters).
                let len = col.value_len as usize + 1;
                drop(unsafe { Box::from_raw(slice::from_raw_parts_mut(col.value as *mut u8, len)) });
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
/// The most rows a chunk is allocated room for up front: a fetch size is a hint, and a large one (`Int.MAX_VALUE`)
/// asked for room for that many rows at once, hundreds of GB, which aborted the process (`panic = "abort"`). A chunk
/// still grows to the fetch size.
const STREAM_MAX_PREALLOCATED_ROWS: usize = 1_000;

type StreamChunk = Result<Vec<MySqlRow>, sqlx::Error>;
/// The rows of a query, each result set's rows followed by its result (`Left`), so a chunk can end where a
/// result set does.
type RowStream<'e> =
    Pin<Box<dyn Stream<Item = Result<sqlx::Either<MySqlQueryResult, MySqlRow>, sqlx::Error>> + Send + 'e>>;

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
/// (as `fetch_all` does), a prepared statement when there are parameters to bind. Either can return
/// several result sets (several statements, or a procedure that runs several SELECTs), one after the other.
fn stream_rows_of<'e>(
    cn: &'e mut MySqlConnection,
    sql: String,
    params: Vec<OwnedParam>,
) -> RowStream<'e> {
    if params.is_empty() {
        cn.fetch_many(AssertSqlSafe(sql))
    } else {
        cn.fetch_many(bind_params(sqlx::query::<MySql>(AssertSqlSafe(sql)), params))
    }
}

/// Drives a row stream, sending chunks of `fetch_size` rows until the stream ends or fails, or the
/// consumer closes it (`cancel` fires, or the channel is dropped). Returns whether the stream was
/// consumed to its end (an error counts as the end: the connection is in a clean state again).
///
/// A chunk also ends where a result set does: its schema is read from its first row, so it must not
/// hold the rows of two result sets, whose columns differ.
///
/// sqlx's read of a MySQL packet is not cancel-safe: it takes the packet's header off the connection's
/// buffer before it waits for the payload, so a read dropped midway leaves the connection out of step
/// with the server (the next statement reads payload bytes as a header, and waits for that many bytes,
/// up to 16 MB, or misreads the protocol). Unless `abort_reads` is set (the connection is closed after
/// a cancelled stream), a row read thus runs to its end, and `cancel` is checked between rows; sqlx then
/// reads and discards the rest of the result on the connection's next use.
async fn stream_rows(
    mut rows: RowStream<'_>,
    fetch_size: usize,
    tx: mpsc::Sender<StreamChunk>,
    mut cancel: oneshot::Receiver<()>,
    abort_reads: bool,
) -> bool {
    let mut chunk: Vec<MySqlRow> = Vec::with_capacity(fetch_size.min(STREAM_MAX_PREALLOCATED_ROWS));
    loop {
        let next = if abort_reads {
            tokio::select! {
                _ = &mut cancel => return false,
                next = rows.try_next() => next,
            }
        } else {
            // Fired, or its sender dropped.
            if !matches!(cancel.try_recv(), Err(oneshot::error::TryRecvError::Empty)) {
                return false;
            }
            rows.try_next().await
        };
        match next {
            Ok(Some(item)) => {
                let full = match item {
                    sqlx::Either::Right(row) => {
                        chunk.push(row);
                        chunk.len() >= fetch_size
                    }
                    // The end of a result set.
                    sqlx::Either::Left(_) => !chunk.is_empty(),
                };
                if full {
                    let full = std::mem::replace(&mut chunk, Vec::with_capacity(fetch_size.min(STREAM_MAX_PREALLOCATED_ROWS)));
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
                last_insert_id: res.last_insert_id(),
                ..Default::default()
            },
            Err(err) => sqlx4k_mysql_error_result_of(err),
        };
        result.leak()
    }

    async fn fetch_all(&self, sql: String) -> *mut Sqlx4kMysqlResult {
        let result = fetch_last(&self.pool, AssertSqlSafe(sql)).await;
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
                last_insert_id: res.last_insert_id(),
                ..Default::default()
            },
            Err(err) => sqlx4k_mysql_error_result_of(err),
        };
        result.leak()
    }

    async fn cn_fetch_all(&self, cn: Sqlx4kMysqlPtr, sql: String) -> *mut Sqlx4kMysqlResult {
        let cn = unsafe { &mut *(cn.ptr as *mut PoolConnection<MySql>) };
        let result = fetch_last(&mut **cn, AssertSqlSafe(sql)).await;
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
                last_insert_id: res.last_insert_id(),
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
        let result = fetch_last(&mut *tx, AssertSqlSafe(sql)).await;
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
                stream_rows(rows, fetch_size, tx, cancel_rx, true).await
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
            stream_rows(rows, fetch_size, tx, cancel_rx, false).await;
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
            stream_rows(rows, fetch_size, chunks, cancel_rx, false).await;
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
        close_pool(&self.pool).await;
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
                last_insert_id: res.last_insert_id(),
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
        let result = fetch_last(&self.pool, q).await;
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
                last_insert_id: res.last_insert_id(),
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
        let result = fetch_last(&mut **cn, q).await;
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
                last_insert_id: res.last_insert_id(),
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
        let result = fetch_last(&mut *tx, q).await;
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
    // A malformed URL is reported to the caller (panic = "abort" would take the process down).
    let options: MySqlConnectOptions = match url.parse() {
        Ok(options) => options,
        Err(err) => return sqlx4k_mysql_error_result_of(err).leak(),
    };
    let options = options.username(username).password(password);

    // Create the tokio runtime, once: get_or_init also when two pools are created concurrently (checking and then
    // setting raced, and the losing set().unwrap() panicked).
    let runtime = RUNTIME.get_or_init(|| Runtime::new().expect("failed to create the tokio runtime"));

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

/// Runs a query and returns the rows of the last of its result sets that has rows. A query can return several
/// result sets, from several statements or a procedure that runs several SELECTs, which fetch_all would return
/// one after the other: the result describes the columns of a single result set. A result set with no rows is
/// passed over, as the status a CALL ends with is one (sqlx does not tell it from an empty SELECT).
async fn fetch_last<'e, 'q: 'e, E, Q>(executor: E, query: Q) -> Result<Vec<MySqlRow>, sqlx::Error>
where
    E: Executor<'e, Database = MySql>,
    Q: 'q + Execute<'q, MySql>,
{
    let mut items = executor.fetch_many(query);
    let mut current = Vec::new();
    let mut last = Vec::new();
    while let Some(item) = items.try_next().await? {
        match item {
            // The end of a result set.
            sqlx::Either::Left(_) => {
                if !current.is_empty() {
                    last = std::mem::take(&mut current)
                }
            }
            sqlx::Either::Right(row) => current.push(row),
        }
    }
    Ok(if current.is_empty() { last } else { current })
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
                let kind: &str = mysql_type_name(&info);
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

/// The column's type name, as reported in the result metadata.
///
/// MySQL also flags text with a binary collation (e.g. `utf8mb4_bin`, which `->>` / JSON_UNQUOTE returns) as
/// BINARY, so sqlx names it like a blob (`LONGBLOB`, `VARBINARY`, ...). Only the `binary` collation means raw
/// bytes: that is sqlx's own check for `&str`, so such text is reported under its text name instead (as the
/// JVM driver does).
fn mysql_type_name(info: &MySqlTypeInfo) -> &str {
    let name = info.name();
    if !<&str as Type<MySql>>::compatible(info) {
        return name;
    }
    match name {
        "BINARY" => "CHAR",
        "VARBINARY" => "VARCHAR",
        "TINYBLOB" => "TINYTEXT",
        "BLOB" => "TEXT",
        "MEDIUMBLOB" => "MEDIUMTEXT",
        "LONGBLOB" => "LONGTEXT",
        _ => name,
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
fn decode_mysql_column_value(row: &MySqlRow, ordinal: usize) -> (*mut c_char, c_int) {
    let (type_name, unsigned): (String, bool) = {
        let value_ref = row.try_get_raw(ordinal).unwrap();
        if value_ref.is_null() {
            return (null_mut(), 0);
        }
        let info = value_ref.type_info();
        // The UNSIGNED flag is private to sqlx; the unsigned integers' compatibility checks it.
        (mysql_type_name(&info).to_string(), <u8 as Type<MySql>>::compatible(&info))
    };

    let s: String = match type_name.as_str() {
        // sqlx-mysql reports `TINYINT(1)` (what `BOOLEAN`/`BOOL` creates) as
        // "BOOLEAN" with `display = 1` rather than the bare "TINYINT" name, also when it is
        // unsigned: its values then reach 255, which an i8 cannot hold (decoding it panicked, aborting
        // the process, or wrapped around to a negative number).
        "BOOLEAN" if unsigned => row.get_unchecked::<u8, _>(ordinal).to_string(),
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
        // Only the `binary` collation reaches here: `mysql_type_name` renames binary-collated text.
        "BLOB" | "TINYBLOB" | "MEDIUMBLOB" | "LONGBLOB" | "VARBINARY" | "BINARY" => {
            let bytes: Vec<u8> = row.get_unchecked(ordinal);
            hex::encode(bytes)
        }
        // Temporal types arrive as packed binary in the prepared-statement
        // protocol; format them to `yyyy-MM-dd HH:mm:ss[.uuuuuu]` so the existing
        // asInstant / asLocalDate* decoders round-trip. See decode_mysql_temporal.
        "DATE" => decode_mysql_temporal(row, ordinal, |d: chrono::NaiveDate| d.to_string(), decode_mysql_date_binary),
        "TIME" => decode_mysql_temporal(row, ordinal, |t: chrono::NaiveTime| t.to_string(), decode_mysql_time_binary),
        "DATETIME" | "TIMESTAMP" => decode_mysql_temporal(
            row,
            ordinal,
            |dt: chrono::NaiveDateTime| dt.format("%Y-%m-%d %H:%M:%S%.6f").to_string(),
            decode_mysql_datetime_binary,
        ),
        // YEAR arrives as text in the text protocol, as a 2-byte integer in the binary protocol (read as text, it
        // was raw bytes). BIT arrives as its bytes, most significant first, in either protocol: it is read as the
        // number it holds (`1` for b'1'), which the Kotlin decoders read (asBoolean, asLong), rather than as the
        // control characters its bytes were as text.
        "YEAR" | "BIT" => match row.try_get_unchecked::<u64, _>(ordinal) {
            Ok(value) => value.to_string(),
            Err(_) => String::new(),
        },
        // VARCHAR / CHAR / TEXT (all varieties) / JSON / ENUM / SET
        // and anything else fall through to `&str`. In the text protocol they
        // arrive as text bytes; for text-typed columns this also works in the
        // binary protocol. Decode leniently so that a binary-only type we don't
        // model can never abort the host process.
        _ => match row.try_get_unchecked::<Vec<u8>, _>(ordinal) {
            Ok(bytes) => String::from_utf8_lossy(&bytes).into_owned(),
            Err(_) => String::new(),
        },
    };
    column_value_of(s)
}

/// Hands a value over with its length: a MySQL text value may hold NUL characters, at which a C string would end
/// (they used to be dropped). It stays NUL-terminated as well; `sqlx4k_mysql_free_result` frees it.
fn column_value_of(value: String) -> (*mut c_char, c_int) {
    let mut bytes = value.into_bytes();
    let len = bytes.len() as c_int;
    bytes.push(0);
    (Box::into_raw(bytes.into_boxed_slice()) as *mut u8 as *mut c_char, len)
}

/// Decodes a DATE / TIME / DATETIME / TIMESTAMP value without panicking on the values chrono cannot represent:
/// zero dates ('0000-00-00'), TIME beyond 24 hours or negative ('838:59:59', '-01:30:00'). `get_unchecked`
/// unwrapped chrono's error, and with `panic = "abort"` a single such value took the host process down. Binary values
/// are rendered from the wire format by `binary`; text values are decoded by chrono and passed through as MySQL
/// prints them when chrono cannot represent them.
fn decode_mysql_temporal<'r, T: sqlx::Decode<'r, MySql>>(
    row: &'r MySqlRow,
    ordinal: usize,
    render: impl FnOnce(T) -> String,
    binary: fn(&[u8]) -> Option<String>,
) -> String {
    let Ok(bytes) = row.try_get_unchecked::<Vec<u8>, _>(ordinal) else { return String::new() };
    // The text protocol sends the value as MySQL prints it, the binary protocol (prepared statements) as a length
    // byte (0..=12) followed by packed fields. sqlx keeps the format private, but the first byte tells them apart:
    // text always starts with a digit or '-'.
    if !matches!(bytes.first(), Some(b'0'..=b'9' | b'-')) {
        return binary(&bytes).unwrap_or_default();
    }
    match row.try_get_unchecked::<T, _>(ordinal) {
        Ok(v) => render(v),
        Err(_) => String::from_utf8_lossy(&bytes).into_owned(),
    }
}

/// Binary DATE / DATETIME / TIMESTAMP: a length byte (0, 4, 7 or 11), then year (u16 LE), month, day, hour, minute,
/// second (u8) and microseconds (u32 LE); trailing fields are omitted when zero (all of them for a zero date).
fn mysql_datetime_fields(bytes: &[u8]) -> Option<(u16, u8, u8, u8, u8, u8, u32)> {
    let (&len, rest) = bytes.split_first()?;
    let rest = rest.get(..len as usize)?;
    let byte = |i: usize| rest.get(i).copied().unwrap_or(0);
    let year = rest.get(0..2).map_or(0, |b| u16::from_le_bytes([b[0], b[1]]));
    let micros = match rest.get(7..11) {
        Some(b) => u32::from_le_bytes(b.try_into().ok()?),
        None => 0,
    };
    Some((year, byte(2), byte(3), byte(4), byte(5), byte(6), micros))
}

fn decode_mysql_date_binary(bytes: &[u8]) -> Option<String> {
    let (year, month, day, ..) = mysql_datetime_fields(bytes)?;
    Some(format!("{year:04}-{month:02}-{day:02}"))
}

fn decode_mysql_datetime_binary(bytes: &[u8]) -> Option<String> {
    let (year, month, day, hour, minute, second, micros) = mysql_datetime_fields(bytes)?;
    Some(format!("{year:04}-{month:02}-{day:02} {hour:02}:{minute:02}:{second:02}.{micros:06}"))
}

/// Binary TIME: a length byte (0, 8 or 12), then sign (u8, 1 when negative), days (u32 LE), hours, minutes, seconds
/// (u8) and microseconds (u32 LE). Rendered like MySQL's text form, `[-]HH:MM:SS[.ffffff]` with hours past 24.
fn decode_mysql_time_binary(bytes: &[u8]) -> Option<String> {
    let (&len, rest) = bytes.split_first()?;
    if len == 0 {
        return Some("00:00:00".to_string());
    }
    let rest = rest.get(..len as usize)?;
    if rest.len() < 8 {
        return None;
    }
    let sign = if rest[0] == 1 { "-" } else { "" };
    let days = u32::from_le_bytes(rest[1..5].try_into().ok()?);
    let hours = days as u64 * 24 + rest[5] as u64;
    let micros = match rest.get(8..12) {
        Some(b) => u32::from_le_bytes(b.try_into().ok()?),
        None => 0,
    };
    let hms = format!("{sign}{hours:02}:{:02}:{:02}", rest[6], rest[7]);
    Some(if micros == 0 { hms } else { format!("{hms}.{micros:06}") })
}

fn sqlx4k_mysql_row_of(row: &MySqlRow) -> Sqlx4kMysqlRow {
    let columns = row.columns();
    if columns.is_empty() {
        Sqlx4kMysqlRow::default()
    } else {
        let columns: Vec<Sqlx4kMysqlColumn> = row
            .columns()
            .iter()
            .map(|c| {
                let (value, value_len) = decode_mysql_column_value(row, c.ordinal());
                Sqlx4kMysqlColumn {
                    ordinal: c.ordinal() as c_int,
                    value,
                    value_len,
                }
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
