//! JNI bridge consumed by the JVM and Android (the shared library).
//!
//! Unlike the asynchronous FFI path, each JNI call is **blocking**: it drives the shared tokio
//! runtime to completion via `block_on` and returns the marshalled result. Kotlin keeps these
//! calls off the main thread with `Dispatchers.IO`, so blocking here is safe and avoids the
//! complexity of cross-thread JNI callbacks. No `JNI_OnLoad` / class caching is needed because
//! the Rust side never calls back into Java.
//!
//! Symbol names match the Kotlin object `io.github.smyrgeorge.sqlx4k.sqlite.cipher.CipherJni`.
//! Pointers (rt/cn/tx) cross the boundary as `jlong` (64-bit JVM/Android only). Results are
//! returned as a single byte buffer decoded by Kotlin; query parameters arrive the same way.

#![allow(non_snake_case)]

use crate::core::*;
use jni::objects::{JByteArray, JClass, JString};
use jni::sys::{jbyteArray, jint, jlong};
use jni::JNIEnv;
use std::ffi::c_void;

// ----------------------------------------------------------------------------
// Small helpers
// ----------------------------------------------------------------------------

#[inline]
fn engine<'a>(rt: jlong) -> &'a Sqlx4kSqliteCipher {
    unsafe { &*(rt as usize as *mut Sqlx4kSqliteCipher) }
}

#[inline]
fn ptr_of(handle: jlong) -> Sqlx4kSqliteCipherPtr {
    Sqlx4kSqliteCipherPtr {
        ptr: handle as usize as *mut c_void,
    }
}

fn jstring_to_string(env: &mut JNIEnv, s: &JString) -> String {
    if s.as_raw().is_null() {
        return String::new();
    }
    env.get_string(s)
        .expect("Invalid UTF-16 in JString argument")
        .into()
}

/// Serializes the leaked result into a Java `byte[]`, then frees the native result.
fn finish(env: &mut JNIEnv, result: *mut Sqlx4kSqliteCipherResult) -> jbyteArray {
    let bytes = serialize_result(result);
    free_result(result);
    env.byte_array_from_slice(&bytes)
        .expect("Failed to allocate result byte array")
        .into_raw()
}

/// Cursor over the big-endian parameter buffer produced by the Kotlin side.
struct Cursor<'a> {
    b: &'a [u8],
    pos: usize,
}

impl<'a> Cursor<'a> {
    fn u8(&mut self) -> u8 {
        let v = self.b[self.pos];
        self.pos += 1;
        v
    }
    fn i32(&mut self) -> i32 {
        let mut a = [0u8; 4];
        a.copy_from_slice(&self.b[self.pos..self.pos + 4]);
        self.pos += 4;
        i32::from_be_bytes(a)
    }
    fn i64(&mut self) -> i64 {
        let mut a = [0u8; 8];
        a.copy_from_slice(&self.b[self.pos..self.pos + 8]);
        self.pos += 8;
        i64::from_be_bytes(a)
    }
    fn f64(&mut self) -> f64 {
        let mut a = [0u8; 8];
        a.copy_from_slice(&self.b[self.pos..self.pos + 8]);
        self.pos += 8;
        f64::from_be_bytes(a)
    }
    fn bytes(&mut self) -> Vec<u8> {
        let len = self.i32().max(0) as usize;
        let v = self.b[self.pos..self.pos + len].to_vec();
        self.pos += len;
        v
    }
    fn string(&mut self) -> String {
        String::from_utf8(self.bytes()).expect("Invalid UTF-8 in TEXT parameter")
    }
}

/// Decodes the parameter buffer (mirror of the Kotlin encoder in `jni.kt`):
///   i32 count; repeat { u8 kind; [INT i64 | REAL f64 | TEXT str | BLOB bytes | NULL ø] }
/// An unknown kind is reported as an error (see `params_error`), as on the FFI path.
fn params_from_bytes(env: &mut JNIEnv, array: &JByteArray) -> Result<Vec<OwnedParam>, String> {
    if array.as_raw().is_null() {
        return Ok(Vec::new());
    }
    let bytes = env
        .convert_byte_array(array)
        .expect("Failed to read parameter byte array");
    if bytes.is_empty() {
        return Ok(Vec::new());
    }
    params_of(&bytes)
}

