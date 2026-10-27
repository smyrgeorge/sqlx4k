//! Reading the parameters Kotlin passes, and binding them to a query.

use sqlx::encode::IsNull;
use sqlx::error::BoxDynError;
use sqlx::postgres::types::Oid;
use sqlx::postgres::{PgArgumentBuffer, PgTypeInfo};
// Re-export sqlx's chrono/uuid feature shims so we don't need direct dependencies
// on those crates — sqlx already pulls them in when the matching features are on.
use sqlx::types::{chrono, uuid};
use sqlx::{Encode, Error, Postgres, Type};
use std::ffi::{c_char, c_int, c_void};

use crate::errors::sqlx4k_postgres_error_result_of;
use crate::result::slice_of;
use crate::types::Sqlx4kPostgresResult;

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
pub(crate) struct UntypedNull;

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

pub(crate) enum OwnedParam {
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
pub(crate) fn read_text(p: &Sqlx4kPostgresParam) -> String {
    let len = p.text_len.max(0) as usize;
    let bytes = if len == 0 || p.text.is_null() {
        &[][..]
    } else {
        unsafe { slice_of(p.text as *const u8, len) }
    };
    String::from_utf8_lossy(bytes).into_owned()
}

/// Reads `count` params from a C array and copies their data into owned Rust
/// values, so the resulting Vec can be moved into an async block without
/// referencing the caller's memory.
///
/// A value that cannot be read is reported as an error (see `params_error`): a panic here would abort the process.
pub(crate) fn read_params(params: *const Sqlx4kPostgresParam, count: c_int) -> Result<Vec<OwnedParam>, String> {
    if params.is_null() || count <= 0 {
        return Ok(Vec::new());
    }
    let slice = unsafe { slice_of(params, count as usize) };
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
                        unsafe { slice_of(p.blob, len) }.to_vec()
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
pub(crate) fn params_error(err: String, tx: *mut c_void) -> *mut Sqlx4kPostgresResult {
    Sqlx4kPostgresResult {
        tx,
        ..sqlx4k_postgres_error_result_of(Error::Encode(err.into()))
    }
    .leak()
}

pub(crate) fn bind_params<'q>(
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
