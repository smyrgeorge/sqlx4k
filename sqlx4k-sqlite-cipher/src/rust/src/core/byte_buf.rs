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
            self.inner
                .extend_from_slice(unsafe { slice_of(ptr as *const u8, len as usize) });
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
/// responsible for calling [`free_result`](crate::core::result::free_result) afterwards.
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

#[cfg(test)]
mod tests {
    use super::*;
    use crate::core::errors::error_result_of;
    use crate::core::result::{free_result, result_of};
    use crate::core::types::{ERROR_DATABASE, KIND_OTHER, NO_NATIVE_CODE, OK};
    use crate::test_support::{block_on, c, memory};
    use sqlx::{Connection, Executor};
    use std::ffi::c_void;
    use std::ptr::null;

    /// Reads a buffer back as the Kotlin decoder (jniMain `jni.kt`) does.
    struct Reader<'a> {
        b: &'a [u8],
    }

    impl Reader<'_> {
        fn take(&mut self, n: usize) -> &[u8] {
            let (head, tail) = self.b.split_at(n);
            self.b = tail;
            head
        }
        fn u8(&mut self) -> u8 {
            self.take(1)[0]
        }
        fn i32(&mut self) -> i32 {
            i32::from_be_bytes(self.take(4).try_into().unwrap())
        }
        fn i64(&mut self) -> i64 {
            i64::from_be_bytes(self.take(8).try_into().unwrap())
        }
        fn str(&mut self) -> String {
            let len = self.i32() as usize;
            String::from_utf8(self.take(len).to_vec()).unwrap()
        }
        fn opt_str(&mut self) -> Option<String> {
            (self.u8() == 1).then(|| self.str())
        }
    }

    /// A serialized result, decoded.
    #[derive(Debug, PartialEq)]
    struct Decoded {
        error: i32,
        error_message: Option<String>,
        error_sql_state: Option<String>,
        error_native_code: i32,
        error_kind: i32,
        rows_affected: i64,
        last_insert_id: i64,
        handles: [i64; 4],
        schema: Option<Vec<(i32, String, String)>>,
        rows: Vec<Vec<(i32, Option<String>)>>,
    }

    fn decode(bytes: &[u8]) -> Decoded {
        let mut r = Reader { b: bytes };
        let decoded = Decoded {
            error: r.i32(),
            error_message: r.opt_str(),
            error_sql_state: r.opt_str(),
            error_native_code: r.i32(),
            error_kind: r.i32(),
            rows_affected: r.i64(),
            last_insert_id: r.i64(),
            handles: [r.i64(), r.i64(), r.i64(), r.i64()],
            schema: (r.u8() == 1)
                .then(|| (0..r.i32()).map(|_| (r.i32(), r.str(), r.str())).collect()),
            rows: (0..r.i32())
                .map(|_| (0..r.i32()).map(|_| (r.i32(), r.opt_str())).collect())
                .collect(),
        };
        assert!(r.b.is_empty(), "{} bytes left", r.b.len());
        decoded
    }

    /// Serializes and decodes a leaked result, then frees it.
    fn round_trip(result: *mut Sqlx4kSqliteCipherResult) -> Decoded {
        let decoded = decode(&serialize_result(result));
        free_result(result);
        decoded
    }

    #[test]
    fn values_are_written_big_endian_and_strings_with_their_length() {
        let mut buf = ByteBuf::new();
        buf.put_u8(7);
        buf.put_i32(-2);
        buf.put_i64(0x0102030405060708);
        let s = c("hé");
        buf.put_cstr(s.as_ptr());
        buf.put_cstr(null());
        buf.put_opt_cstr(s.as_ptr());
        buf.put_opt_cstr(null());
        buf.put_opt_bytes(b"a\0b".as_ptr() as *const c_char, 3);
        buf.put_opt_bytes(null(), 0);
        let mut expected = vec![7, 0xff, 0xff, 0xff, 0xfe, 1, 2, 3, 4, 5, 6, 7, 8];
        expected.extend([0, 0, 0, 3, b'h', 0xc3, 0xa9]);
        expected.extend([0, 0, 0, 0]);
        expected.extend([1, 0, 0, 0, 3, b'h', 0xc3, 0xa9]);
        expected.extend([0]);
        expected.extend([1, 0, 0, 0, 3, b'a', 0, b'b']);
        expected.extend([0]);
        assert_eq!(buf.inner, expected);
    }

    #[test]
    fn a_result_with_rows_is_serialized_with_its_schema() {
        let rows = block_on(async {
            let mut cn = memory().await;
            let rows = cn
                .fetch_all("select 1 as a, null as b, 'x' || char(0) || 'y' as c, x'01ff' as d")
                .await;
            cn.close().await.unwrap();
            rows
        });
        let decoded = round_trip(result_of(rows).leak());
        let columns = [
            (0, "a", "INTEGER"),
            (1, "b", "NULL"),
            (2, "c", "TEXT"),
            (3, "d", "BLOB"),
        ];
        assert_eq!(
            decoded.schema,
            Some(
                columns
                    .iter()
                    .map(|(i, n, k)| (*i, n.to_string(), k.to_string()))
                    .collect()
            )
        );
        // A value is written by its length: the NUL inside it is kept.
        assert_eq!(
            decoded.rows,
            [[
                (0, Some("1".into())),
                (1, None),
                (2, Some("x\0y".into())),
                (3, Some("01ff".into()))
            ]]
        );
        assert_eq!(
            (
                decoded.error,
                decoded.error_message,
                decoded.error_native_code
            ),
            (OK, None, NO_NATIVE_CODE)
        );
    }

    #[test]
    fn an_empty_result_has_no_schema_and_no_rows() {
        let decoded = round_trip(result_of(Ok(vec![])).leak());
        assert_eq!((decoded.schema, decoded.rows.len()), (None, 0));
    }

    #[test]
    fn an_error_is_serialized_with_its_message_code_and_kind() {
        let err = block_on(async {
            let mut cn = memory().await;
            let err = cn.execute("select * from missing_table").await.unwrap_err();
            cn.close().await.unwrap();
            err
        });
        let decoded = round_trip(error_result_of(err).leak());
        assert_eq!(decoded.error, ERROR_DATABASE);
        let message = decoded.error_message.unwrap();
        assert!(
            message.starts_with("[1] ") && message.contains("no such table: missing_table"),
            "{message}"
        );
        // SQLite has no SQLSTATE.
        assert_eq!(
            (
                decoded.error_sql_state,
                decoded.error_native_code,
                decoded.error_kind
            ),
            (None, 1, KIND_OTHER)
        );
    }

    #[test]
    fn counts_and_handles_are_serialized() {
        let result = Sqlx4kSqliteCipherResult {
            rows_affected: 3,
            last_insert_id: -4,
            cn: 0x10 as *mut c_void,
            tx: 0x20 as *mut c_void,
            rt: 0x30 as *mut c_void,
            stream: 0x40 as *mut c_void,
            ..Default::default()
        };
        // Not leaked: its handles are not ours to free.
        let decoded = decode(&serialize_result(&result));
        assert_eq!((decoded.rows_affected, decoded.last_insert_id), (3, -4));
        assert_eq!(decoded.handles, [0x10, 0x20, 0x30, 0x40]);
    }
}