/// Decodes the parameters of a non-empty buffer (see `params_from_bytes`).
fn params_of(bytes: &[u8]) -> Result<Vec<OwnedParam>, String> {
    let mut c = Cursor { b: bytes, pos: 0 };
    let count = c.i32().max(0);
    let mut out = Vec::with_capacity(count as usize);
    for _ in 0..count {
        let kind = c.u8() as i32;
        let p = match kind {
            PARAM_INT => OwnedParam::Int(c.i64()),
            PARAM_REAL => OwnedParam::Real(c.f64()),
            PARAM_TEXT => OwnedParam::Text(c.string()),
            PARAM_BLOB => OwnedParam::Blob(c.bytes()),
            PARAM_NULL => OwnedParam::Null,
            other => return Err(format!("Unknown parameter kind: {}", other)),
        };
        out.push(p);
    }
    Ok(out)
}

// ----------------------------------------------------------------------------
// Lifecycle / pool
// ----------------------------------------------------------------------------

#[no_mangle]
pub extern "system" fn Java_io_github_smyrgeorge_sqlx4k_sqlite_cipher_CipherJni_nativeOf<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    url: JString<'local>,
    password: JString<'local>,
    min_connections: jint,
    max_connections: jint,
    acquire_timeout_milis: jint,
    idle_timeout_milis: jint,
    max_lifetime_milis: jint,
) -> jbyteArray {
    let url = jstring_to_string(&mut env, &url);
    let password = jstring_to_string(&mut env, &password);
    let result = open(
        &url,
        &password,
        min_connections,
        max_connections,
        acquire_timeout_milis,
        idle_timeout_milis,
        max_lifetime_milis,
    );
    finish(&mut env, result)
}

#[no_mangle]
pub extern "system" fn Java_io_github_smyrgeorge_sqlx4k_sqlite_cipher_CipherJni_nativePoolSize(
    _env: JNIEnv,
    _class: JClass,
    rt: jlong,
) -> jint {
    pool_size(rt as usize as *mut c_void)
}

#[no_mangle]
pub extern "system" fn Java_io_github_smyrgeorge_sqlx4k_sqlite_cipher_CipherJni_nativePoolIdleSize(
    _env: JNIEnv,
    _class: JClass,
    rt: jlong,
) -> jint {
    pool_idle_size(rt as usize as *mut c_void)
}

#[no_mangle]
pub extern "system" fn Java_io_github_smyrgeorge_sqlx4k_sqlite_cipher_CipherJni_nativeClose<
    'local,
>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    rt: jlong,
) -> jbyteArray {
    let result = RUNTIME.get().unwrap().block_on(engine(rt).close());
    finish(&mut env, result)
}

// ----------------------------------------------------------------------------
// Pool-level queries
// ----------------------------------------------------------------------------

#[no_mangle]
pub extern "system" fn Java_io_github_smyrgeorge_sqlx4k_sqlite_cipher_CipherJni_nativeQuery<
    'local,
>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    rt: jlong,
    sql: JString<'local>,
) -> jbyteArray {
    let sql = jstring_to_string(&mut env, &sql);
    let result = RUNTIME.get().unwrap().block_on(engine(rt).query(sql));
    finish(&mut env, result)
}

#[no_mangle]
pub extern "system" fn Java_io_github_smyrgeorge_sqlx4k_sqlite_cipher_CipherJni_nativeFetchAll<
    'local,
>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    rt: jlong,
    sql: JString<'local>,
) -> jbyteArray {
    let sql = jstring_to_string(&mut env, &sql);
    let result = RUNTIME.get().unwrap().block_on(engine(rt).fetch_all(sql));
    finish(&mut env, result)
}

