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

#[cfg(test)]
mod tests {
    use super::*;
    use crate::test_support::{block_on, c, connect_options, param, text, text_param};
    use crate::types::ERROR_DATABASE;
    use sqlx::postgres::PgPoolOptions;
    use sqlx::Row;
    use std::ffi::CString;
    use std::ptr::null;

    fn read(params: &[Sqlx4kPostgresParam]) -> Result<Vec<OwnedParam>, String> {
        read_params(params.as_ptr(), params.len() as c_int)
    }

    #[test]
    fn no_params_read_as_none() {
        assert!(read_params(null(), 0).unwrap().is_empty());
        assert!(read_params(null(), 3).unwrap().is_empty());
    }

    #[test]
    fn every_kind_is_read() {
        let (s, date, time, ts, tstz, uuid) = (
            c("hello"),
            c("2024-02-29"),
            c("12:34:56.5"),
            c("2024-02-29 12:34:56"),
            c("2024-02-29 12:34:56.123456"),
            c("67e55044-10b1-426f-9247-bb680e5fe0c8"),
        );
        let blob = [1u8, 0, 255];
        let params = [
            param(PARAM_NULL, |p| p.null_type = PARAM_INT8),
            param(PARAM_BOOL, |p| p.i64_val = 1),
            param(PARAM_INT2, |p| p.i64_val = -2),
            param(PARAM_INT4, |p| p.i64_val = 4),
            param(PARAM_INT8, |p| p.i64_val = i64::MAX),
            param(PARAM_FLOAT4, |p| p.f64_val = 1.5),
            param(PARAM_FLOAT8, |p| p.f64_val = -2.25),
            text_param(PARAM_TEXT, &s),
            param(PARAM_BLOB, |p| {
                p.blob = blob.as_ptr();
                p.blob_len = 3
            }),
            text_param(PARAM_DATE, &date),
            text_param(PARAM_TIME, &time),
            text_param(PARAM_TIMESTAMP, &ts),
            text_param(PARAM_TIMESTAMPTZ, &tstz),
            text_param(PARAM_UUID, &uuid),
        ];
        let read = read(&params).unwrap();
        assert!(matches!(read[0], OwnedParam::Null(PARAM_INT8)));
        assert!(matches!(read[1], OwnedParam::Bool(true)));
        assert!(matches!(read[2], OwnedParam::Int2(-2)));
        assert!(matches!(read[3], OwnedParam::Int4(4)));
        assert!(matches!(read[4], OwnedParam::Int8(i64::MAX)));
        assert!(matches!(read[5], OwnedParam::Float4(v) if v == 1.5));
        assert!(matches!(read[6], OwnedParam::Float8(v) if v == -2.25));
        assert!(matches!(&read[7], OwnedParam::Text(v) if v == "hello"));
        assert!(matches!(&read[8], OwnedParam::Blob(v) if v == &blob));
        assert!(matches!(&read[9], OwnedParam::Date(v) if v.to_string() == "2024-02-29"));
        assert!(matches!(&read[10], OwnedParam::Time(v) if v.to_string() == "12:34:56.500"));
        assert!(matches!(&read[11], OwnedParam::Timestamp(v) if v.to_string() == "2024-02-29 12:34:56"));
        assert!(matches!(&read[12], OwnedParam::TimestampTz(v) if v.to_string() == "2024-02-29 12:34:56.123456 UTC"));
        assert!(matches!(&read[13], OwnedParam::Uuid(v) if v.to_string() == "67e55044-10b1-426f-9247-bb680e5fe0c8"));
    }

    #[test]
    fn a_text_is_read_by_its_length() {
        // NUL included, and the C string read up to the length only.
        let s = CString::new("a").unwrap();
        let bytes = b"a\0b and more";
        let p = param(PARAM_TEXT, |p| {
            p.text = bytes.as_ptr() as *const c_char;
            p.text_len = 3
        });
        assert_eq!(read_text(&p), "a\0b");
        let empty = param(PARAM_TEXT, |p| p.text = s.as_ptr());
        assert_eq!(read_text(&empty), "");
        assert_eq!(read_text(&param(PARAM_TEXT, |_| {})), "");
    }

    #[test]
    fn an_empty_or_missing_blob_reads_empty() {
        let read = read(&[param(PARAM_BLOB, |p| p.blob_len = 5)]).unwrap();
        assert!(matches!(&read[0], OwnedParam::Blob(v) if v.is_empty()));
    }

