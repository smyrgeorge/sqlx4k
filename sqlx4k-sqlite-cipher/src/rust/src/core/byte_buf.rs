//! Byte-buffer serialization (JNI bridge).
//!
//! The JVM/Android side cannot dereference the C result graph directly, so the JNI layer
//! serializes a whole `Sqlx4kSqliteCipherResult` into one length-prefixed, big-endian byte
//! buffer that Kotlin decodes in a single pass. This keeps result shaping (including the
//! hex-encoding of BLOBs) identical to the FFI/cinterop path. Mirror of the Kotlin decoder
//! in jniMain `jni.kt`.
//!
//! Layout:
//!   i32 error                         (-1 = OK; >= 0 maps to SQLError.Code ordinal)
//!   u8  has_error_message; [str]
//!   u8  has_error_sql_state; [str]   (always absent for SQLite; kept for layout symmetry)
//!   i32 error_native_code             (-1 = none; otherwise the SQLite extended result code)
//!   i32 error_kind                    (SQLError.Kind ordinal)
//!   i64 rows_affected
//!   i64 last_insert_id                (the connection's last inserted rowid)
//!   i64 cn  (pointer as i64, 0 = null)
//!   i64 tx
//!   i64 rt
//!   i64 stream (pointer as i64, 0 = null; see `Sqlx4kSqliteCipherStream`)
//!   u8  has_schema; [i32 col_count; { i32 ordinal, str name, str kind } * col_count]
//!   i32 row_count;  [{ i32 col_count; { i32 ordinal, u8 has_value, [str value] } * col_count } * row_count]
//! where str = i32 utf8_len + utf8 bytes.
//!
//! Only compiled where the JNI bridge exists (iOS uses the FFI/cinterop path exclusively, so this
//! would otherwise be flagged as dead code there).

use std::ffi::{c_char, c_int, CStr};

use crate::core::result::slice_of;
use crate::core::types::{Sqlx4kSqliteCipherResult, Sqlx4kSqliteCipherSchema};

#[cfg(not(target_os = "ios"))]
pub(crate) struct ByteBuf {
    pub(crate) inner: Vec<u8>,
}

#[cfg(not(target_os = "ios"))]
impl ByteBuf {
    pub(crate) fn new() -> Self {
        ByteBuf { inner: Vec::new() }
    }
    pub(crate) fn put_u8(&mut self, v: u8) {
        self.inner.push(v);
    }
    pub(crate) fn put_i32(&mut self, v: i32) {
        self.inner.extend_from_slice(&v.to_be_bytes());
    }
    pub(crate) fn put_i64(&mut self, v: i64) {
        self.inner.extend_from_slice(&v.to_be_bytes());
    }
    /// Writes an optional C string as: u8 present-flag, then (when present) i32 len + utf8 bytes.
    pub(crate) fn put_opt_cstr(&mut self, ptr: *const c_char) {
        if ptr.is_null() {
            self.put_u8(0);
        } else {
            self.put_u8(1);
            let bytes = unsafe { CStr::from_ptr(ptr) }.to_bytes();
            self.put_i32(bytes.len() as i32);
            self.inner.extend_from_slice(bytes);
        }
    }
    /// Writes an optional buffer of `len` bytes, in the same layout as `put_opt_cstr`.
    pub(crate) fn put_opt_bytes(&mut self, ptr: *const c_char, len: c_int) {
        if ptr.is_null() {
            self.put_u8(0);
        } else {
            self.put_u8(1);
            self.put_i32(len);
            self.inner.extend_from_slice(unsafe { slice_of(ptr as *const u8, len as usize) });
        }
    }
    /// Writes a required C string as: i32 len + utf8 bytes.
    pub(crate) fn put_cstr(&mut self, ptr: *const c_char) {
        let bytes = if ptr.is_null() {
            &[][..]
        } else {
            unsafe { CStr::from_ptr(ptr) }.to_bytes()
        };
        self.put_i32(bytes.len() as i32);
        self.inner.extend_from_slice(bytes);
    }
}

/// Serializes a leaked result into a byte buffer **without** freeing it. The caller is
/// responsible for calling [`free_result`] afterwards.
#[cfg(not(target_os = "ios"))]
pub fn serialize_result(ptr: *const Sqlx4kSqliteCipherResult) -> Vec<u8> {
    let result: &Sqlx4kSqliteCipherResult = unsafe { &*ptr };
    let mut buf = ByteBuf::new();

    buf.put_i32(result.error);
    if result.error >= 0 {
        buf.put_opt_cstr(result.error_message);
        buf.put_opt_cstr(result.error_sql_state);
    } else {
        buf.put_u8(0);
        buf.put_u8(0);
    }
    buf.put_i32(result.error_native_code);
    buf.put_i32(result.error_kind);
    buf.put_i64(result.rows_affected as i64);
    buf.put_i64(result.last_insert_id);
    buf.put_i64(result.cn as usize as i64);
    buf.put_i64(result.tx as usize as i64);
    buf.put_i64(result.rt as usize as i64);
    buf.put_i64(result.stream as usize as i64);

    // Schema.
    if result.schema.is_null() {
        buf.put_u8(0);
    } else {
        let schema: &Sqlx4kSqliteCipherSchema = unsafe { &*result.schema };
        if schema.size <= 0 || schema.columns.is_null() {
            // Present but empty schema is encoded as "no schema".
            buf.put_u8(0);
        } else {
            buf.put_u8(1);
            let cols = unsafe { slice_of(schema.columns, schema.size as usize) };
            buf.put_i32(schema.size);
            for c in cols {
                buf.put_i32(c.ordinal);
                buf.put_cstr(c.name);
                buf.put_cstr(c.kind);
            }
        }
    }

    // Rows.
    if result.rows.is_null() || result.size <= 0 {
        buf.put_i32(0);
    } else {
        let rows = unsafe { slice_of(result.rows, result.size as usize) };
        buf.put_i32(result.size);
        for row in rows {
            if row.columns.is_null() || row.size <= 0 {
                buf.put_i32(0);
                continue;
            }
            let cols = unsafe { slice_of(row.columns, row.size as usize) };
            buf.put_i32(row.size);
            for c in cols {
                buf.put_i32(c.ordinal);
                buf.put_opt_bytes(c.value, c.value_len);
            }
        }
    }

    buf.inner
}