#[no_mangle]
pub extern "system" fn Java_io_github_smyrgeorge_sqlx4k_sqlite_cipher_CipherJni_nativeQueryWithParams<
    'local,
>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    rt: jlong,
    sql: JString<'local>,
    params: JByteArray<'local>,
) -> jbyteArray {
    let sql = jstring_to_string(&mut env, &sql);
    let owned = match params_from_bytes(&mut env, &params) {
        Ok(owned) => owned,
        Err(err) => return finish(&mut env, params_error(err, std::ptr::null_mut())),
    };
    let result = RUNTIME
        .get()
        .unwrap()
        .block_on(engine(rt).query_with_params(sql, owned));
    finish(&mut env, result)
}

#[no_mangle]
pub extern "system" fn Java_io_github_smyrgeorge_sqlx4k_sqlite_cipher_CipherJni_nativeFetchAllWithParams<
    'local,
>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    rt: jlong,
    sql: JString<'local>,
    params: JByteArray<'local>,
) -> jbyteArray {
    let sql = jstring_to_string(&mut env, &sql);
    let owned = match params_from_bytes(&mut env, &params) {
        Ok(owned) => owned,
        Err(err) => return finish(&mut env, params_error(err, std::ptr::null_mut())),
    };
    let result = RUNTIME
        .get()
        .unwrap()
        .block_on(engine(rt).fetch_all_with_params(sql, owned));
    finish(&mut env, result)
}

// ----------------------------------------------------------------------------
// Streaming (fetch)
// ----------------------------------------------------------------------------
//
// Each call blocks until its result is ready: `nativeStreamNext` waits for the next chunk of rows,
// `nativeStreamClose` for the producer task to stop. Kotlin keeps them on `Dispatchers.IO`.

#[no_mangle]
pub extern "system" fn Java_io_github_smyrgeorge_sqlx4k_sqlite_cipher_CipherJni_nativeStreamOpen<
    'local,
>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    rt: jlong,
    sql: JString<'local>,
    params: JByteArray<'local>,
    fetch_size: jint,
) -> jbyteArray {
    let sql = jstring_to_string(&mut env, &sql);
    let owned = match params_from_bytes(&mut env, &params) {
        Ok(owned) => owned,
        Err(err) => return finish(&mut env, params_error(err, std::ptr::null_mut())),
    };
    let result = RUNTIME.get().unwrap().block_on(engine(rt).stream_open(
        sql,
        owned,
        stream_fetch_size(fetch_size),
    ));
    finish(&mut env, result)
}

#[no_mangle]
pub extern "system" fn Java_io_github_smyrgeorge_sqlx4k_sqlite_cipher_CipherJni_nativeCnStreamOpen<
    'local,
>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    rt: jlong,
    cn: jlong,
    sql: JString<'local>,
    params: JByteArray<'local>,
    fetch_size: jint,
) -> jbyteArray {
    let sql = jstring_to_string(&mut env, &sql);
    let owned = match params_from_bytes(&mut env, &params) {
        Ok(owned) => owned,
        Err(err) => return finish(&mut env, params_error(err, std::ptr::null_mut())),
    };
    let result = RUNTIME.get().unwrap().block_on(engine(rt).cn_stream_open(
        ptr_of(cn),
        sql,
        owned,
        stream_fetch_size(fetch_size),
    ));
    finish(&mut env, result)
}

#[no_mangle]
pub extern "system" fn Java_io_github_smyrgeorge_sqlx4k_sqlite_cipher_CipherJni_nativeTxStreamOpen<
    'local,
>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    rt: jlong,
    tx: jlong,
    sql: JString<'local>,
    params: JByteArray<'local>,
    fetch_size: jint,
) -> jbyteArray {
    let sql = jstring_to_string(&mut env, &sql);
    let owned = match params_from_bytes(&mut env, &params) {
        Ok(owned) => owned,
        Err(err) => return finish(&mut env, params_error(err, ptr_of(tx).ptr)),
    };
    let result = RUNTIME.get().unwrap().block_on(engine(rt).tx_stream_open(
        ptr_of(tx),
        sql,
        owned,
        stream_fetch_size(fetch_size),
    ));
    finish(&mut env, result)
}