    #[test]
    fn a_value_that_cannot_be_read_is_an_error() {
        for (kind, value, message) in [
            (PARAM_DATE, "2024-13-01", "Invalid DATE"),
            (PARAM_TIME, "25:00", "Invalid TIME"),
            (PARAM_TIMESTAMP, "yesterday", "Invalid TIMESTAMP"),
            (PARAM_TIMESTAMPTZ, "now", "Invalid TIMESTAMPTZ"),
            (PARAM_UUID, "not-a-uuid", "Invalid UUID"),
        ] {
            let v = c(value);
            let err = read(&[text_param(kind, &v)]).err().unwrap();
            assert!(err.starts_with(message), "{err}");
        }
        let err = read(&[param(99, |_| {})]).err().unwrap();
        assert_eq!(err, "Unknown Sqlx4kPostgresParam kind: 99");
    }

    #[test]
    fn a_params_error_hands_back_the_transaction() {
        let mut tx = 1;
        let tx = &mut tx as *mut i32 as *mut c_void;
        let r = params_error("bad value".to_string(), tx);
        let result = unsafe { &*r };
        assert_eq!(result.error, ERROR_DATABASE);
        assert_eq!(result.tx, tx);
        assert!(text(result.error_message).unwrap().contains("bad value"));
        crate::result::sqlx4k_postgres_free_result(r);
    }

    #[test]
    #[ignore = "needs the PostgreSQL of docker-compose.yml"]
    fn every_kind_is_bound() {
        block_on(async {
            let pool = PgPoolOptions::new().max_connections(1).connect_with(connect_options()).await.unwrap();
            let params = vec![
                OwnedParam::Bool(true),
                OwnedParam::Int2(-2),
                OwnedParam::Int4(4),
                OwnedParam::Int8(8),
                OwnedParam::Float4(1.5),
                OwnedParam::Float8(-2.25),
                OwnedParam::Text("x".to_string()),
                OwnedParam::Blob(vec![1, 2]),
                OwnedParam::Date(chrono::NaiveDate::from_ymd_opt(2024, 2, 29).unwrap()),
                OwnedParam::Time(chrono::NaiveTime::from_hms_opt(1, 2, 3).unwrap()),
                OwnedParam::Timestamp(chrono::NaiveDate::from_ymd_opt(2024, 2, 29).unwrap().and_hms_opt(1, 2, 3).unwrap()),
                OwnedParam::TimestampTz(chrono::DateTime::from_timestamp(0, 0).unwrap()),
                OwnedParam::Uuid(uuid::Uuid::nil()),
            ];
            let sql = "select $1::bool::text, $2::int2::text, $3::int4::text, $4::int8::text, $5::float4::text, \
                       $6::float8::text, $7::text, encode($8::bytea, 'hex'), $9::date::text, $10::time::text, \
                       $11::timestamp::text, ($12::timestamptz at time zone 'UTC')::text, $13::uuid::text";
            let row = bind_params(sqlx::query(sql), params).fetch_one(&pool).await.unwrap();
            let values: Vec<String> = (0..13).map(|i| row.get::<String, _>(i)).collect();
            assert_eq!(
                values,
                [
                    "true", "-2", "4", "8", "1.5", "-2.25", "x", "0102", "2024-02-29", "01:02:03",
                    "2024-02-29 01:02:03", "1970-01-01 00:00:00", "00000000-0000-0000-0000-000000000000"
                ]
            );
            pool.close().await;
        });
    }

    #[test]
    #[ignore = "needs the PostgreSQL of docker-compose.yml"]
    fn nulls_are_bound_with_their_type_or_none() {
        block_on(async {
            let pool = PgPoolOptions::new().max_connections(1).connect_with(connect_options()).await.unwrap();
            // A typed NULL keeps its type: pg_typeof reads it without a cast.
            for (kind, type_name) in [
                (PARAM_BOOL, "boolean"),
                (PARAM_INT2, "smallint"),
                (PARAM_INT4, "integer"),
                (PARAM_INT8, "bigint"),
                (PARAM_FLOAT4, "real"),
                (PARAM_FLOAT8, "double precision"),
                (PARAM_BLOB, "bytea"),
                (PARAM_DATE, "date"),
                (PARAM_TIME, "time without time zone"),
                (PARAM_TIMESTAMP, "timestamp without time zone"),
                (PARAM_TIMESTAMPTZ, "timestamp with time zone"),
                (PARAM_UUID, "uuid"),
            ] {
                // A statement of its own each (sqlx caches a statement, with its parameter types, by its SQL).
                let sql = format!("select pg_typeof($1)::text, $1 is null -- {kind}");
                let row = bind_params(sqlx::query(sqlx::AssertSqlSafe(sql)), vec![OwnedParam::Null(kind)])
                    .fetch_one(&pool)
                    .await
                    .unwrap();
                assert_eq!(row.get::<String, _>(0), type_name);
                assert!(row.get::<bool, _>(1));
            }
            // An untyped NULL takes the type the query gives it.
            let row = bind_params(sqlx::query("select $1::int4 is null"), vec![OwnedParam::Null(PARAM_NULL)])
                .fetch_one(&pool)
                .await
                .unwrap();
            assert!(row.get::<bool, _>(0));
            pool.close().await;
        });
    }
}
