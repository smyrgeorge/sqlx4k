use futures_core::Stream;
use futures_util::TryStreamExt;
use sqlx::encode::IsNull;
use sqlx::error::BoxDynError;
use sqlx::error::ErrorKind;
use sqlx::pool::PoolConnection;
use sqlx::postgres::types::Oid;
use sqlx::postgres::{
    PgArgumentBuffer, PgConnectOptions, PgConnection, PgListener, PgNotification, PgPool,
    PgPoolOptions, PgQueryResult, PgRow, PgTypeInfo, PgValueFormat, PgValueRef,
};
// Re-export sqlx's chrono/uuid feature shims so we don't need direct dependencies
// on those crates — sqlx already pulls them in when the matching features are on.
use sqlx::types::{chrono, uuid};
use sqlx::{
    Acquire, AssertSqlSafe, Column, Connection, Encode, Error, Executor, Postgres, Row,
    Transaction, Type, TypeInfo, ValueRef,
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
pub struct Sqlx4kPostgresPtr {
    pub ptr: *mut c_void,
}
unsafe impl Send for Sqlx4kPostgresPtr {}
unsafe impl Sync for Sqlx4kPostgresPtr {}

impl Sqlx4kPostgresPtr {
    /// The wrapped pointer. Going through a method (rather than the `ptr` field) inside a spawned
    /// task captures the whole `Send` wrapper, not the raw pointer field (edition-2021 captures).
    fn raw(&self) -> *mut c_void {
        self.ptr
    }
}

#[repr(C)]
pub struct Sqlx4kPostgresResult {
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
    /// A row stream opened by `sqlx4k_postgresql_*_stream_open` (see `Sqlx4kPostgresStream`), or null.
    pub stream: *mut c_void,
    pub schema: *mut Sqlx4kPostgresSchema,
    pub size: c_int,
    pub rows: *mut Sqlx4kPostgresRow,
}

impl Sqlx4kPostgresResult {
    pub fn leak(self) -> *mut Sqlx4kPostgresResult {
        let result = Box::new(self);
        let result = Box::leak(result);
        result
    }
}

impl Default for Sqlx4kPostgresResult {
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
pub struct Sqlx4kPostgresSchema {
    pub size: c_int,
    pub columns: *mut Sqlx4kPostgresSchemaColumn,
}

impl Default for Sqlx4kPostgresSchema {
    fn default() -> Self {
        Self {
            size: 0,
            columns: null_mut(),
        }
    }
}

#[repr(C)]
pub struct Sqlx4kPostgresSchemaColumn {
    pub ordinal: c_int,
    pub name: *mut c_char,
    pub kind: *mut c_char,
}

#[repr(C)]
pub struct Sqlx4kPostgresRow {
    pub size: c_int,
    pub columns: *mut Sqlx4kPostgresColumn,
}

impl Default for Sqlx4kPostgresRow {
    fn default() -> Self {
        Self {
            size: 0,
            columns: null_mut(),
        }
    }
}

#[repr(C)]
pub struct Sqlx4kPostgresColumn {
    pub ordinal: c_int,
    pub value: *mut c_char,
}

// ----------------------------------------------------------------------------
// Prepared statement parameter binding
// ----------------------------------------------------------------------------

// Value kinds. The non-NULL kinds also identify the postgres type to bind for
// `TypedNull` via the `null_type` field on the param struct.
//
// Postgres extended-protocol bind is strict about parameter types — bigint
// (`int8`) won't implicit-cast into `int4`/`int2`, and `bool` won't accept any
// integer kind. So we keep separate kinds per Rust scalar width instead of
// folding everything into INT/REAL.
pub const PARAM_NULL: c_int = 0;
pub const PARAM_BOOL: c_int = 1;
pub const PARAM_INT2: c_int = 2;
pub const PARAM_INT4: c_int = 3;
pub const PARAM_INT8: c_int = 4;
pub const PARAM_FLOAT4: c_int = 5;
pub const PARAM_FLOAT8: c_int = 6;
pub const PARAM_TEXT: c_int = 7;
pub const PARAM_BLOB: c_int = 8;
pub const PARAM_DATE: c_int = 9;
pub const PARAM_TIME: c_int = 10;
pub const PARAM_TIMESTAMP: c_int = 11;
pub const PARAM_TIMESTAMPTZ: c_int = 12;
pub const PARAM_UUID: c_int = 13;

/// FFI representation of a single bound parameter. Only the field matching `kind`
/// is read for the value; for `kind == PARAM_NULL`, `null_type` carries the
/// intended postgres type (one of the other PARAM_* kinds, or `PARAM_NULL` for
/// "untyped" — which falls back to a TEXT-typed null).
#[repr(C)]
pub struct Sqlx4kPostgresParam {
    pub kind: c_int,
    pub null_type: c_int,
    pub i64_val: i64,
    pub f64_val: f64,
    pub text: *const c_char,
    /// Length of `text` in bytes; a string may contain NUL, so it is not read as a C string.
    pub text_len: c_int,
    pub blob: *const u8,
    pub blob_len: c_int,
}

/// A NULL value with `Oid(0)` (unknown) so that postgres infers the parameter
/// type from the surrounding query context. This is the only safe default for
/// `bind(idx, null)` (untyped null) — binding `None::<String>` would lock the
/// parameter to TEXT and have postgres reject most non-text columns at execute.
struct UntypedNull;

impl Type<Postgres> for UntypedNull {
    fn type_info() -> PgTypeInfo {
        PgTypeInfo::with_oid(Oid(0))
    }
}

impl Encode<'_, Postgres> for UntypedNull {
    fn encode_by_ref(&self, _buf: &mut PgArgumentBuffer) -> Result<IsNull, BoxDynError> {
        Ok(IsNull::Yes)
    }
}

enum OwnedParam {
    /// `null_type` matches one of the PARAM_* kinds (or PARAM_NULL when untyped).
    Null(c_int),
    Bool(bool),
    Int2(i16),
    Int4(i32),
    Int8(i64),
    Float4(f32),
    Float8(f64),
    Text(String),
    Blob(Vec<u8>),
    Date(chrono::NaiveDate),
    Time(chrono::NaiveTime),
    Timestamp(chrono::NaiveDateTime),
    TimestampTz(chrono::DateTime<chrono::Utc>),
    Uuid(uuid::Uuid),
}

/// Reads a TEXT-shaped parameter by its length, not as a C string: a string may contain NUL, at which a C string would
/// silently cut it.
fn read_text(p: &Sqlx4kPostgresParam) -> String {
    let len = p.text_len.max(0) as usize;
    let bytes = if len == 0 || p.text.is_null() {
        &[][..]
    } else {
        unsafe { slice::from_raw_parts(p.text as *const u8, len) }
    };
    String::from_utf8_lossy(bytes).into_owned()
}

/// Reads `count` params from a C array and copies their data into owned Rust
/// values, so the resulting Vec can be moved into an async block without
/// referencing the caller's memory.
///
/// A value that cannot be read is reported as an error (see `params_error`): a panic here would abort the process.
fn read_params(params: *const Sqlx4kPostgresParam, count: c_int) -> Result<Vec<OwnedParam>, String> {
    if params.is_null() || count <= 0 {
        return Ok(Vec::new());
    }
    let slice = unsafe { slice::from_raw_parts(params, count as usize) };
    slice
        .iter()
        .map(|p| -> Result<OwnedParam, String> {
            Ok(match p.kind {
                PARAM_NULL => OwnedParam::Null(p.null_type),
                PARAM_BOOL => OwnedParam::Bool(p.i64_val != 0),
                PARAM_INT2 => OwnedParam::Int2(p.i64_val as i16),
                PARAM_INT4 => OwnedParam::Int4(p.i64_val as i32),
                PARAM_INT8 => OwnedParam::Int8(p.i64_val),
                PARAM_FLOAT4 => OwnedParam::Float4(p.f64_val as f32),
                PARAM_FLOAT8 => OwnedParam::Float8(p.f64_val),
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
                PARAM_DATE => {
                    let s = read_text(p);
                    let d = chrono::NaiveDate::parse_from_str(&s, "%Y-%m-%d")
                        .map_err(|e| format!("Invalid DATE '{}': {}", s, e))?;
                    OwnedParam::Date(d)
                }
                PARAM_TIME => {
                    let s = read_text(p);
                    let t = chrono::NaiveTime::parse_from_str(&s, "%H:%M:%S%.f")
                        .or_else(|_| chrono::NaiveTime::parse_from_str(&s, "%H:%M:%S"))
                        .map_err(|e| format!("Invalid TIME '{}': {}", s, e))?;
                    OwnedParam::Time(t)
                }
                PARAM_TIMESTAMP => {
                    let s = read_text(p);
                    let dt = chrono::NaiveDateTime::parse_from_str(&s, "%Y-%m-%d %H:%M:%S%.f")
                        .or_else(|_| chrono::NaiveDateTime::parse_from_str(&s, "%Y-%m-%d %H:%M:%S"))
                        .map_err(|e| format!("Invalid TIMESTAMP '{}': {}", s, e))?;
                    OwnedParam::Timestamp(dt)
                }
                PARAM_TIMESTAMPTZ => {
                    // Kotlin emits UTC `yyyy-MM-dd HH:mm:ss.uuuuuu` (no offset suffix).
                    // Treat it as UTC.
                    let s = read_text(p);
                    let naive = chrono::NaiveDateTime::parse_from_str(&s, "%Y-%m-%d %H:%M:%S%.f")
                        .or_else(|_| chrono::NaiveDateTime::parse_from_str(&s, "%Y-%m-%d %H:%M:%S"))
                        .map_err(|e| format!("Invalid TIMESTAMPTZ '{}': {}", s, e))?;
                    OwnedParam::TimestampTz(naive.and_utc())
                }
                PARAM_UUID => {
                    let s = read_text(p);
                    let u = uuid::Uuid::parse_str(&s)
                        .map_err(|e| format!("Invalid UUID '{}': {}", s, e))?;
                    OwnedParam::Uuid(u)
                }
                other => return Err(format!("Unknown Sqlx4kPostgresParam kind: {}", other)),
            })
        })
        .collect()
}

/// The result of a call whose parameters could not be read, handing back the transaction (`tx`, or null) it was
/// given: the caller takes it back from every result.
fn params_error(err: String, tx: *mut c_void) -> *mut Sqlx4kPostgresResult {
    Sqlx4kPostgresResult {
        tx,
        ..sqlx4k_postgres_error_result_of(Error::Encode(err.into()))
    }
    .leak()
}

fn bind_params<'q>(
    mut q: sqlx::query::Query<'q, Postgres, sqlx::postgres::PgArguments>,
    params: Vec<OwnedParam>,
) -> sqlx::query::Query<'q, Postgres, sqlx::postgres::PgArguments> {
    for p in params {
        q = match p {
            OwnedParam::Null(t) => match t {
                PARAM_BOOL => q.bind(None::<bool>),
                PARAM_INT2 => q.bind(None::<i16>),
                PARAM_INT4 => q.bind(None::<i32>),
                PARAM_INT8 => q.bind(None::<i64>),
                PARAM_FLOAT4 => q.bind(None::<f32>),
                PARAM_FLOAT8 => q.bind(None::<f64>),
                PARAM_BLOB => q.bind(None::<Vec<u8>>),
                PARAM_DATE => q.bind(None::<chrono::NaiveDate>),
                PARAM_TIME => q.bind(None::<chrono::NaiveTime>),
                PARAM_TIMESTAMP => q.bind(None::<chrono::NaiveDateTime>),
                PARAM_TIMESTAMPTZ => q.bind(None::<chrono::DateTime<chrono::Utc>>),
                PARAM_UUID => q.bind(None::<uuid::Uuid>),
                // Untyped or unknown: bind a NULL with `Oid(0)` so postgres
                // infers the type from the surrounding query context (e.g. the
                // target column in an `INSERT ... VALUES (?, ?)`).
                _ => q.bind(UntypedNull),
            },
            OwnedParam::Bool(v) => q.bind(v),
            OwnedParam::Int2(v) => q.bind(v),
            OwnedParam::Int4(v) => q.bind(v),
            OwnedParam::Int8(v) => q.bind(v),
            OwnedParam::Float4(v) => q.bind(v),
            OwnedParam::Float8(v) => q.bind(v),
            OwnedParam::Text(s) => q.bind(s),
            OwnedParam::Blob(b) => q.bind(b),
            OwnedParam::Date(d) => q.bind(d),
            OwnedParam::Time(t) => q.bind(t),
            OwnedParam::Timestamp(dt) => q.bind(dt),
            OwnedParam::TimestampTz(dt) => q.bind(dt),
            OwnedParam::Uuid(u) => q.bind(u),
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
pub extern "C" fn auto_generated_for_struct_postgres_Sqlx4kPostgresPtr(_: Sqlx4kPostgresPtr) {}
#[no_mangle]
pub extern "C" fn auto_generated_for_struct_postgres_Sqlx4kPostgresResult(_: Sqlx4kPostgresResult) {
}
#[no_mangle]
pub extern "C" fn auto_generated_for_struct_postgres_Sqlx4kPostgresParam(_: Sqlx4kPostgresParam) {}

#[no_mangle]
pub extern "C" fn sqlx4k_postgres_free_result(ptr: *mut Sqlx4kPostgresResult) {
    let ptr: Sqlx4kPostgresResult = unsafe { *Box::from_raw(ptr) };

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

    let schema: Sqlx4kPostgresSchema = unsafe { *Box::from_raw(ptr.schema) };
    let columns: Vec<Sqlx4kPostgresSchemaColumn> =
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

    let rows: Vec<Sqlx4kPostgresRow> =
        unsafe { Vec::from_raw_parts(ptr.rows, ptr.size as usize, ptr.size as usize) };
    for row in rows {
        let columns: Vec<Sqlx4kPostgresColumn> =
            unsafe { Vec::from_raw_parts(row.columns, row.size as usize, row.size as usize) };
        for col in columns {
            if col.value != null_mut() {
                let value = unsafe { CString::from_raw(col.value) };
                std::mem::drop(value);
            }
        }
    }
}

pub fn sqlx4k_postgres_error_result_of(err: sqlx::Error) -> Sqlx4kPostgresResult {
    // A database error also carries what the server reported: the SQLSTATE and a portable kind
    // (unique violation, foreign key violation, ...), surfaced on `SQLError`. PostgreSQL has no
    // numeric error code: the SQLSTATE is the code.
    let (sql_state, native_code, kind): (Option<String>, c_int, c_int) = match &err {
        Error::Database(e) => {
            let sql_state = e.code().map(|c| c.to_string());
            let kind = sqlx4k_postgres_kind_of(e.kind(), sql_state.as_deref());
            (sql_state, NO_NATIVE_CODE, kind)
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
    Sqlx4kPostgresResult {
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
fn sqlx4k_postgres_constraint_kind_of(kind: ErrorKind) -> Option<c_int> {
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

/// Classifies a PostgreSQL error as a `SQLError.Kind` ordinal: constraint violations come from sqlx,
/// concurrency failures from the SQLSTATE. Mirrors `postgresKindOf` in the JVM driver.
/// https://www.postgresql.org/docs/current/errcodes-appendix.html
fn sqlx4k_postgres_kind_of(kind: ErrorKind, sql_state: Option<&str>) -> c_int {
    if let Some(kind) = sqlx4k_postgres_constraint_kind_of(kind) {
        return kind;
    }
    match sql_state {
        // deadlock_detected, serialization_failure, lock_not_available
        Some("40P01") => KIND_DEADLOCK,
        Some("40001") => KIND_SERIALIZATION_FAILURE,
        Some("55P03") => KIND_LOCK_TIMEOUT,
        _ => KIND_OTHER,
    }
}

pub fn c_chars_to_str_postgres<'a>(c_chars: *const c_char) -> &'a str {
    unsafe { CStr::from_ptr(c_chars).to_str().unwrap() }
}

// ============================================================================
// PostgreSQL-specific implementation
// ============================================================================

// ============================================================================
// Streaming (fetch)
// ============================================================================

/// Rows are moved to Kotlin in chunks of the requested fetch size; the channel holds at most this
/// many ready chunks, which is the backpressure that keeps a slow consumer from buffering a large
/// result on the Rust side.
const STREAM_CHANNEL_CAPACITY: usize = 2;
const STREAM_DEFAULT_FETCH_SIZE: usize = 500;

type StreamChunk = Result<Vec<PgRow>, sqlx::Error>;
/// The rows of a query, each statement's rows followed by its result (`Left`), so a chunk can end where a
/// statement does.
type RowStream<'e> =
    Pin<Box<dyn Stream<Item = Result<sqlx::Either<PgQueryResult, PgRow>, sqlx::Error>> + Send + 'e>>;

/// A result set being streamed to Kotlin, row chunk by row chunk.
///
/// sqlx streams rows straight off the wire (no server-side cursor), and the stream borrows the
/// connection it runs on, so both live in a spawned task that owns them and sends the chunks through
/// `rx`. `sqlx4k_postgresql_stream_next` receives one chunk; `sqlx4k_postgresql_stream_close` stops
/// the task (dropping `cancel`) and joins it before freeing the handle, so the connection or
/// transaction the task was borrowing is no longer in use once Kotlin continues.
struct Sqlx4kPostgresStream {
    rx: tokio::sync::Mutex<mpsc::Receiver<StreamChunk>>,
    cancel: oneshot::Sender<()>,
    task: JoinHandle<()>,
}

/// Opens the row stream of a query on the given connection: the simple protocol for a plain query
/// (as `fetch_all` does), a prepared statement when there are parameters to bind. The simple protocol
/// runs every statement of the SQL and returns the rows of each one after the other.
fn stream_rows_of<'e>(
    cn: &'e mut PgConnection,
    sql: String,
    params: Vec<OwnedParam>,
) -> RowStream<'e> {
    if params.is_empty() {
        cn.fetch_many(AssertSqlSafe(sql))
    } else {
        Box::pin(
            bind_params(sqlx::query::<Postgres>(AssertSqlSafe(sql)), params)
                .fetch(cn)
                .map_ok(sqlx::Either::Right),
        )
    }
}

/// Drives a row stream, sending chunks of `fetch_size` rows until the stream ends or fails, or the
/// consumer closes it (`cancel` fires, or the channel is dropped). Returns whether the stream was
/// consumed to its end (an error counts as the end: the connection is in a clean state again).
///
/// A chunk also ends where a statement does: its schema is read from its first row, so it must not
/// hold the rows of two statements, whose columns differ.
async fn stream_rows(
    mut rows: RowStream<'_>,
    fetch_size: usize,
    tx: mpsc::Sender<StreamChunk>,
    mut cancel: oneshot::Receiver<()>,
) -> bool {
    let mut chunk: Vec<PgRow> = Vec::with_capacity(fetch_size);
    loop {
        let next = tokio::select! {
            _ = &mut cancel => return false,
            next = rows.try_next() => next,
        };
        match next {
            Ok(Some(item)) => {
                let full = match item {
                    sqlx::Either::Right(row) => {
                        chunk.push(row);
                        chunk.len() >= fetch_size
                    }
                    // The end of a statement.
                    sqlx::Either::Left(_) => !chunk.is_empty(),
                };
                if full {
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

fn stream_result_of(stream: Sqlx4kPostgresStream) -> *mut Sqlx4kPostgresResult {
    Sqlx4kPostgresResult {
        stream: Box::into_raw(Box::new(stream)) as *mut c_void,
        ..Default::default()
    }
    .leak()
}

static RUNTIME: OnceLock<Runtime> = OnceLock::new();

#[derive(Debug)]
struct Sqlx4kPostgreSql {
    connect_options: PgConnectOptions,
    pool: PgPool,
}

impl Sqlx4kPostgreSql {
    async fn query(&self, sql: String) -> *mut Sqlx4kPostgresResult {
        let result = self.pool.execute(AssertSqlSafe(sql)).await;
        let result = match result {
            Ok(res) => Sqlx4kPostgresResult {
                rows_affected: res.rows_affected(),
                ..Default::default()
            },
            Err(err) => sqlx4k_postgres_error_result_of(err),
        };
        result.leak()
    }

    async fn fetch_all(&self, sql: String) -> *mut Sqlx4kPostgresResult {
        let result = fetch_last(&self.pool, sql).await;
        sqlx4k_postgresql_result_of(result).leak()
    }

    async fn cn_acquire(&self) -> *mut Sqlx4kPostgresResult {
        let cn = self.pool.acquire().await;
        let cn: PoolConnection<Postgres> = match cn {
            Ok(cn) => cn,
            Err(err) => return sqlx4k_postgres_error_result_of(err).leak(),
        };

        let cn = Box::new(cn);
        let cn = Box::leak(cn);
        let result = Sqlx4kPostgresResult {
            cn: cn as *mut _ as *mut c_void,
            ..Default::default()
        };
        result.leak()
    }

    async fn cn_release(&self, cn: Sqlx4kPostgresPtr) -> *mut Sqlx4kPostgresResult {
        if cn.ptr.is_null() {
            return Sqlx4kPostgresResult::default().leak();
        }

        let cn_ptr = cn.ptr as *mut PoolConnection<Postgres>;
        unsafe {
            // Recreate the Box and drop it, returning connection to pool
            let _boxed: Box<PoolConnection<Postgres>> = Box::from_raw(cn_ptr);
        }

        Sqlx4kPostgresResult::default().leak()
    }

    async fn cn_query(&self, cn: Sqlx4kPostgresPtr, sql: String) -> *mut Sqlx4kPostgresResult {
        let cn = unsafe { &mut *(cn.ptr as *mut PoolConnection<Postgres>) };
        let result = cn.execute(AssertSqlSafe(sql)).await;
        let result = match result {
            Ok(res) => Sqlx4kPostgresResult {
                rows_affected: res.rows_affected(),
                ..Default::default()
            },
            Err(err) => sqlx4k_postgres_error_result_of(err),
        };
        result.leak()
    }

    async fn cn_fetch_all(&self, cn: Sqlx4kPostgresPtr, sql: String) -> *mut Sqlx4kPostgresResult {
        let cn = unsafe { &mut *(cn.ptr as *mut PoolConnection<Postgres>) };
        let result = fetch_last(&mut **cn, sql).await;
        sqlx4k_postgresql_result_of(result).leak()
    }

    async fn cn_tx_begin(&self, cn: Sqlx4kPostgresPtr) -> *mut Sqlx4kPostgresResult {
        let cn = unsafe { &mut *(cn.ptr as *mut PoolConnection<Postgres>) };
        let tx = cn.begin().await;
        let tx = match tx {
            Ok(tx) => tx,
            Err(err) => {
                return sqlx4k_postgres_error_result_of(err).leak();
            }
        };

        let tx = Box::new(tx);
        let tx = Box::leak(tx);

        let result = Sqlx4kPostgresResult {
            tx: tx as *mut _ as *mut c_void,
            ..Default::default()
        };
        result.leak()
    }

    async fn tx_begin(&self) -> *mut Sqlx4kPostgresResult {
        let tx = self.pool.begin().await;
        let tx = match tx {
            Ok(tx) => tx,
            Err(err) => {
                return sqlx4k_postgres_error_result_of(err).leak();
            }
        };

        let tx = Box::new(tx);
        let tx = Box::leak(tx);
        let result = Sqlx4kPostgresResult {
            tx: tx as *mut _ as *mut c_void,
            ..Default::default()
        };
        result.leak()
    }

    async fn tx_commit(&self, tx: Sqlx4kPostgresPtr) -> *mut Sqlx4kPostgresResult {
        let tx = unsafe { *Box::from_raw(tx.ptr as *mut Transaction<'_, Postgres>) };
        let result = match tx.commit().await {
            Ok(_) => Sqlx4kPostgresResult::default(),
            Err(err) => sqlx4k_postgres_error_result_of(err),
        };
        result.leak()
    }

    async fn tx_rollback(&self, tx: Sqlx4kPostgresPtr) -> *mut Sqlx4kPostgresResult {
        let tx = unsafe { *Box::from_raw(tx.ptr as *mut Transaction<'_, Postgres>) };
        let result = match tx.rollback().await {
            Ok(_) => Sqlx4kPostgresResult::default(),
            Err(err) => sqlx4k_postgres_error_result_of(err),
        };
        result.leak()
    }

    async fn tx_query(&self, tx: Sqlx4kPostgresPtr, sql: String) -> *mut Sqlx4kPostgresResult {
        let mut tx = unsafe { *Box::from_raw(tx.ptr as *mut Transaction<'_, Postgres>) };
        let result = tx.execute(AssertSqlSafe(sql)).await;
        let tx = Box::new(tx);
        let tx = Box::into_raw(tx);
        let result = match result {
            Ok(res) => Sqlx4kPostgresResult {
                rows_affected: res.rows_affected(),
                ..Default::default()
            },
            Err(err) => sqlx4k_postgres_error_result_of(err),
        };
        let result = Sqlx4kPostgresResult {
            tx: tx as *mut c_void,
            ..result
        };
        result.leak()
    }

    async fn tx_fetch_all(&self, tx: Sqlx4kPostgresPtr, sql: String) -> *mut Sqlx4kPostgresResult {
        let mut tx = unsafe { *Box::from_raw(tx.ptr as *mut Transaction<'_, Postgres>) };
        let result = fetch_last(&mut *tx, sql).await;
        let tx = Box::new(tx);
        let tx = Box::into_raw(tx);
        let result = sqlx4k_postgresql_result_of(result);
        let result = Sqlx4kPostgresResult {
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
    ) -> *mut Sqlx4kPostgresResult {
        let mut cn: PoolConnection<Postgres> = match self.pool.acquire().await {
            Ok(cn) => cn,
            Err(err) => return sqlx4k_postgres_error_result_of(err).leak(),
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
        stream_result_of(Sqlx4kPostgresStream {
            rx: tokio::sync::Mutex::new(rx),
            cancel,
            task,
        })
    }

    async fn cn_stream_open(
        &self,
        cn: Sqlx4kPostgresPtr,
        sql: String,
        params: Vec<OwnedParam>,
        fetch_size: usize,
    ) -> *mut Sqlx4kPostgresResult {
        let (tx, rx) = mpsc::channel(STREAM_CHANNEL_CAPACITY);
        let (cancel, cancel_rx) = oneshot::channel();
        let task = RUNTIME.get().unwrap().spawn(async move {
            // The connection stays with Kotlin, which serializes its use and closes this stream
            // before any other statement; a cancelled stream leaves sqlx to drain the remaining
            // rows on the connection's next use.
            let cn = unsafe { &mut *(cn.raw() as *mut PoolConnection<Postgres>) };
            let rows = stream_rows_of(&mut **cn, sql, params);
            stream_rows(rows, fetch_size, tx, cancel_rx).await;
        });
        stream_result_of(Sqlx4kPostgresStream {
            rx: tokio::sync::Mutex::new(rx),
            cancel,
            task,
        })
    }

    async fn tx_stream_open(
        &self,
        tx: Sqlx4kPostgresPtr,
        sql: String,
        params: Vec<OwnedParam>,
        fetch_size: usize,
    ) -> *mut Sqlx4kPostgresResult {
        let (chunks, rx) = mpsc::channel(STREAM_CHANNEL_CAPACITY);
        let (cancel, cancel_rx) = oneshot::channel();
        let task = RUNTIME.get().unwrap().spawn(async move {
            // Borrowed in place (not re-boxed like `tx_fetch_all`), so the Kotlin-held pointer stays valid.
            let tx = unsafe { &mut *(tx.raw() as *mut Transaction<'static, Postgres>) };
            let rows = stream_rows_of(&mut **tx, sql, params);
            stream_rows(rows, fetch_size, chunks, cancel_rx).await;
        });
        stream_result_of(Sqlx4kPostgresStream {
            rx: tokio::sync::Mutex::new(rx),
            cancel,
            task,
        })
    }

    async fn stream_next(&self, stream: Sqlx4kPostgresPtr) -> *mut Sqlx4kPostgresResult {
        let stream = unsafe { &*(stream.ptr as *const Sqlx4kPostgresStream) };
        let mut rx = stream.rx.lock().await;
        match rx.recv().await {
            Some(chunk) => sqlx4k_postgresql_result_of(chunk).leak(),
            // End of the stream: an empty result (size 0, no schema).
            None => Sqlx4kPostgresResult::default().leak(),
        }
    }

    async fn stream_close(&self, stream: Sqlx4kPostgresPtr) -> *mut Sqlx4kPostgresResult {
        let stream = unsafe { Box::from_raw(stream.ptr as *mut Sqlx4kPostgresStream) };
        let Sqlx4kPostgresStream { rx, cancel, task } = *stream;
        // Stop the producer (dropping the sender fires its cancel branch, dropping the receiver fails
        // its next send) and wait for it: only then is the connection it borrowed free again.
        drop(cancel);
        drop(rx);
        let _ = task.await;
        Sqlx4kPostgresResult::default().leak()
    }

    async fn close(&self) -> *mut Sqlx4kPostgresResult {
        close_pool(&self.pool).await;
        Sqlx4kPostgresResult::default().leak()
    }

    async fn query_with_params(
        &self,
        sql: String,
        params: Vec<OwnedParam>,
    ) -> *mut Sqlx4kPostgresResult {
        let q = bind_params(sqlx::query::<Postgres>(AssertSqlSafe(sql)), params);
        let result = q.execute(&self.pool).await;
        let result = match result {
            Ok(res) => Sqlx4kPostgresResult {
                rows_affected: res.rows_affected(),
                ..Default::default()
            },
            Err(err) => sqlx4k_postgres_error_result_of(err),
        };
        result.leak()
    }

    async fn fetch_all_with_params(
        &self,
        sql: String,
        params: Vec<OwnedParam>,
    ) -> *mut Sqlx4kPostgresResult {
        let q = bind_params(sqlx::query::<Postgres>(AssertSqlSafe(sql)), params);
        let result = q.fetch_all(&self.pool).await;
        sqlx4k_postgresql_result_of(result).leak()
    }

    async fn cn_query_with_params(
        &self,
        cn: Sqlx4kPostgresPtr,
        sql: String,
        params: Vec<OwnedParam>,
    ) -> *mut Sqlx4kPostgresResult {
        let cn = unsafe { &mut *(cn.ptr as *mut PoolConnection<Postgres>) };
        let q = bind_params(sqlx::query::<Postgres>(AssertSqlSafe(sql)), params);
        let result = q.execute(&mut **cn).await;
        let result = match result {
            Ok(res) => Sqlx4kPostgresResult {
                rows_affected: res.rows_affected(),
                ..Default::default()
            },
            Err(err) => sqlx4k_postgres_error_result_of(err),
        };
        result.leak()
    }

    async fn cn_fetch_all_with_params(
        &self,
        cn: Sqlx4kPostgresPtr,
        sql: String,
        params: Vec<OwnedParam>,
    ) -> *mut Sqlx4kPostgresResult {
        let cn = unsafe { &mut *(cn.ptr as *mut PoolConnection<Postgres>) };
        let q = bind_params(sqlx::query::<Postgres>(AssertSqlSafe(sql)), params);
        let result = q.fetch_all(&mut **cn).await;
        sqlx4k_postgresql_result_of(result).leak()
    }

    async fn tx_query_with_params(
        &self,
        tx: Sqlx4kPostgresPtr,
        sql: String,
        params: Vec<OwnedParam>,
    ) -> *mut Sqlx4kPostgresResult {
        let mut tx = unsafe { *Box::from_raw(tx.ptr as *mut Transaction<'_, Postgres>) };
        let q = bind_params(sqlx::query::<Postgres>(AssertSqlSafe(sql)), params);
        let result = q.execute(&mut *tx).await;
        let tx = Box::new(tx);
        let tx = Box::into_raw(tx);
        let result = match result {
            Ok(res) => Sqlx4kPostgresResult {
                rows_affected: res.rows_affected(),
                ..Default::default()
            },
            Err(err) => sqlx4k_postgres_error_result_of(err),
        };
        let result = Sqlx4kPostgresResult {
            tx: tx as *mut c_void,
            ..result
        };
        result.leak()
    }

    async fn tx_fetch_all_with_params(
        &self,
        tx: Sqlx4kPostgresPtr,
        sql: String,
        params: Vec<OwnedParam>,
    ) -> *mut Sqlx4kPostgresResult {
        let mut tx = unsafe { *Box::from_raw(tx.ptr as *mut Transaction<'_, Postgres>) };
        let q = bind_params(sqlx::query::<Postgres>(AssertSqlSafe(sql)), params);
        let result = q.fetch_all(&mut *tx).await;
        let tx = Box::new(tx);
        let tx = Box::into_raw(tx);
        let result = sqlx4k_postgresql_result_of(result);
        let result = Sqlx4kPostgresResult {
            tx: tx as *mut c_void,
            ..result
        };
        result.leak()
    }
}

#[no_mangle]
pub extern "C" fn sqlx4k_postgresql_of(
    url: *const c_char,
    username: *const c_char,
    password: *const c_char,
    min_connections: c_int,
    max_connections: c_int,
    acquire_timeout_milis: c_int,
    idle_timeout_milis: c_int,
    max_lifetime_milis: c_int,
) -> *mut Sqlx4kPostgresResult {
    let url = c_chars_to_str_postgres(url);
    let username = c_chars_to_str_postgres(username);
    let password = c_chars_to_str_postgres(password);
    // A malformed URL is reported to the caller (panic = "abort" would take the process down).
    let options: PgConnectOptions = match url.parse() {
        Ok(options) => options,
        Err(err) => return sqlx4k_postgres_error_result_of(err).leak(),
    };
    let options = options.username(username).password(password);

    // Create the tokio runtime, once: get_or_init also when two pools are created concurrently (checking and then
    // setting raced, and the losing set().unwrap() panicked).
    let runtime = RUNTIME.get_or_init(|| Runtime::new().expect("failed to create the tokio runtime"));

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

    let pool = pool.connect_with(options.clone());

    // Create the pool here.
    let pool = runtime.block_on(pool);
    let pool: PgPool = match pool {
        Ok(pool) => pool,
        Err(err) => return sqlx4k_postgres_error_result_of(err).leak(),
    };
    let sqlx4k = Sqlx4kPostgreSql {
        connect_options: options,
        pool,
    };
    let sqlx4k = Box::new(sqlx4k);
    let sqlx4k = Box::leak(sqlx4k);

    Sqlx4kPostgresResult {
        rt: sqlx4k as *mut _ as *mut c_void,
        ..Default::default()
    }
    .leak()
}

#[no_mangle]
pub extern "C" fn sqlx4k_postgresql_pool_size(rt: *mut c_void) -> c_int {
    let sqlx4k = unsafe { &*(rt as *mut Sqlx4kPostgreSql) };
    sqlx4k.pool.size() as c_int
}

#[no_mangle]
pub extern "C" fn sqlx4k_postgresql_pool_idle_size(rt: *mut c_void) -> c_int {
    let sqlx4k = unsafe { &*(rt as *mut Sqlx4kPostgreSql) };
    sqlx4k.pool.num_idle() as c_int
}

#[no_mangle]
pub extern "C" fn sqlx4k_postgresql_close(
    rt: *mut c_void,
    callback: *mut c_void,
    fun: extern "C" fn(Sqlx4kPostgresPtr, *mut Sqlx4kPostgresResult),
) {
    let callback = Sqlx4kPostgresPtr { ptr: callback };
    let runtime = RUNTIME.get().unwrap();
    let sqlx4k = unsafe { &*(rt as *mut Sqlx4kPostgreSql) };
    runtime.spawn(async move {
        let result = sqlx4k.close().await;
        fun(callback, result)
    });
}

#[no_mangle]
pub extern "C" fn sqlx4k_postgresql_query(
    rt: *mut c_void,
    sql: *const c_char,
    callback: *mut c_void,
    fun: extern "C" fn(Sqlx4kPostgresPtr, *mut Sqlx4kPostgresResult),
) {
    let callback = Sqlx4kPostgresPtr { ptr: callback };
    let sql = c_chars_to_str_postgres(sql).to_owned();
    let runtime = RUNTIME.get().unwrap();
    let sqlx4k = unsafe { &*(rt as *mut Sqlx4kPostgreSql) };
    runtime.spawn(async move {
        let result = sqlx4k.query(sql).await;
        fun(callback, result)
    });
}

#[no_mangle]
pub extern "C" fn sqlx4k_postgresql_fetch_all(
    rt: *mut c_void,
    sql: *const c_char,
    callback: *mut c_void,
    fun: extern "C" fn(Sqlx4kPostgresPtr, *mut Sqlx4kPostgresResult),
) {
    let callback = Sqlx4kPostgresPtr { ptr: callback };
    let sql = c_chars_to_str_postgres(sql).to_owned();
    let runtime = RUNTIME.get().unwrap();
    let sqlx4k = unsafe { &*(rt as *mut Sqlx4kPostgreSql) };
    runtime.spawn(async move {
        let result = sqlx4k.fetch_all(sql).await;
        fun(callback, result)
    });
}

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

#[no_mangle]
pub extern "C" fn sqlx4k_postgresql_cn_acquire(
    rt: *mut c_void,
    callback: *mut c_void,
    fun: extern "C" fn(Sqlx4kPostgresPtr, *mut Sqlx4kPostgresResult),
) {
    let callback = Sqlx4kPostgresPtr { ptr: callback };
    let runtime = RUNTIME.get().unwrap();
    let sqlx4k = unsafe { &*(rt as *mut Sqlx4kPostgreSql) };
    runtime.spawn(async move {
        let result = sqlx4k.cn_acquire().await;
        fun(callback, result)
    });
}

#[no_mangle]
pub extern "C" fn sqlx4k_postgresql_cn_release(
    rt: *mut c_void,
    cn: *mut c_void,
    callback: *mut c_void,
    fun: extern "C" fn(Sqlx4kPostgresPtr, *mut Sqlx4kPostgresResult),
) {
    let cn = Sqlx4kPostgresPtr { ptr: cn };
    let callback = Sqlx4kPostgresPtr { ptr: callback };
    let runtime = RUNTIME.get().unwrap();
    let sqlx4k = unsafe { &*(rt as *mut Sqlx4kPostgreSql) };
    runtime.spawn(async move {
        let result = sqlx4k.cn_release(cn).await;
        fun(callback, result)
    });
}

#[no_mangle]
pub extern "C" fn sqlx4k_postgresql_cn_query(
    rt: *mut c_void,
    cn: *mut c_void,
    sql: *const c_char,
    callback: *mut c_void,
    fun: extern "C" fn(Sqlx4kPostgresPtr, *mut Sqlx4kPostgresResult),
) {
    let cn = Sqlx4kPostgresPtr { ptr: cn };
    let callback = Sqlx4kPostgresPtr { ptr: callback };
    let sql = c_chars_to_str_postgres(sql).to_owned();
    let runtime = RUNTIME.get().unwrap();
    let sqlx4k = unsafe { &*(rt as *mut Sqlx4kPostgreSql) };
    runtime.spawn(async move {
        let result = sqlx4k.cn_query(cn, sql).await;
        fun(callback, result)
    });
}

#[no_mangle]
pub extern "C" fn sqlx4k_postgresql_cn_fetch_all(
    rt: *mut c_void,
    cn: *mut c_void,
    sql: *const c_char,
    callback: *mut c_void,
    fun: extern "C" fn(Sqlx4kPostgresPtr, *mut Sqlx4kPostgresResult),
) {
    let cn = Sqlx4kPostgresPtr { ptr: cn };
    let callback = Sqlx4kPostgresPtr { ptr: callback };
    let sql = c_chars_to_str_postgres(sql).to_owned();
    let runtime = RUNTIME.get().unwrap();
    let sqlx4k = unsafe { &*(rt as *mut Sqlx4kPostgreSql) };
    runtime.spawn(async move {
        let result = sqlx4k.cn_fetch_all(cn, sql).await;
        fun(callback, result)
    });
}

#[no_mangle]
pub extern "C" fn sqlx4k_postgresql_cn_tx_begin(
    rt: *mut c_void,
    cn: *mut c_void,
    callback: *mut c_void,
    fun: extern "C" fn(Sqlx4kPostgresPtr, *mut Sqlx4kPostgresResult),
) {
    let cn = Sqlx4kPostgresPtr { ptr: cn };
    let callback = Sqlx4kPostgresPtr { ptr: callback };
    let runtime = RUNTIME.get().unwrap();
    let sqlx4k = unsafe { &*(rt as *mut Sqlx4kPostgreSql) };
    runtime.spawn(async move {
        let result = sqlx4k.cn_tx_begin(cn).await;
        fun(callback, result)
    });
}

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
pub extern "C" fn sqlx4k_postgresql_listen(
    rt: *mut c_void,
    channels: *const c_char,
    notify_id: c_int,
    notify: extern "C" fn(c_int, *mut Sqlx4kPostgresResult),
    callback: *mut c_void,
    fun: extern "C" fn(Sqlx4kPostgresPtr, *mut Sqlx4kPostgresResult),
) {
    let callback = Sqlx4kPostgresPtr { ptr: callback };
    let channels = c_chars_to_str_postgres(channels).to_owned();
    let runtime = RUNTIME.get().unwrap();
    let sqlx4k = unsafe { &*(rt as *mut Sqlx4kPostgreSql) };
    // Registered before the task starts, so that sqlx4k_postgresql_unlisten finds it as soon as listen returns.
    let (stop_tx, mut stop) = oneshot::channel::<()>();
    let (stopped, stopped_rx) = oneshot::channel::<()>();
    listeners().lock().unwrap().insert(notify_id, (stop_tx, stopped_rx));
    runtime.spawn(async move {
        // A pool of 1 without timeouts (as they don't apply here), only used to handle re-connections.
        let options = sqlx4k.pool.options().clone().max_connections(1).min_connections(0);
        let listener = async {
            let pool = options.connect_with(sqlx4k.connect_options.clone()).await?;
            let mut listener = PgListener::connect_with(&pool).await?;
            // We don't need to handle close events
            listener.ignore_pool_close_event(true);
            let channels: Vec<&str> = channels.split(',').collect();
            listener.listen_all(channels).await?;
            Ok::<_, Error>((pool, listener))
        }
        .await;
        let (pool, mut listener) = match listener {
            Ok(listener) => listener,
            // Report a failure to start listening (database down, too many clients, ...) instead of aborting.
            Err(err) => return fun(callback, sqlx4k_postgres_error_result_of(err).leak()),
        };

        // Return OK as soon as the stream is ready.
        let result = Sqlx4kPostgresResult::default().leak();
        fun(callback, result);

        // Until stopped by sqlx4k_postgresql_unlisten (a dropped sender stops it too).
        let mut backoff = LISTEN_MIN_BACKOFF;
        loop {
            let stop_now = tokio::select! {
                _ = &mut stop => true,
                received = listener.try_recv() => {
                    match received {
                        Ok(Some(item)) => {
                            backoff = LISTEN_MIN_BACKOFF;
                            let result = sqlx4k_postgresql_result_of_pg_notification(item).leak();
                            notify(notify_id, result);
                            false
                        }
                        // The connection was lost: the next call reconnects and listens again.
                        Ok(None) => false,
                        // The connection failed or could not be re-established (server restart, terminated backend,
                        // database down): retry with a backoff instead of aborting the process.
                        Err(_) => {
                            let stop_now = tokio::select! {
                                _ = &mut stop => true,
                                _ = tokio::time::sleep(backoff) => false,
                            };
                            backoff = (backoff * 2).min(LISTEN_MAX_BACKOFF);
                            stop_now
                        }
                    }
                }
            };
            if stop_now {
                break;
            }
        }

        // Stopped: release the listener's connection.
        drop(listener);
        pool.close().await;
        let _ = stopped.send(());
    });
}

/// The listeners started by sqlx4k_postgresql_listen, by notify id: the sender that stops one, and the receiver
/// notified once it has stopped and closed its connection.
type ListenerControl = (oneshot::Sender<()>, oneshot::Receiver<()>);

fn listeners() -> &'static std::sync::Mutex<std::collections::HashMap<c_int, ListenerControl>> {
    static LISTENERS: OnceLock<std::sync::Mutex<std::collections::HashMap<c_int, ListenerControl>>> = OnceLock::new();
    LISTENERS.get_or_init(Default::default)
}

/// Stops the listener started with `notify_id` and closes its connection; completes once it has (and at once for an
/// unknown id).
#[no_mangle]
pub extern "C" fn sqlx4k_postgresql_unlisten(
    notify_id: c_int,
    callback: *mut c_void,
    fun: extern "C" fn(Sqlx4kPostgresPtr, *mut Sqlx4kPostgresResult),
) {
    let callback = Sqlx4kPostgresPtr { ptr: callback };
    let runtime = RUNTIME.get().unwrap();
    let control = listeners().lock().unwrap().remove(&notify_id);
    runtime.spawn(async move {
        if let Some((stop, stopped)) = control {
            let _ = stop.send(());
            // An error means the task already ended (it failed to start listening).
            let _ = stopped.await;
        }
        fun(callback, Sqlx4kPostgresResult::default().leak())
    });
}

/// Backoff between LISTEN reconnect attempts.
const LISTEN_MIN_BACKOFF: Duration = Duration::from_millis(100);
const LISTEN_MAX_BACKOFF: Duration = Duration::from_secs(30);

fn sqlx4k_postgresql_result_of_pg_notification(item: PgNotification) -> Sqlx4kPostgresResult {
    let column = Sqlx4kPostgresSchemaColumn {
        ordinal: 0,
        name: CString::new(item.channel()).unwrap().into_raw(),
        kind: CString::new("TEXT").unwrap().into_raw(),
    };
    let columns = vec![column];
    let columns: Box<[Sqlx4kPostgresSchemaColumn]> = columns.into_boxed_slice();
    let columns: &mut [Sqlx4kPostgresSchemaColumn] = Box::leak(columns);
    let columns: *mut Sqlx4kPostgresSchemaColumn = columns.as_mut_ptr();
    let schema = Sqlx4kPostgresSchema { size: 1, columns };
    let schema = Box::new(schema);
    let schema = Box::leak(schema);

    let column = Sqlx4kPostgresColumn {
        ordinal: 0,
        value: CString::new(item.payload()).unwrap().into_raw(),
    };

    let columns = vec![column];
    let columns: Box<[Sqlx4kPostgresColumn]> = columns.into_boxed_slice();
    let columns: &mut [Sqlx4kPostgresColumn] = Box::leak(columns);
    let columns: *mut Sqlx4kPostgresColumn = columns.as_mut_ptr();

    let row = Sqlx4kPostgresRow { size: 1, columns };
    let rows = vec![row];
    let rows: Box<[Sqlx4kPostgresRow]> = rows.into_boxed_slice();
    let rows: &mut [Sqlx4kPostgresRow] = Box::leak(rows);
    let rows: *mut Sqlx4kPostgresRow = rows.as_mut_ptr();

    Sqlx4kPostgresResult {
        schema,
        size: 1,
        rows,
        ..Default::default()
    }
}

/// The rows of the last statement of `sql`, as the JVM driver returns them. A simple query may hold several
/// statements, whose rows (with columns of their own) fetch_all would return one after the other: the result
/// describes the columns of a single row set.
async fn fetch_last<'e, E>(executor: E, sql: String) -> Result<Vec<PgRow>, sqlx::Error>
where
    E: Executor<'e, Database = Postgres>,
{
    let mut items = executor.fetch_many(AssertSqlSafe(sql));
    let mut current = Vec::new();
    let mut last = Vec::new();
    while let Some(item) = items.try_next().await? {
        match item {
            // The end of a statement.
            sqlx::Either::Left(_) => last = std::mem::take(&mut current),
            sqlx::Either::Right(row) => current.push(row),
        }
    }
    Ok(if current.is_empty() { last } else { current })
}

fn sqlx4k_postgresql_result_of(result: Result<Vec<PgRow>, sqlx::Error>) -> Sqlx4kPostgresResult {
    match result {
        Ok(rows) => {
            let schema: Sqlx4kPostgresSchema = if rows.len() > 0 {
                sqlx4k_postgresql_schema_of(rows.get(0).unwrap())
            } else {
                Sqlx4kPostgresSchema::default()
            };

            let schema = Box::new(schema);
            let schema = Box::leak(schema);

            let rows: Vec<Sqlx4kPostgresRow> =
                rows.iter().map(|r| sqlx4k_postgresql_row_of(r)).collect();
            let size = rows.len();
            let rows: Box<[Sqlx4kPostgresRow]> = rows.into_boxed_slice();
            let rows: &mut [Sqlx4kPostgresRow] = Box::leak(rows);
            let rows: *mut Sqlx4kPostgresRow = rows.as_mut_ptr();

            Sqlx4kPostgresResult {
                schema,
                size: size as c_int,
                rows,
                ..Default::default()
            }
        }
        Err(err) => sqlx4k_postgres_error_result_of(err),
    }
}

fn sqlx4k_postgresql_schema_of(row: &PgRow) -> Sqlx4kPostgresSchema {
    let columns = row.columns();
    if columns.is_empty() {
        Sqlx4kPostgresSchema::default()
    } else {
        let columns: Vec<Sqlx4kPostgresSchemaColumn> = row
            .columns()
            .iter()
            .map(|c| {
                let name: &str = c.name();
                let value_ref: PgValueRef = row.try_get_raw(c.ordinal()).unwrap();
                let info: std::borrow::Cow<PgTypeInfo> = value_ref.type_info();
                let kind: &str = info.name();
                Sqlx4kPostgresSchemaColumn {
                    ordinal: c.ordinal() as c_int,
                    name: CString::new(name).unwrap().into_raw(),
                    kind: CString::new(kind).unwrap().into_raw(),
                }
            })
            .collect();

        let size = columns.len();
        let columns: Box<[Sqlx4kPostgresSchemaColumn]> = columns.into_boxed_slice();
        let columns: &mut [Sqlx4kPostgresSchemaColumn] = Box::leak(columns);
        let columns: *mut Sqlx4kPostgresSchemaColumn = columns.as_mut_ptr();

        Sqlx4kPostgresSchema {
            size: size as c_int,
            columns,
        }
    }
}

/// Decodes a single Postgres column value into a heap-allocated C string.
///
/// `row.get_unchecked::<&str>` only works reliably when the result is in **text**
/// format (the simple-query protocol used by `pool.execute(sql)`). The `_with_params`
/// path goes through sqlx's prepared/extended protocol, which returns column values
/// in **binary** format — `&str` decode then either fails on non-UTF8 bytes or
/// silently produces strings full of control characters / embedded NULs that crash
/// `CString::new`. Decode by type so both protocols round-trip identically through
/// the existing Kotlin `as*` extensions, which expect text-shaped values.
fn decode_postgresql_column_value(row: &PgRow, ordinal: usize) -> *mut c_char {
    let type_name: String = {
        let value_ref = row.try_get_raw(ordinal).unwrap();
        if value_ref.is_null() {
            return null_mut();
        }
        value_ref.type_info().name().to_string()
    };

    let s: String = match type_name.as_str() {
        "INT2" => row.get_unchecked::<i16, _>(ordinal).to_string(),
        "INT4" => row.get_unchecked::<i32, _>(ordinal).to_string(),
        "INT8" => row.get_unchecked::<i64, _>(ordinal).to_string(),
        "FLOAT4" => float_text(row.get_unchecked::<f32, _>(ordinal)),
        "FLOAT8" => float_text(row.get_unchecked::<f64, _>(ordinal)),
        "BOOL" => row.get_unchecked::<bool, _>(ordinal).to_string(),
        // Match the bytea text-input format `\xHH..` so the existing
        // `asByteArray()` decoder (which strips `\x` and parses hex) round-trips.
        // Use `Vec<u8>` not `&[u8]` — sqlx-rs only supports `&[u8]` decode in
        // the prepared-statement protocol, while simple-query results require
        // an owned buffer. sqlx reads only the hex text form: with `bytea_output = 'escape'` (a session, role or
        // database setting) the value is decoded here instead of failing, which would abort the process.
        "BYTEA" => decode_or(
            row,
            ordinal,
            |bytes: Vec<u8>| format!("\\x{}", hex::encode(bytes)),
            || {
                let text = decode_text_or_binary(row, ordinal, |_| None);
                decode_bytea_escape(&text).map_or(text, |bytes| format!("\\x{}", hex::encode(bytes)))
            },
        ),
        "UUID" => row.get_unchecked::<uuid::Uuid, _>(ordinal).to_string(),
        // Dates, times and timestamps have values chrono cannot represent ('infinity', '24:00:00'): see decode_datetime.
        "DATE" => decode_datetime(row, ordinal, |d: chrono::NaiveDate| d.to_string(), decode_pg_date_binary),
        "TIME" => decode_datetime(row, ordinal, |t: chrono::NaiveTime| t.to_string(), decode_pg_time_binary),
        "TIMESTAMP" => decode_datetime(
            row,
            ordinal,
            |dt: chrono::NaiveDateTime| dt.format("%Y-%m-%d %H:%M:%S%.6f").to_string(),
            decode_pg_timestamp_binary,
        ),
        "TIMESTAMPTZ" => decode_datetime(
            row,
            ordinal,
            |dt: chrono::DateTime<chrono::Utc>| dt.format("%Y-%m-%d %H:%M:%S%.6f").to_string(),
            decode_pg_timestamp_binary,
        ),
        // Array types — render with postgres' `{a,b,c}` text form so the existing
        // `asString()` path round-trips. Element formatting mirrors `T::to_string()`,
        // so this only handles arrays of element types we can decode here. Elements may
        // be NULL (rendered `NULL`, as postgres does); an array sqlx cannot decode (a lower
        // bound other than 1, an infinite timestamp, ...) falls back as in decode_or.
        "BOOL[]" => decode_or(
            row,
            ordinal,
            // Postgres' canonical text encoding for bool arrays is `{t,f}` and
            // sqlx4k's `asBooleanArray()` parses that form (via `asBoolean`).
            |v: Vec<Option<bool>>| {
                format_pg_array_fmt(v.iter(), |b| match b {
                    Some(true) => "t".to_string(),
                    Some(false) => "f".to_string(),
                    None => "NULL".to_string(),
                })
            },
            || decode_text_or_binary(row, ordinal, |_| None),
        ),
        "INT2[]" => decode_array::<i16>(row, ordinal),
        "INT4[]" => decode_array::<i32>(row, ordinal),
        "INT8[]" => decode_array::<i64>(row, ordinal),
        "FLOAT4[]" => decode_float_array::<f32>(row, ordinal),
        "FLOAT8[]" => decode_float_array::<f64>(row, ordinal),
        // Text-like array types, quoted as PostgreSQL writes them (`{a,"b,c"}`).
        "TEXT[]" | "VARCHAR[]" | "BPCHAR[]" | "NAME[]" => decode_text_array(row, ordinal),
        "UUID[]" => decode_array::<uuid::Uuid>(row, ordinal),
        "TIMESTAMP[]" => decode_datetime(
            row,
            ordinal,
            |v: Vec<Option<chrono::NaiveDateTime>>| {
                format_pg_nullable_array_fmt(v.iter(), |dt| dt.format("%Y-%m-%d %H:%M:%S%.6f").to_string())
            },
            |bytes| decode_pg_array_binary(bytes, decode_pg_timestamp_binary),
        ),
        "TIMESTAMPTZ[]" => decode_datetime(
            row,
            ordinal,
            |v: Vec<Option<chrono::DateTime<chrono::Utc>>>| {
                format_pg_nullable_array_fmt(v.iter(), |dt| dt.format("%Y-%m-%d %H:%M:%S%.6f").to_string())
            },
            |bytes| decode_pg_array_binary(bytes, decode_pg_timestamp_binary),
        ),
        "BYTEA[]" => decode_or(
            row,
            ordinal,
            // Each element gets the bytea text form (`\xHH..`) so a downstream
            // `asStringArray().map { it.asByteArray() }` round-trips per element.
            |v: Vec<Option<Vec<u8>>>| format_pg_nullable_array_fmt(v.iter(), |b| format!("\\x{}", hex::encode(b))),
            || decode_text_or_binary(row, ordinal, |_| None),
        ),
        // JSONB's binary wire format is a 1-byte version header (0x01) followed by the
        // canonical JSON text. Decoding as `&str` (the fallthrough below) would keep that
        // header byte, corrupting JSON parsing on the Kotlin side (e.g. `?{...}`), so strip
        // it here. In the simple-query / text protocol there is no header, so the strip is a
        // no-op. (Plain `JSON` is stored/sent as text and needs no adjustment.)
        "JSONB" => {
            let s = row.get_unchecked::<&str, _>(ordinal);
            s.strip_prefix('\u{1}').unwrap_or(s).to_string()
        }
        // NUMERIC arrives in binary form through the extended/prepared protocol
        // (every `_with_params` call, and aggregates such as SUM(bigint)/AVG(..)
        // return NUMERIC). Decoding it as `&str` used to panic and abort the host
        // process; decode the wire format into its canonical decimal text instead.
        "NUMERIC" => decode_text_or_binary(row, ordinal, decode_pg_numeric_binary),
        // NUMERIC[] has the same problem: its binary form is an array of binary NUMERICs.
        // Render it as the `{1.5,NULL,NaN}` text PostgreSQL prints for the simple protocol.
        "NUMERIC[]" => decode_text_or_binary(row, ordinal, decode_pg_numeric_array_binary),
        // TEXT/VARCHAR/CHAR/NAME/JSON and anything else fall back to the raw bytes.
        // In the simple-query / text protocol every value arrives as text; in the
        // extended protocol a binary-only type we don't model must never take the
        // process down, so decode leniently instead of unwrapping.
        _ => decode_text_lossy(row, ordinal),
    };
    // A value may legally contain NUL (e.g. text in a non-UTF8-safe column after
    // lossy decoding); CString::new would fail on it. Strip rather than abort.
    let s = if s.as_bytes().contains(&0) { s.replace('\0', "") } else { s };
    CString::new(s).unwrap_or_default().into_raw()
}

/// Decodes the value as `T` and renders it with `render`. A value `T` cannot represent ('infinity', '24:00:00', an
/// array whose lower bound is not 1, ...) is rendered by `fallback` instead: `get_unchecked` would unwrap the decode
/// error, and with `panic = "abort"` a single such value took the host process down.
fn decode_or<'r, T: sqlx::Decode<'r, Postgres>>(
    row: &'r PgRow,
    ordinal: usize,
    render: impl FnOnce(T) -> String,
    fallback: impl FnOnce() -> String,
) -> String {
    match row.try_get_unchecked::<T, _>(ordinal) {
        Ok(value) => render(value),
        Err(_) => fallback(),
    }
}

/// Decodes an array of `T` (elements may be NULL) into postgres' `{a,NULL,c}` text form; see decode_or.
/// A float as PostgreSQL and Kotlin write it: Rust writes the infinities as `inf` and `-inf`, which Kotlin cannot
/// parse (`NaN` is the same in all three).
fn float_text(value: impl ToString) -> String {
    match value.to_string().as_str() {
        "inf" => "Infinity".to_string(),
        "-inf" => "-Infinity".to_string(),
        text => text.to_string(),
    }
}

/// A text element as PostgreSQL writes it in an array: quoted when it is empty, reads `NULL`, or holds a delimiter,
/// quote, backslash or whitespace (so that the Kotlin decoders read `{"a,b"}` back as one element).
fn pg_array_element(value: &str) -> String {
    let quote = value.is_empty()
        || value.eq_ignore_ascii_case("NULL")
        || value
            .chars()
            .any(|c| matches!(c, '{' | '}' | ',' | '"' | '\\') || c.is_whitespace());
    if !quote {
        return value.to_string();
    }
    let mut s = String::with_capacity(value.len() + 2);
    s.push('"');
    for c in value.chars() {
        if c == '"' || c == '\\' {
            s.push('\\');
        }
        s.push(c);
    }
    s.push('"');
    s
}

fn decode_text_array(row: &PgRow, ordinal: usize) -> String {
    // A text value (a plain query) is PostgreSQL's own output, already in this form; sqlx's parser of it reads the
    // string "NULL" (quoted) as a NULL element.
    if let Ok(value) = row.try_get_raw(ordinal) {
        if value.format() == PgValueFormat::Text {
            return value.as_str().map(str::to_string).unwrap_or_default();
        }
    }
    decode_or(
        row,
        ordinal,
        |v: Vec<Option<String>>| format_pg_nullable_array_fmt(v.iter(), |e| pg_array_element(e)),
        || decode_text_or_binary(row, ordinal, |_| None),
    )
}

fn decode_float_array<T>(row: &PgRow, ordinal: usize) -> String
where
    T: ToString + for<'r> sqlx::Decode<'r, Postgres> + Type<Postgres>,
{
    decode_or(
        row,
        ordinal,
        |v: Vec<Option<T>>| format_pg_nullable_array_fmt(v.iter(), |e| float_text(e.to_string())),
        || decode_text_or_binary(row, ordinal, |_| None),
    )
}

fn decode_array<T>(row: &PgRow, ordinal: usize) -> String
where
    T: ToString + for<'r> sqlx::Decode<'r, Postgres> + Type<Postgres>,
{
    decode_or(
        row,
        ordinal,
        |v: Vec<Option<T>>| format_pg_nullable_array_fmt(v.iter(), |e| e.to_string()),
        || decode_text_or_binary(row, ordinal, |_| None),
    )
}

/// Decodes a date/time value (or an array of them) without sqlx's binary decoders, which panic (overflowing chrono)
/// on values chrono cannot represent, such as 'infinity': a binary value is decoded by `binary`, with checked
/// arithmetic; a text value by sqlx, whose text parsing fails cleanly, and is passed through as is when it cannot be
/// represented ('infinity', '24:00:00').
fn decode_datetime<'r, T: sqlx::Decode<'r, Postgres>>(
    row: &'r PgRow,
    ordinal: usize,
    render: impl FnOnce(T) -> String,
    binary: fn(&[u8]) -> Option<String>,
) -> String {
    let Ok(value) = row.try_get_raw(ordinal) else { return String::new() };
    match value.format() {
        PgValueFormat::Binary => value.as_bytes().ok().and_then(binary).unwrap_or_default(),
        PgValueFormat::Text => decode_or(row, ordinal, render, || value.as_str().map(str::to_string).unwrap_or_default()),
    }
}

/// Days from 0001-01-01 (chrono's `num_days_from_ce` day 1) to 2000-01-01, the epoch of the binary DATE format.
const PG_EPOCH_DAYS_FROM_CE: i32 = 730_120;
/// Microseconds from 1970-01-01 to 2000-01-01, the epoch of the binary TIMESTAMP format.
const PG_EPOCH_UNIX_MICROS: i64 = 946_684_800_000_000;

/// Binary DATE (days since 2000-01-01, `i32::MAX` / `i32::MIN` for `±infinity`).
fn decode_pg_date_binary(bytes: &[u8]) -> Option<String> {
    let days = i32::from_be_bytes(bytes.try_into().ok()?);
    match days {
        i32::MAX => Some("infinity".to_string()),
        i32::MIN => Some("-infinity".to_string()),
        _ => Some(chrono::NaiveDate::from_num_days_from_ce_opt(days.checked_add(PG_EPOCH_DAYS_FROM_CE)?)?.to_string()),
    }
}

/// Binary TIMESTAMP / TIMESTAMPTZ (microseconds since 2000-01-01 UTC, `i64::MAX` / `i64::MIN` for `±infinity`).
fn decode_pg_timestamp_binary(bytes: &[u8]) -> Option<String> {
    let micros = i64::from_be_bytes(bytes.try_into().ok()?);
    match micros {
        i64::MAX => Some("infinity".to_string()),
        i64::MIN => Some("-infinity".to_string()),
        _ => {
            let dt = chrono::DateTime::from_timestamp_micros(micros.checked_add(PG_EPOCH_UNIX_MICROS)?)?;
            Some(dt.format("%Y-%m-%d %H:%M:%S%.6f").to_string())
        }
    }
}

/// Binary TIME (microseconds since midnight, up to and including 24:00:00, which chrono cannot represent).
fn decode_pg_time_binary(bytes: &[u8]) -> Option<String> {
    let micros = i64::from_be_bytes(bytes.try_into().ok()?);
    if !(0..=86_400_000_000).contains(&micros) {
        return None;
    }
    let (secs, frac) = (micros / 1_000_000, micros % 1_000_000);
    let hms = format!("{:02}:{:02}:{:02}", secs / 3600, secs % 3600 / 60, secs % 60);
    Some(if frac == 0 { hms } else { format!("{hms}.{frac:06}") })
}

/// Text format is passed through as is; binary format is rendered to the same text by `decode_binary`.
/// Decodes bytea's escape output format: `\\` is a backslash, `\ooo` an octal byte, anything else the byte itself.
fn decode_bytea_escape(text: &str) -> Option<Vec<u8>> {
    let bytes = text.as_bytes();
    let mut out = Vec::with_capacity(bytes.len());
    let mut i = 0;
    while i < bytes.len() {
        if bytes[i] != b'\\' {
            out.push(bytes[i]);
            i += 1;
        } else if bytes.get(i + 1) == Some(&b'\\') {
            out.push(b'\\');
            i += 2;
        } else {
            let octal = std::str::from_utf8(bytes.get(i + 1..i + 4)?).ok()?;
            out.push(u8::from_str_radix(octal, 8).ok()?);
            i += 4;
        }
    }
    Some(out)
}

fn decode_text_or_binary(row: &PgRow, ordinal: usize, decode_binary: fn(&[u8]) -> Option<String>) -> String {
    let Ok(value) = row.try_get_raw(ordinal) else { return String::new() };
    match value.format() {
        PgValueFormat::Text => value.as_str().map(str::to_string).unwrap_or_default(),
        PgValueFormat::Binary => value.as_bytes().ok().and_then(decode_binary).unwrap_or_default(),
    }
}

/// Decodes a binary NUMERIC[] (`array_send`):
/// `ndim: i32, has_nulls: i32, elemtype: u32, [len: i32, lbound: i32; ndim]`, followed by the
/// elements in row-major order, each as `len: i32` (-1 for NULL) and `len` bytes of `numeric_send`.
/// Renders the canonical text form (`{1.5,NULL}`, `{{1,2},{3,4}}`, `[0:1]={1,2}`).
pub(crate) fn decode_pg_numeric_array_binary(bytes: &[u8]) -> Option<String> {
    decode_pg_array_binary(bytes, decode_pg_numeric_binary)
}

/// Decodes a binary array (`array_send`, see decode_pg_numeric_array_binary) whose elements are decoded by
/// `decode_element`, into the canonical text form.
fn decode_pg_array_binary(bytes: &[u8], decode_element: fn(&[u8]) -> Option<String>) -> Option<String> {
    let read_i32 = |i: usize| -> Option<i32> { Some(i32::from_be_bytes(bytes.get(i..i + 4)?.try_into().ok()?)) };
    let ndim = read_i32(0)?;
    if ndim < 0 {
        return None;
    }
    if ndim == 0 {
        return Some("{}".to_string());
    }
    let mut dims = Vec::with_capacity(ndim as usize);
    let mut bounds = String::new();
    let mut default_bounds = true;
    let mut pos = 12;
    for _ in 0..ndim {
        let (len, lbound) = (read_i32(pos)?, read_i32(pos + 4)?);
        if len < 0 {
            return None;
        }
        dims.push(len as usize);
        default_bounds &= lbound == 1;
        bounds.push_str(&format!("[{}:{}]", lbound, lbound as i64 + len as i64 - 1));
        pos += 8;
    }
    // Like array_out, spell the bounds out only when some dimension does not start at 1.
    let mut out = if default_bounds { String::new() } else { bounds + "=" };
    write_pg_array_level(bytes, &mut pos, &dims, decode_element, &mut out)?;
    Some(out)
}

fn write_pg_array_level(
    bytes: &[u8],
    pos: &mut usize,
    dims: &[usize],
    decode_element: fn(&[u8]) -> Option<String>,
    out: &mut String,
) -> Option<()> {
    out.push('{');
    for i in 0..dims[0] {
        if i > 0 {
            out.push(',');
        }
        if dims.len() > 1 {
            write_pg_array_level(bytes, pos, &dims[1..], decode_element, out)?;
            continue;
        }
        let len = i32::from_be_bytes(bytes.get(*pos..*pos + 4)?.try_into().ok()?);
        *pos += 4;
        if len < 0 {
            out.push_str("NULL");
            continue;
        }
        let element = bytes.get(*pos..*pos + len as usize)?;
        *pos += len as usize;
        out.push_str(&decode_element(element)?);
    }
    out.push('}');
    Some(())
}

/// Decodes the PostgreSQL NUMERIC binary representation (`numeric_send`):
/// `ndigits: i16, weight: i16, sign: u16, dscale: u16, digits: [u16; ndigits]`,
/// where each digit is a base-10000 group and `weight` is the power of 10000 of
/// the first group. Returns `None` on malformed input.
pub(crate) fn decode_pg_numeric_binary(bytes: &[u8]) -> Option<String> {
    if bytes.len() < 8 {
        return None;
    }
    let read_u16 = |i: usize| u16::from_be_bytes([bytes[i], bytes[i + 1]]);
    let ndigits = read_u16(0) as i16;
    let weight = read_u16(2) as i16 as i32;
    let sign = read_u16(4);
    let dscale = read_u16(6) as usize;
    match sign {
        0xC000 => return Some("NaN".to_string()),
        0xD000 => return Some("Infinity".to_string()),
        0xF000 => return Some("-Infinity".to_string()),
        0x0000 | 0x4000 => {}
        _ => return None,
    }
    if ndigits < 0 || bytes.len() < 8 + ndigits as usize * 2 {
        return None;
    }
    let digits: Vec<u16> = (0..ndigits as usize).map(|i| read_u16(8 + i * 2)).collect();
    if digits.iter().any(|d| *d >= 10000) {
        return None;
    }
    // Integer part: groups with power >= 0 (weight, weight-1, ..., 0).
    let mut int_part = String::new();
    if weight >= 0 {
        for i in 0..=weight {
            let d = digits.get(i as usize).copied().unwrap_or(0);
            if int_part.is_empty() {
                if d != 0 {
                    int_part.push_str(&d.to_string());
                }
            } else {
                int_part.push_str(&format!("{d:04}"));
            }
        }
    }
    if int_part.is_empty() {
        int_part.push('0');
    }
    // Fraction part: groups with power < 0, padded/truncated to dscale digits.
    let mut frac = String::new();
    if dscale > 0 {
        let mut power = -1;
        while frac.len() < dscale {
            // digit i carries power (weight - i), so the group with this power is i = weight - power
            let group_index = weight - power;
            let d = if group_index >= 0 { digits.get(group_index as usize).copied().unwrap_or(0) } else { 0 };
            frac.push_str(&format!("{d:04}"));
            power -= 1;
        }
        frac.truncate(dscale);
    }
    let mut out = String::new();
    if sign == 0x4000 && (int_part != "0" || frac.chars().any(|c| c != '0')) {
        out.push('-');
    }
    out.push_str(&int_part);
    if dscale > 0 {
        out.push('.');
        out.push_str(&frac);
    }
    Some(out)
}

/// Text-ish fallback that never panics: UTF-8 when valid, lossy otherwise.
fn decode_text_lossy(row: &PgRow, ordinal: usize) -> String {
    match row.try_get_raw(ordinal) {
        Ok(value) => match value.as_bytes() {
            Ok(bytes) => String::from_utf8_lossy(bytes).into_owned(),
            Err(_) => String::new(),
        },
        Err(_) => String::new(),
    }
}

/// Like format_pg_array_fmt, for elements that may be NULL (rendered `NULL`, as postgres does).
fn format_pg_nullable_array_fmt<'a, T: 'a, I: Iterator<Item = &'a Option<T>>, F: FnMut(&'a T) -> String>(
    iter: I,
    mut format_one: F,
) -> String {
    format_pg_array_fmt(iter, |v| v.as_ref().map_or_else(|| "NULL".to_string(), &mut format_one))
}

fn format_pg_array_fmt<'a, T: 'a, I: Iterator<Item = &'a T>, F: FnMut(&'a T) -> String>(
    iter: I,
    mut format_one: F,
) -> String {
    let mut s = String::from("{");
    let mut first = true;
    for v in iter {
        if !first {
            s.push(',');
        }
        first = false;
        s.push_str(&format_one(v));
    }
    s.push('}');
    s
}

fn sqlx4k_postgresql_row_of(row: &PgRow) -> Sqlx4kPostgresRow {
    let columns = row.columns();
    if columns.is_empty() {
        Sqlx4kPostgresRow::default()
    } else {
        let columns: Vec<Sqlx4kPostgresColumn> = row
            .columns()
            .iter()
            .map(|c| Sqlx4kPostgresColumn {
                ordinal: c.ordinal() as c_int,
                value: decode_postgresql_column_value(row, c.ordinal()),
            })
            .collect();

        let size = columns.len();
        let columns: Box<[Sqlx4kPostgresColumn]> = columns.into_boxed_slice();
        let columns: &mut [Sqlx4kPostgresColumn] = Box::leak(columns);
        let columns: *mut Sqlx4kPostgresColumn = columns.as_mut_ptr();

        Sqlx4kPostgresRow {
            size: size as c_int,
            columns,
        }
    }
}

// ----------------------------------------------------------------------------
// Parameterized FFI exports (used by the Statement-based execute/fetchAll path)
// ----------------------------------------------------------------------------

#[no_mangle]
pub extern "C" fn sqlx4k_postgresql_query_with_params(
    rt: *mut c_void,
    sql: *const c_char,
    params: *const Sqlx4kPostgresParam,
    params_len: c_int,
    callback: *mut c_void,
    fun: extern "C" fn(Sqlx4kPostgresPtr, *mut Sqlx4kPostgresResult),
) {
    let callback = Sqlx4kPostgresPtr { ptr: callback };
    let sql = c_chars_to_str_postgres(sql).to_owned();
    let owned = match read_params(params, params_len) {
        Ok(owned) => owned,
        Err(err) => return fun(callback, params_error(err, std::ptr::null_mut())),
    };
    let runtime = RUNTIME.get().unwrap();
    let sqlx4k = unsafe { &*(rt as *mut Sqlx4kPostgreSql) };
    runtime.spawn(async move {
        let result = sqlx4k.query_with_params(sql, owned).await;
        fun(callback, result)
    });
}

#[no_mangle]
pub extern "C" fn sqlx4k_postgresql_fetch_all_with_params(
    rt: *mut c_void,
    sql: *const c_char,
    params: *const Sqlx4kPostgresParam,
    params_len: c_int,
    callback: *mut c_void,
    fun: extern "C" fn(Sqlx4kPostgresPtr, *mut Sqlx4kPostgresResult),
) {
    let callback = Sqlx4kPostgresPtr { ptr: callback };
    let sql = c_chars_to_str_postgres(sql).to_owned();
    let owned = match read_params(params, params_len) {
        Ok(owned) => owned,
        Err(err) => return fun(callback, params_error(err, std::ptr::null_mut())),
    };
    let runtime = RUNTIME.get().unwrap();
    let sqlx4k = unsafe { &*(rt as *mut Sqlx4kPostgreSql) };
    runtime.spawn(async move {
        let result = sqlx4k.fetch_all_with_params(sql, owned).await;
        fun(callback, result)
    });
}

#[no_mangle]
pub extern "C" fn sqlx4k_postgresql_cn_query_with_params(
    rt: *mut c_void,
    cn: *mut c_void,
    sql: *const c_char,
    params: *const Sqlx4kPostgresParam,
    params_len: c_int,
    callback: *mut c_void,
    fun: extern "C" fn(Sqlx4kPostgresPtr, *mut Sqlx4kPostgresResult),
) {
    let cn = Sqlx4kPostgresPtr { ptr: cn };
    let callback = Sqlx4kPostgresPtr { ptr: callback };
    let sql = c_chars_to_str_postgres(sql).to_owned();
    let owned = match read_params(params, params_len) {
        Ok(owned) => owned,
        Err(err) => return fun(callback, params_error(err, std::ptr::null_mut())),
    };
    let runtime = RUNTIME.get().unwrap();
    let sqlx4k = unsafe { &*(rt as *mut Sqlx4kPostgreSql) };
    runtime.spawn(async move {
        let result = sqlx4k.cn_query_with_params(cn, sql, owned).await;
        fun(callback, result)
    });
}

#[no_mangle]
pub extern "C" fn sqlx4k_postgresql_cn_fetch_all_with_params(
    rt: *mut c_void,
    cn: *mut c_void,
    sql: *const c_char,
    params: *const Sqlx4kPostgresParam,
    params_len: c_int,
    callback: *mut c_void,
    fun: extern "C" fn(Sqlx4kPostgresPtr, *mut Sqlx4kPostgresResult),
) {
    let cn = Sqlx4kPostgresPtr { ptr: cn };
    let callback = Sqlx4kPostgresPtr { ptr: callback };
    let sql = c_chars_to_str_postgres(sql).to_owned();
    let owned = match read_params(params, params_len) {
        Ok(owned) => owned,
        Err(err) => return fun(callback, params_error(err, std::ptr::null_mut())),
    };
    let runtime = RUNTIME.get().unwrap();
    let sqlx4k = unsafe { &*(rt as *mut Sqlx4kPostgreSql) };
    runtime.spawn(async move {
        let result = sqlx4k.cn_fetch_all_with_params(cn, sql, owned).await;
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
mod numeric_tests {
    use super::{decode_pg_numeric_array_binary, decode_pg_numeric_binary};

    fn enc(ndigits: i16, weight: i16, sign: u16, dscale: u16, digits: &[u16]) -> Vec<u8> {
        let mut v = Vec::new();
        for x in [ndigits as u16, weight as u16, sign, dscale] {
            v.extend_from_slice(&x.to_be_bytes());
        }
        for d in digits {
            v.extend_from_slice(&d.to_be_bytes());
        }
        v
    }

    #[test]
    fn decodes_postgres_numeric_wire_format() {
        // 7 (SUM of bigint)
        assert_eq!(decode_pg_numeric_binary(&enc(1, 0, 0, 0, &[7])).unwrap(), "7");
        // 1.5 -> groups [1, 5000], weight 0, dscale 1
        assert_eq!(decode_pg_numeric_binary(&enc(2, 0, 0, 1, &[1, 5000])).unwrap(), "1.5");
        // 2.3333333333333333 (AVG) -> [2, 3333, 3333, 3333, 3333], dscale 16
        assert_eq!(decode_pg_numeric_binary(&enc(5, 0, 0, 16, &[2, 3333, 3333, 3333, 3333])).unwrap(), "2.3333333333333333");
        // 123456789.0001 -> [1, 2345, 6789, 1], weight 2, dscale 4
        assert_eq!(decode_pg_numeric_binary(&enc(4, 2, 0, 4, &[1, 2345, 6789, 1])).unwrap(), "123456789.0001");
        // -0.0042 -> [42], weight -1, dscale 4
        assert_eq!(decode_pg_numeric_binary(&enc(1, -1, 0x4000, 4, &[42])).unwrap(), "-0.0042");
        // 0.00000123 -> [123], weight -2, dscale 8
        assert_eq!(decode_pg_numeric_binary(&enc(1, -2, 0, 8, &[123])).unwrap(), "0.00000123");
        // 10000 -> [1], weight 1 (trailing zero groups are omitted)
        assert_eq!(decode_pg_numeric_binary(&enc(1, 1, 0, 0, &[1])).unwrap(), "10000");
        // 0 and 0.00
        assert_eq!(decode_pg_numeric_binary(&enc(0, 0, 0, 0, &[])).unwrap(), "0");
        assert_eq!(decode_pg_numeric_binary(&enc(0, 0, 0, 2, &[])).unwrap(), "0.00");
        // special values
        assert_eq!(decode_pg_numeric_binary(&enc(0, 0, 0xC000, 0, &[])).unwrap(), "NaN");
        assert_eq!(decode_pg_numeric_binary(&enc(0, 0, 0xD000, 0, &[])).unwrap(), "Infinity");
        assert_eq!(decode_pg_numeric_binary(&enc(0, 0, 0xF000, 0, &[])).unwrap(), "-Infinity");
        // malformed input is rejected instead of panicking
        assert!(decode_pg_numeric_binary(&[0, 1]).is_none());
        assert!(decode_pg_numeric_binary(&enc(2, 0, 0, 0, &[1])).is_none());
        assert!(decode_pg_numeric_binary(&enc(1, 0, 0, 0, &[10000])).is_none());
    }

    fn enc_array(dims: &[(i32, i32)], elements: &[Option<Vec<u8>>]) -> Vec<u8> {
        let mut v = Vec::new();
        v.extend_from_slice(&(dims.len() as i32).to_be_bytes());
        v.extend_from_slice(&(elements.iter().any(Option::is_none) as i32).to_be_bytes());
        v.extend_from_slice(&1700u32.to_be_bytes()); // NUMERICOID
        for (len, lbound) in dims {
            v.extend_from_slice(&len.to_be_bytes());
            v.extend_from_slice(&lbound.to_be_bytes());
        }
        for e in elements {
            match e {
                Some(bytes) => {
                    v.extend_from_slice(&(bytes.len() as i32).to_be_bytes());
                    v.extend_from_slice(bytes);
                }
                None => v.extend_from_slice(&(-1i32).to_be_bytes()),
            }
        }
        v
    }

    #[test]
    fn decodes_postgres_numeric_array_wire_format() {
        let one_five = Some(enc(2, 0, 0, 1, &[1, 5000]));
        let nan = Some(enc(0, 0, 0xC000, 0, &[]));
        let neg = Some(enc(2, 0, 0x4000, 2, &[2, 2500]));
        let zero = Some(enc(0, 0, 0, 3, &[]));
        // empty array
        assert_eq!(decode_pg_numeric_array_binary(&enc_array(&[], &[])).unwrap(), "{}");
        // one dimension, with NULL and NaN
        let a = enc_array(&[(5, 1)], &[one_five.clone(), neg.clone(), zero.clone(), None, nan.clone()]);
        assert_eq!(decode_pg_numeric_array_binary(&a).unwrap(), "{1.5,-2.25,0.000,NULL,NaN}");
        // two dimensions
        let a = enc_array(&[(2, 1), (2, 1)], &[one_five.clone(), neg.clone(), zero.clone(), nan.clone()]);
        assert_eq!(decode_pg_numeric_array_binary(&a).unwrap(), "{{1.5,-2.25},{0.000,NaN}}");
        // non-default lower bound
        let a = enc_array(&[(2, 0)], &[one_five.clone(), nan.clone()]);
        assert_eq!(decode_pg_numeric_array_binary(&a).unwrap(), "[0:1]={1.5,NaN}");
        // malformed input is rejected instead of panicking
        assert!(decode_pg_numeric_array_binary(&[0, 0]).is_none());
        let truncated = enc_array(&[(2, 1)], &[one_five.clone(), nan.clone()]);
        assert!(decode_pg_numeric_array_binary(&truncated[..truncated.len() - 1]).is_none());
        assert!(decode_pg_numeric_array_binary(&enc_array(&[(1, 1)], &[Some(vec![0, 1])])).is_none());
    }
}