#[no_mangle]
pub extern "system" fn Java_io_github_smyrgeorge_sqlx4k_sqlite_cipher_CipherJni_nativeStreamNext<
    'local,
>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    rt: jlong,
    stream: jlong,
) -> jbyteArray {
    let result = RUNTIME
        .get()
        .unwrap()
        .block_on(engine(rt).stream_next(ptr_of(stream)));
    finish(&mut env, result)
}

#[no_mangle]
pub extern "system" fn Java_io_github_smyrgeorge_sqlx4k_sqlite_cipher_CipherJni_nativeStreamClose<
    'local,
>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    rt: jlong,
    stream: jlong,
) -> jbyteArray {
    let result = RUNTIME
        .get()
        .unwrap()
        .block_on(engine(rt).stream_close(ptr_of(stream)));
    finish(&mut env, result)
}

// ----------------------------------------------------------------------------
// Connection-level
// ----------------------------------------------------------------------------

#[no_mangle]
pub extern "system" fn Java_io_github_smyrgeorge_sqlx4k_sqlite_cipher_CipherJni_nativeCnAcquire<
    'local,
>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    rt: jlong,
) -> jbyteArray {
    let result = RUNTIME.get().unwrap().block_on(engine(rt).cn_acquire());
    finish(&mut env, result)
}

#[no_mangle]
pub extern "system" fn Java_io_github_smyrgeorge_sqlx4k_sqlite_cipher_CipherJni_nativeCnRelease<
    'local,
>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    rt: jlong,
    cn: jlong,
) -> jbyteArray {
    let result = RUNTIME
        .get()
        .unwrap()
        .block_on(engine(rt).cn_release(ptr_of(cn)));
    finish(&mut env, result)
}

#[no_mangle]
pub extern "system" fn Java_io_github_smyrgeorge_sqlx4k_sqlite_cipher_CipherJni_nativeCnQuery<
    'local,
>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    rt: jlong,
    cn: jlong,
    sql: JString<'local>,
) -> jbyteArray {
    let sql = jstring_to_string(&mut env, &sql);
    let result = RUNTIME
        .get()
        .unwrap()
        .block_on(engine(rt).cn_query(ptr_of(cn), sql));
    finish(&mut env, result)
}

#[no_mangle]
pub extern "system" fn Java_io_github_smyrgeorge_sqlx4k_sqlite_cipher_CipherJni_nativeCnFetchAll<
    'local,
>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    rt: jlong,
    cn: jlong,
    sql: JString<'local>,
) -> jbyteArray {
    let sql = jstring_to_string(&mut env, &sql);
    let result = RUNTIME
        .get()
        .unwrap()
        .block_on(engine(rt).cn_fetch_all(ptr_of(cn), sql));
    finish(&mut env, result)
}

#[no_mangle]
pub extern "system" fn Java_io_github_smyrgeorge_sqlx4k_sqlite_cipher_CipherJni_nativeCnQueryWithParams<
    'local,
>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    rt: jlong,
    cn: jlong,
    sql: JString<'local>,
    params: JByteArray<'local>,
) -> jbyteArray {
    let sql = jstring_to_string(&mut env, &sql);
    let owned = match params_from_bytes(&mut env, &params) {
        Ok(owned) => owned,
        Err(err) => return finish(&mut env, params_error(err, std::ptr::null_mut())),
    };
    let result = RUNTIME
        .get()
        .unwrap()
        .block_on(engine(rt).cn_query_with_params(ptr_of(cn), sql, owned));
    finish(&mut env, result)
}

#[no_mangle]
pub extern "system" fn Java_io_github_smyrgeorge_sqlx4k_sqlite_cipher_CipherJni_nativeCnFetchAllWithParams<
    'local,
