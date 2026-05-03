use sqlx::encode::IsNull;
use sqlx::error::BoxDynError;
use sqlx::pool::PoolConnection;
use sqlx::postgres::types::Oid;
use sqlx::postgres::{
    PgArgumentBuffer, PgConnectOptions, PgListener, PgNotification, PgPool, PgPoolOptions, PgRow,
    PgTypeInfo, PgValueRef,
};
// Re-export sqlx's chrono/uuid feature shims so we don't need direct dependencies
// on those crates — sqlx already pulls them in when the matching features are on.
use sqlx::types::{chrono, uuid};
use sqlx::{
    Acquire, Column, Encode, Error, Executor, Postgres, Row, Transaction, Type, TypeInfo, ValueRef,
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
// Shared constants and types (inlined from sqlx4k)
// ============================================================================

pub const OK: c_int = -1;
pub const ERROR_DATABASE: c_int = 0;
pub const ERROR_POOL_TIMED_OUT: c_int = 1;
pub const ERROR_POOL_CLOSED: c_int = 2;
pub const ERROR_WORKER_CRASHED: c_int = 3;

#[repr(C)]
pub struct Sqlx4kPostgresPtr {
    pub ptr: *mut c_void,
}
unsafe impl Send for Sqlx4kPostgresPtr {}
unsafe impl Sync for Sqlx4kPostgresPtr {}

#[repr(C)]
pub struct Sqlx4kPostgresResult {
    pub error: c_int,
    pub error_message: *mut c_char,
    pub rows_affected: c_ulonglong,
    pub cn: *mut c_void,
    pub tx: *mut c_void,
    pub rt: *mut c_void,
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

fn read_text(p: &Sqlx4kPostgresParam) -> String {
    unsafe { CStr::from_ptr(p.text) }
        .to_str()
        .expect("Invalid UTF-8 in TEXT-shaped parameter")
        .to_owned()
}

/// Reads `count` params from a C array and copies their data into owned Rust
/// values, so the resulting Vec can be moved into an async block without
/// referencing the caller's memory.
fn read_params(params: *const Sqlx4kPostgresParam, count: c_int) -> Vec<OwnedParam> {
    if params.is_null() || count <= 0 {
        return Vec::new();
    }
    let slice = unsafe { slice::from_raw_parts(params, count as usize) };
    slice
        .iter()
        .map(|p| match p.kind {
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
                    .unwrap_or_else(|e| panic!("Invalid DATE '{}': {}", s, e));
                OwnedParam::Date(d)
            }
            PARAM_TIME => {
                let s = read_text(p);
                let t = chrono::NaiveTime::parse_from_str(&s, "%H:%M:%S%.f")
                    .or_else(|_| chrono::NaiveTime::parse_from_str(&s, "%H:%M:%S"))
                    .unwrap_or_else(|e| panic!("Invalid TIME '{}': {}", s, e));
                OwnedParam::Time(t)
            }
            PARAM_TIMESTAMP => {
                let s = read_text(p);
                let dt = chrono::NaiveDateTime::parse_from_str(&s, "%Y-%m-%d %H:%M:%S%.f")
                    .or_else(|_| chrono::NaiveDateTime::parse_from_str(&s, "%Y-%m-%d %H:%M:%S"))
                    .unwrap_or_else(|e| panic!("Invalid TIMESTAMP '{}': {}", s, e));
                OwnedParam::Timestamp(dt)
            }
            PARAM_TIMESTAMPTZ => {
                // Kotlin emits UTC `yyyy-MM-dd HH:mm:ss.uuuuuu` (no offset suffix).
                // Treat it as UTC.
                let s = read_text(p);
                let naive = chrono::NaiveDateTime::parse_from_str(&s, "%Y-%m-%d %H:%M:%S%.f")
                    .or_else(|_| chrono::NaiveDateTime::parse_from_str(&s, "%Y-%m-%d %H:%M:%S"))
                    .unwrap_or_else(|e| panic!("Invalid TIMESTAMPTZ '{}': {}", s, e));
                OwnedParam::TimestampTz(naive.and_utc())
            }
            PARAM_UUID => {
                let s = read_text(p);
                let u = uuid::Uuid::parse_str(&s)
                    .unwrap_or_else(|e| panic!("Invalid UUID '{}': {}", s, e));
                OwnedParam::Uuid(u)
            }
            other => panic!("Unknown Sqlx4kPostgresParam kind: {}", other),
        })
        .collect()
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

    Sqlx4kPostgresResult {
        error: code,
        error_message: CString::new(message).unwrap().into_raw(),
        ..Default::default()
    }
}

pub fn c_chars_to_str_postgres<'a>(c_chars: *const c_char) -> &'a str {
    unsafe { CStr::from_ptr(c_chars).to_str().unwrap() }
}

// ============================================================================
// PostgreSQL-specific implementation
// ============================================================================

static RUNTIME: OnceLock<Runtime> = OnceLock::new();

#[derive(Debug)]
struct Sqlx4kPostgreSql {
    connect_options: PgConnectOptions,
    pool: PgPool,
}

impl Sqlx4kPostgreSql {
    async fn query(&self, sql: &str) -> *mut Sqlx4kPostgresResult {
        let result = self.pool.execute(sql).await;
        let result = match result {
            Ok(res) => Sqlx4kPostgresResult {
                rows_affected: res.rows_affected(),
                ..Default::default()
            },
            Err(err) => sqlx4k_postgres_error_result_of(err),
        };
        result.leak()
    }

    async fn fetch_all(&self, sql: &str) -> *mut Sqlx4kPostgresResult {
        let result = self.pool.fetch_all(sql).await;
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

    async fn cn_query(&self, cn: Sqlx4kPostgresPtr, sql: &str) -> *mut Sqlx4kPostgresResult {
        let cn = unsafe { &mut *(cn.ptr as *mut PoolConnection<Postgres>) };
        let result = cn.execute(sql).await;
        let result = match result {
            Ok(res) => Sqlx4kPostgresResult {
                rows_affected: res.rows_affected(),
                ..Default::default()
            },
            Err(err) => sqlx4k_postgres_error_result_of(err),
        };
        result.leak()
    }

    async fn cn_fetch_all(&self, cn: Sqlx4kPostgresPtr, sql: &str) -> *mut Sqlx4kPostgresResult {
        let cn = unsafe { &mut *(cn.ptr as *mut PoolConnection<Postgres>) };
        let result = cn.fetch_all(sql).await;
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

    async fn tx_query(&self, tx: Sqlx4kPostgresPtr, sql: &str) -> *mut Sqlx4kPostgresResult {
        let mut tx = unsafe { *Box::from_raw(tx.ptr as *mut Transaction<'_, Postgres>) };
        let result = tx.execute(sql).await;
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

    async fn tx_fetch_all(&self, tx: Sqlx4kPostgresPtr, sql: &str) -> *mut Sqlx4kPostgresResult {
        let mut tx = unsafe { *Box::from_raw(tx.ptr as *mut Transaction<'_, Postgres>) };
        let result = tx.fetch_all(sql).await;
        let tx = Box::new(tx);
        let tx = Box::into_raw(tx);
        let result = sqlx4k_postgresql_result_of(result);
        let result = Sqlx4kPostgresResult {
            tx: tx as *mut c_void,
            ..result
        };
        result.leak()
    }

    async fn close(&self) -> *mut Sqlx4kPostgresResult {
        self.pool.close().await;
        Sqlx4kPostgresResult::default().leak()
    }

    async fn query_with_params(
        &self,
        sql: &str,
        params: Vec<OwnedParam>,
    ) -> *mut Sqlx4kPostgresResult {
        let q = bind_params(sqlx::query::<Postgres>(sql), params);
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
        sql: &str,
        params: Vec<OwnedParam>,
    ) -> *mut Sqlx4kPostgresResult {
        let q = bind_params(sqlx::query::<Postgres>(sql), params);
        let result = q.fetch_all(&self.pool).await;
        sqlx4k_postgresql_result_of(result).leak()
    }

    async fn cn_query_with_params(
        &self,
        cn: Sqlx4kPostgresPtr,
        sql: &str,
        params: Vec<OwnedParam>,
    ) -> *mut Sqlx4kPostgresResult {
        let cn = unsafe { &mut *(cn.ptr as *mut PoolConnection<Postgres>) };
        let q = bind_params(sqlx::query::<Postgres>(sql), params);
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
        sql: &str,
        params: Vec<OwnedParam>,
    ) -> *mut Sqlx4kPostgresResult {
        let cn = unsafe { &mut *(cn.ptr as *mut PoolConnection<Postgres>) };
        let q = bind_params(sqlx::query::<Postgres>(sql), params);
        let result = q.fetch_all(&mut **cn).await;
        sqlx4k_postgresql_result_of(result).leak()
    }

    async fn tx_query_with_params(
        &self,
        tx: Sqlx4kPostgresPtr,
        sql: &str,
        params: Vec<OwnedParam>,
    ) -> *mut Sqlx4kPostgresResult {
        let mut tx = unsafe { *Box::from_raw(tx.ptr as *mut Transaction<'_, Postgres>) };
        let q = bind_params(sqlx::query::<Postgres>(sql), params);
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
        sql: &str,
        params: Vec<OwnedParam>,
    ) -> *mut Sqlx4kPostgresResult {
        let mut tx = unsafe { *Box::from_raw(tx.ptr as *mut Transaction<'_, Postgres>) };
        let q = bind_params(sqlx::query::<Postgres>(sql), params);
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
    let options: PgConnectOptions = url.parse().unwrap();
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
        let result = sqlx4k.query(&sql).await;
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
        let result = sqlx4k.fetch_all(&sql).await;
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
        let result = sqlx4k.cn_query(cn, &sql).await;
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
        let result = sqlx4k.cn_fetch_all(cn, &sql).await;
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
        let result = sqlx4k.tx_query(tx, &sql).await;
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
        let result = sqlx4k.tx_fetch_all(tx, &sql).await;
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
    runtime.spawn(async move {
        // Create a pool of 1 without timeouts (as they don't apply here)
        // We only use the pool to handle re-connections
        let pool = sqlx4k
            .pool
            .options()
            .clone()
            .connect_with(sqlx4k.connect_options.clone())
            .await
            .unwrap();

        let mut listener = PgListener::connect_with(&pool).await.unwrap();
        // We don't need to handle close events
        listener.ignore_pool_close_event(true);

        let channels: Vec<&str> = channels.split(',').collect();
        listener.listen_all(channels).await.unwrap();

        // Return OK as soon as the stream is ready.
        let result = Sqlx4kPostgresResult::default().leak();
        fun(callback, result);

        loop {
            while let Some(item) = listener.try_recv().await.unwrap() {
                let result = sqlx4k_postgresql_result_of_pg_notification(item).leak();
                notify(notify_id, result)
            }
            // Automatically reconnect if connection closes.
        }
    });
}

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
        "FLOAT4" => row.get_unchecked::<f32, _>(ordinal).to_string(),
        "FLOAT8" => row.get_unchecked::<f64, _>(ordinal).to_string(),
        "BOOL" => row.get_unchecked::<bool, _>(ordinal).to_string(),
        "BYTEA" => {
            // Match the bytea text-input format `\xHH..` so the existing
            // `asByteArray()` decoder (which strips `\x` and parses hex) round-trips.
            // Use `Vec<u8>` not `&[u8]` — sqlx-rs only supports `&[u8]` decode in
            // the prepared-statement protocol, while simple-query results require
            // an owned buffer.
            let bytes: Vec<u8> = row.get_unchecked(ordinal);
            format!("\\x{}", hex::encode(bytes))
        }
        "UUID" => row.get_unchecked::<uuid::Uuid, _>(ordinal).to_string(),
        "DATE" => row
            .get_unchecked::<chrono::NaiveDate, _>(ordinal)
            .to_string(),
        "TIME" => row
            .get_unchecked::<chrono::NaiveTime, _>(ordinal)
            .to_string(),
        "TIMESTAMP" => {
            let dt: chrono::NaiveDateTime = row.get_unchecked(ordinal);
            dt.format("%Y-%m-%d %H:%M:%S%.6f").to_string()
        }
        "TIMESTAMPTZ" => {
            let dt: chrono::DateTime<chrono::Utc> = row.get_unchecked(ordinal);
            dt.format("%Y-%m-%d %H:%M:%S%.6f").to_string()
        }
        // Array types — render with postgres' `{a,b,c}` text form so the existing
        // `asString()` path round-trips. Element formatting mirrors `T::to_string()`,
        // so this only handles arrays of element types we can decode here.
        "BOOL[]" => {
            // Postgres' canonical text encoding for bool arrays is `{t,f}` and
            // sqlx4k's `asBooleanArray()` parses that form (via `asBoolean`).
            let v: Vec<bool> = row.get_unchecked(ordinal);
            let mut s = String::from("{");
            let mut first = true;
            for b in v {
                if !first {
                    s.push(',');
                }
                first = false;
                s.push(if b { 't' } else { 'f' });
            }
            s.push('}');
            s
        }
        "INT2[]" => format_pg_array(row.get_unchecked::<Vec<i16>, _>(ordinal).iter()),
        "INT4[]" => format_pg_array(row.get_unchecked::<Vec<i32>, _>(ordinal).iter()),
        "INT8[]" => format_pg_array(row.get_unchecked::<Vec<i64>, _>(ordinal).iter()),
        "FLOAT4[]" => format_pg_array(row.get_unchecked::<Vec<f32>, _>(ordinal).iter()),
        "FLOAT8[]" => format_pg_array(row.get_unchecked::<Vec<f64>, _>(ordinal).iter()),
        // Text-like array types — output `{a,b,c}` without per-element quoting so
        // the existing naive `asStringArray()` parser (split on `,`) round-trips.
        // Limitation: elements containing commas will be split incorrectly.
        "TEXT[]" | "VARCHAR[]" | "BPCHAR[]" | "NAME[]" => {
            format_pg_array(row.get_unchecked::<Vec<String>, _>(ordinal).iter())
        }
        "UUID[]" => format_pg_array(row.get_unchecked::<Vec<uuid::Uuid>, _>(ordinal).iter()),
        "TIMESTAMP[]" => {
            let v: Vec<chrono::NaiveDateTime> = row.get_unchecked(ordinal);
            format_pg_array_fmt(v.iter(), |dt| dt.format("%Y-%m-%d %H:%M:%S%.6f").to_string())
        }
        "TIMESTAMPTZ[]" => {
            let v: Vec<chrono::DateTime<chrono::Utc>> = row.get_unchecked(ordinal);
            format_pg_array_fmt(v.iter(), |dt| dt.format("%Y-%m-%d %H:%M:%S%.6f").to_string())
        }
        "BYTEA[]" => {
            // Each element gets the bytea text form (`\xHH..`) so a downstream
            // `asStringArray().map { it.asByteArray() }` round-trips per element.
            let v: Vec<Vec<u8>> = row.get_unchecked(ordinal);
            format_pg_array_fmt(v.iter(), |b| format!("\\x{}", hex::encode(b)))
        }
        // TEXT/VARCHAR/CHAR/NAME/JSON/JSONB and anything else (including NUMERIC,
        // which we don't depend on bigdecimal/rust_decimal for) fall through to
        // `&str`. In the simple-query / text protocol every value arrives as text;
        // in extended/prepared mode this works for text-typed columns. Callers
        // wanting binary-only types via prepared statements can register a custom
        // row decoder or expose them through cast-to-text in SQL.
        _ => row.get_unchecked::<&str, _>(ordinal).to_string(),
    };
    CString::new(s).unwrap().into_raw()
}

fn format_pg_array<'a, T: 'a + ToString, I: Iterator<Item = &'a T>>(iter: I) -> String {
    format_pg_array_fmt(iter, |v| v.to_string())
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
    let owned = read_params(params, params_len);
    let runtime = RUNTIME.get().unwrap();
    let sqlx4k = unsafe { &*(rt as *mut Sqlx4kPostgreSql) };
    runtime.spawn(async move {
        let result = sqlx4k.query_with_params(&sql, owned).await;
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
    let owned = read_params(params, params_len);
    let runtime = RUNTIME.get().unwrap();
    let sqlx4k = unsafe { &*(rt as *mut Sqlx4kPostgreSql) };
    runtime.spawn(async move {
        let result = sqlx4k.fetch_all_with_params(&sql, owned).await;
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
    let owned = read_params(params, params_len);
    let runtime = RUNTIME.get().unwrap();
    let sqlx4k = unsafe { &*(rt as *mut Sqlx4kPostgreSql) };
    runtime.spawn(async move {
        let result = sqlx4k.cn_query_with_params(cn, &sql, owned).await;
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
    let owned = read_params(params, params_len);
    let runtime = RUNTIME.get().unwrap();
    let sqlx4k = unsafe { &*(rt as *mut Sqlx4kPostgreSql) };
    runtime.spawn(async move {
        let result = sqlx4k.cn_fetch_all_with_params(cn, &sql, owned).await;
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
    let owned = read_params(params, params_len);
    let runtime = RUNTIME.get().unwrap();
    let sqlx4k = unsafe { &*(rt as *mut Sqlx4kPostgreSql) };
    runtime.spawn(async move {
        let result = sqlx4k.tx_query_with_params(tx, &sql, owned).await;
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
    let owned = read_params(params, params_len);
    let runtime = RUNTIME.get().unwrap();
    let sqlx4k = unsafe { &*(rt as *mut Sqlx4kPostgreSql) };
    runtime.spawn(async move {
        let result = sqlx4k.tx_fetch_all_with_params(tx, &sql, owned).await;
        fun(callback, result)
    });
}
