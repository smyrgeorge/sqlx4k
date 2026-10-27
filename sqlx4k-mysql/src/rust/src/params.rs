//! Reading the parameters Kotlin passes, and binding them to a query.

use sqlx::MySql;
use std::ffi::{c_char, c_int, c_void};

use crate::errors::sqlx4k_mysql_error_result_of;
use crate::result::slice_of;
use crate::types::Sqlx4kMysqlResult;

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
pub(crate) fn read_text(p: &Sqlx4kMysqlParam) -> String {
    let len = p.text_len.max(0) as usize;
    let bytes = if len == 0 || p.text.is_null() {
        &[][..]
    } else {
        unsafe { slice_of(p.text as *const u8, len) }
    };
    String::from_utf8_lossy(bytes).into_owned()
}

pub(crate) enum OwnedParam {
    Null,
    Int(i64),
    Real(f64),
    Text(String),
    Blob(Vec<u8>),
}

/// A kind that cannot be read is reported as an error (see `params_error`): a panic here would abort the process.
pub(crate) fn read_params(
    params: *const Sqlx4kMysqlParam,
    count: c_int,
) -> Result<Vec<OwnedParam>, String> {
    if params.is_null() || count <= 0 {
        return Ok(Vec::new());
    }
    let slice = unsafe { slice_of(params, count as usize) };
    slice
        .iter()
        .map(|p| {
            Ok(match p.kind {
                PARAM_NULL => OwnedParam::Null,
                PARAM_INT => OwnedParam::Int(p.i64_val),
                PARAM_REAL => OwnedParam::Real(p.f64_val),
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
                other => return Err(format!("Unknown Sqlx4kMysqlParam kind: {}", other)),
            })
        })
        .collect()
}

/// The result of a call whose parameters could not be read, handing back the transaction (`tx`, or null) it was
/// given: the caller takes it back from every result.
pub(crate) fn params_error(err: String, tx: *mut c_void) -> *mut Sqlx4kMysqlResult {
    Sqlx4kMysqlResult {
        tx,
        ..sqlx4k_mysql_error_result_of(sqlx::Error::Encode(err.into()))
    }
    .leak()
}

pub(crate) fn bind_params<'q>(
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

#[cfg(test)]
mod tests {
    use super::*;
    use crate::test_support::{block_on, c, connect_options, param, text_param};
    use sqlx::mysql::MySqlPoolOptions;
    use sqlx::Row;
    use std::ffi::CString;
    use std::ptr::null;

    fn read(params: &[Sqlx4kMysqlParam]) -> Vec<OwnedParam> {
        read_params(params.as_ptr(), params.len() as c_int).unwrap()
    }

    #[test]
    fn no_params_read_as_none() {
        assert!(read_params(null(), 0).unwrap().is_empty());
        assert!(read_params(null(), 3).unwrap().is_empty());
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
        let p = param(PARAM_TEXT, |p| {
            p.text = bytes.as_ptr() as *const c_char;
            p.text_len = 3
        });
        assert_eq!(read_text(&p), "a\0b");
        let s = CString::new("a").unwrap();
        assert_eq!(read_text(&param(PARAM_TEXT, |p| p.text = s.as_ptr())), "");
        assert_eq!(read_text(&param(PARAM_TEXT, |_| {})), "");
    }

    #[test]
    fn an_empty_or_missing_blob_reads_empty() {
        let read = read(&[param(PARAM_BLOB, |p| p.blob_len = 5)]);
        assert!(matches!(&read[0], OwnedParam::Blob(v) if v.is_empty()));
    }

    // Kotlin only passes the kinds above; another one is a bug on its side, reported as an error (a panic would abort
    // the process).
    #[test]
    fn an_unknown_kind_is_an_error() {
        let params = [param(PARAM_INT, |_| {}), param(9, |_| {})];
        assert_eq!(
            read_params(params.as_ptr(), 2).err().as_deref(),
            Some("Unknown Sqlx4kMysqlParam kind: 9")
        );
    }

    #[test]
    fn a_params_error_hands_back_the_transaction() {
        let tx = 0x10 as *mut c_void;
        let r = crate::test_support::Res(params_error("bad".to_string(), tx));
        assert_eq!(
            (r.get().error, r.get().tx),
            (crate::types::ERROR_DATABASE, tx)
        );
        assert!(
            r.error_message().unwrap().contains("bad"),
            "{:?}",
            r.error_message()
        );
    }

    #[test]
    #[ignore = "needs the MySQL of docker-compose.yml"]
    fn every_kind_is_bound() {
        block_on(async {
            let pool = MySqlPoolOptions::new()
                .max_connections(1)
                .connect_with(connect_options())
                .await
                .unwrap();
            let params = vec![
                OwnedParam::Null,
                OwnedParam::Int(-8),
                OwnedParam::Real(1.5),
                OwnedParam::Text("x".to_string()),
                OwnedParam::Blob(vec![1, 2]),
            ];
            let sql = "select ? is null, cast(? as char), cast(? as char), ?, hex(?)";
            let row = bind_params(sqlx::query(sql), params)
                .fetch_one(&pool)
                .await
                .unwrap();
            assert_eq!(row.get::<i64, _>(0), 1);
            let values: Vec<String> = (1..5).map(|i| row.get::<String, _>(i)).collect();
            assert_eq!(values, ["-8", "1.5", "x", "0102"]);
            pool.close().await;
        });
    }
}