>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    rt: jlong,
    cn: jlong,
    sql: JString<'local>,
    params: JByteArray<'local>,
) -> jbyteArray {
    let sql = jstring_to_string(&mut env, &sql);
    let owned = match params_from_bytes(&mut env, &params) {
        Ok(owned) => owned,
        Err(err) => return finish(&mut env, params_error(err, std::ptr::null_mut())),
    };
    let result = RUNTIME
        .get()
        .unwrap()
        .block_on(engine(rt).cn_fetch_all_with_params(ptr_of(cn), sql, owned));
    finish(&mut env, result)
}

#[no_mangle]
pub extern "system" fn Java_io_github_smyrgeorge_sqlx4k_sqlite_cipher_CipherJni_nativeCnTxBegin<
    'local,
>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    rt: jlong,
    cn: jlong,
) -> jbyteArray {
    let result = RUNTIME
        .get()
        .unwrap()
        .block_on(engine(rt).cn_tx_begin(ptr_of(cn)));
    finish(&mut env, result)
}

// ----------------------------------------------------------------------------
// Transaction-level
// ----------------------------------------------------------------------------

#[no_mangle]
pub extern "system" fn Java_io_github_smyrgeorge_sqlx4k_sqlite_cipher_CipherJni_nativeTxBegin<
    'local,
>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    rt: jlong,
) -> jbyteArray {
    let result = RUNTIME.get().unwrap().block_on(engine(rt).tx_begin());
    finish(&mut env, result)
}

#[no_mangle]
pub extern "system" fn Java_io_github_smyrgeorge_sqlx4k_sqlite_cipher_CipherJni_nativeTxCommit<
    'local,
>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    rt: jlong,
    tx: jlong,
) -> jbyteArray {
    let result = RUNTIME
        .get()
        .unwrap()
        .block_on(engine(rt).tx_commit(ptr_of(tx)));
    finish(&mut env, result)
}

#[no_mangle]
pub extern "system" fn Java_io_github_smyrgeorge_sqlx4k_sqlite_cipher_CipherJni_nativeTxRollback<
    'local,
>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    rt: jlong,
    tx: jlong,
) -> jbyteArray {
    let result = RUNTIME
        .get()
        .unwrap()
        .block_on(engine(rt).tx_rollback(ptr_of(tx)));
    finish(&mut env, result)
}

#[no_mangle]
pub extern "system" fn Java_io_github_smyrgeorge_sqlx4k_sqlite_cipher_CipherJni_nativeTxQuery<
    'local,
>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    rt: jlong,
    tx: jlong,
    sql: JString<'local>,
) -> jbyteArray {
    let sql = jstring_to_string(&mut env, &sql);
    let result = RUNTIME
        .get()
        .unwrap()
        .block_on(engine(rt).tx_query(ptr_of(tx), sql));
    finish(&mut env, result)
}

#[no_mangle]
pub extern "system" fn Java_io_github_smyrgeorge_sqlx4k_sqlite_cipher_CipherJni_nativeTxFetchAll<
    'local,
>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    rt: jlong,
    tx: jlong,
    sql: JString<'local>,
) -> jbyteArray {
    let sql = jstring_to_string(&mut env, &sql);
    let result = RUNTIME
        .get()
        .unwrap()
        .block_on(engine(rt).tx_fetch_all(ptr_of(tx), sql));
    finish(&mut env, result)
}

#[no_mangle]
pub extern "system" fn Java_io_github_smyrgeorge_sqlx4k_sqlite_cipher_CipherJni_nativeTxQueryWithParams<
    'local,
>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    rt: jlong,
    tx: jlong,
    sql: JString<'local>,
    params: JByteArray<'local>,
) -> jbyteArray {
    let sql = jstring_to_string(&mut env, &sql);
    let owned = match params_from_bytes(&mut env, &params) {
        Ok(owned) => owned,
        Err(err) => return finish(&mut env, params_error(err, ptr_of(tx).ptr)),
    };
    let result = RUNTIME
        .get()
        .unwrap()
        .block_on(engine(rt).tx_query_with_params(ptr_of(tx), sql, owned));
    finish(&mut env, result)
}

