//! Reading the parameters Kotlin passes, and binding them to a query.

use sqlx::Sqlite;
use std::ffi::{c_char, c_int};

use crate::result::slice_of;

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
    /// Length of `text` in bytes; TEXT may contain NUL, so it is not read as a C string.
    pub text_len: c_int,
    pub blob: *const u8,
    pub blob_len: c_int,
}

pub(crate) enum OwnedParam {
    Null,
    Int(i64),
    Real(f64),
    Text(String),
    Blob(Vec<u8>),
}

/// Reads `count` params from a C array and copies their data into owned
/// Rust values, so the resulting Vec can be moved into an async block
/// without referencing the caller's memory.
pub(crate) fn read_params(params: *const Sqlx4kSqliteParam, count: c_int) -> Vec<OwnedParam> {
    if params.is_null() || count <= 0 {
        return Vec::new();
    }
    let slice = unsafe { slice_of(params, count as usize) };
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
                    unsafe { slice_of(p.text as *const u8, len) }
                };
                OwnedParam::Text(String::from_utf8_lossy(bytes).into_owned())
            }
            PARAM_BLOB => {
                let len = p.blob_len.max(0) as usize;
                let bytes = if len == 0 || p.blob.is_null() {
                    Vec::new()
                } else {
                    unsafe { slice_of(p.blob, len) }.to_vec()
                };
                OwnedParam::Blob(bytes)
            }
            other => panic!("Unknown Sqlx4kSqliteParam kind: {}", other),
        })
        .collect()
}

pub(crate) fn bind_params<'q>(
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

#[cfg(test)]
mod tests {
    use super::*;
    use crate::test_support::{block_on, c, memory, param, text_param};
    use sqlx::{Connection, Row};
    use std::ptr::null;

    fn read(params: &[Sqlx4kSqliteParam]) -> Vec<OwnedParam> {
        read_params(params.as_ptr(), params.len() as c_int)
    }

    #[test]
    fn no_params_read_as_none() {
        assert!(read_params(null(), 0).is_empty());
        assert!(read_params(null(), 3).is_empty());
    }

    #[test]
    fn every_kind_is_read() {
        let s = c("hello");
        let blob = [1u8, 0, 255];
        let read = read(&[
            param(PARAM_NULL, |_| {}),
            param(PARAM_INT, |p| p.i64_val = i64::MIN),
            param(PARAM_REAL, |p| p.f64_val = -2.25),
            text_param(PARAM_TEXT, &s),
            param(PARAM_BLOB, |p| {
                p.blob = blob.as_ptr();
                p.blob_len = 3
            }),
        ]);
        assert!(matches!(read[0], OwnedParam::Null));
        assert!(matches!(read[1], OwnedParam::Int(i64::MIN)));
        assert!(matches!(read[2], OwnedParam::Real(v) if v == -2.25));
        assert!(matches!(&read[3], OwnedParam::Text(v) if v == "hello"));
        assert!(matches!(&read[4], OwnedParam::Blob(v) if v == &blob));
    }

    #[test]
    fn a_text_is_read_by_its_length() {
        let bytes = b"a\0b and more";
        let s = c("a");
        let read = read(&[
            param(PARAM_TEXT, |p| {
                p.text = bytes.as_ptr() as *const c_char;
                p.text_len = 3
            }),
            param(PARAM_TEXT, |p| p.text = s.as_ptr()),
            param(PARAM_TEXT, |p| p.text_len = 4),
            // Invalid UTF-8 is replaced, not trusted.
            param(PARAM_TEXT, |p| {
                p.text = b"\xff".as_ptr() as *const c_char;
                p.text_len = 1
            }),
        ]);
        let texts: Vec<&str> = read
            .iter()
            .map(|p| match p {
                OwnedParam::Text(s) => s.as_str(),
                _ => panic!("not a text"),
            })
            .collect();
        assert_eq!(texts, ["a\0b", "", "", "\u{fffd}"]);
    }

    #[test]
    fn an_empty_or_missing_blob_reads_empty() {
        let read = read(&[param(PARAM_BLOB, |p| p.blob_len = 5)]);
        assert!(matches!(&read[0], OwnedParam::Blob(v) if v.is_empty()));
    }

    // Kotlin only passes the kinds above; another one is a bug on its side (and aborts a release build).
    #[test]
    #[should_panic(expected = "Unknown Sqlx4kSqliteParam kind: 9")]
    fn an_unknown_kind_panics() {
        read(&[param(9, |_| {})]);
    }

    #[test]
    fn every_kind_is_bound() {
        block_on(async {
            let mut cn = memory().await;
            let params = vec![
                OwnedParam::Null,
                OwnedParam::Int(-8),
                OwnedParam::Real(1.5),
                OwnedParam::Text("a\0b".to_string()),
                OwnedParam::Blob(vec![1, 2]),
            ];
            let sql = "select ? is null, typeof(?), ?, hex(?), hex(?)";
            let row = bind_params(sqlx::query(sql), params).fetch_one(&mut cn).await.unwrap();
            assert_eq!(row.get::<i64, _>(0), 1);
            assert_eq!(row.get::<String, _>(1), "integer");
            assert_eq!(row.get::<f64, _>(2), 1.5);
            // The NUL inside the text is bound with it.
            assert_eq!(row.get::<String, _>(3), "610062");
            assert_eq!(row.get::<String, _>(4), "0102");
            cn.close().await.unwrap();
        });
    }
}
