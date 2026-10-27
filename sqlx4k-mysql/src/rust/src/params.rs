//! Reading the parameters Kotlin passes, and binding them to a query.

use sqlx::MySql;
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

pub(crate) fn read_params(params: *const Sqlx4kMysqlParam, count: c_int) -> Vec<OwnedParam> {
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
            other => panic!("Unknown Sqlx4kMysqlParam kind: {}", other),
        })
        .collect()
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