#[no_mangle]
pub extern "system" fn Java_io_github_smyrgeorge_sqlx4k_sqlite_cipher_CipherJni_nativeTxFetchAllWithParams<
    'local,
>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    rt: jlong,
    tx: jlong,
    sql: JString<'local>,
    params: JByteArray<'local>,
) -> jbyteArray {
    let sql = jstring_to_string(&mut env, &sql);
    let owned = match params_from_bytes(&mut env, &params) {
        Ok(owned) => owned,
        Err(err) => return finish(&mut env, params_error(err, ptr_of(tx).ptr)),
    };
    let result = RUNTIME
        .get()
        .unwrap()
        .block_on(engine(rt).tx_fetch_all_with_params(ptr_of(tx), sql, owned));
    finish(&mut env, result)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a_cursor_reads_the_big_endian_values_in_order() {
        let mut b = vec![9];
        b.extend(7i32.to_be_bytes());
        b.extend((-5i64).to_be_bytes());
        b.extend(1.5f64.to_be_bytes());
        b.extend(3i32.to_be_bytes());
        b.extend(b"a\0b");
        b.extend(2i32.to_be_bytes());
        b.extend("é".as_bytes());
        // A negative length reads as empty.
        b.extend((-1i32).to_be_bytes());
        let mut c = Cursor { b: &b, pos: 0 };
        assert_eq!(c.u8(), 9);
        assert_eq!(c.i32(), 7);
        assert_eq!(c.i64(), -5);
        assert_eq!(c.f64(), 1.5);
        assert_eq!(c.bytes(), b"a\0b");
        assert_eq!(c.string(), "é");
        assert!(c.bytes().is_empty());
        assert_eq!(c.pos, b.len());
    }

    #[test]
    #[should_panic(expected = "Invalid UTF-8 in TEXT parameter")]
    fn a_text_of_invalid_utf8_panics() {
        let mut b = 1i32.to_be_bytes().to_vec();
        b.push(0xff);
        Cursor { b: &b, pos: 0 }.string();
    }

    /// A parameter buffer of the given kinds, each followed by its value bytes.
    fn buffer(params: &[(u8, &[u8])]) -> Vec<u8> {
        let mut b = (params.len() as i32).to_be_bytes().to_vec();
        for (kind, value) in params {
            b.push(*kind);
            b.extend(*value);
        }
        b
    }

    #[test]
    fn every_kind_of_parameter_is_decoded() {
        let text = [&2i32.to_be_bytes()[..], b"hi"].concat();
        let blob = [&1i32.to_be_bytes()[..], &[0xff]].concat();
        let b = buffer(&[
            (PARAM_NULL as u8, &[]),
            (PARAM_INT as u8, &(-3i64).to_be_bytes()),
            (PARAM_REAL as u8, &2.5f64.to_be_bytes()),
            (PARAM_TEXT as u8, &text),
            (PARAM_BLOB as u8, &blob),
        ]);
        let params = params_of(&b).unwrap();
        assert!(matches!(params[0], OwnedParam::Null));
        assert!(matches!(params[1], OwnedParam::Int(-3)));
        assert!(matches!(params[2], OwnedParam::Real(v) if v == 2.5));
        assert!(matches!(&params[3], OwnedParam::Text(v) if v == "hi"));
        assert!(matches!(&params[4], OwnedParam::Blob(v) if v == &[0xff]));
    }

    #[test]
    fn an_unknown_kind_of_parameter_is_an_error() {
        let b = buffer(&[(PARAM_INT as u8, &1i64.to_be_bytes()), (9, &[])]);
        assert_eq!(params_of(&b).err().as_deref(), Some("Unknown parameter kind: 9"));
    }

    #[test]
    fn a_pointer_crosses_as_a_long() {
        let mut value = 7;
        let handle = &mut value as *mut i32 as usize as jlong;
        assert_eq!(ptr_of(handle).ptr, &mut value as *mut i32 as *mut c_void);
        assert!(ptr_of(0).ptr.is_null());
    }
}
