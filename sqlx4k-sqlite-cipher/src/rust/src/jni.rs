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
fn params_from_bytes(env: &mut JNIEnv, array: &JByteArray) -> Vec<OwnedParam> {
    if array.as_raw().is_null() {
        return Vec::new();
    }
    let bytes = env
        .convert_byte_array(array)
        .expect("Failed to read parameter byte array");
    if bytes.is_empty() {
        return Vec::new();
    }
    let mut c = Cursor { b: &bytes, pos: 0 };
    let count = c.i32().max(0);
    let mut out = Vec::with_capacity(count as usize);
    for _ in 0..count {
        let kind = c.u8() as i32;
        let p = match kind {
            PARAM_INT => OwnedParam::Int(c.i64()),
            PARAM_REAL => OwnedParam::Real(c.f64()),
            PARAM_TEXT => OwnedParam::Text(c.string()),
            PARAM_BLOB => OwnedParam::Blob(c.bytes()),
            _ => OwnedParam::Null,
        };
        out.push(p);
    }
    out
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
    let owned = params_from_bytes(&mut env, &params);
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
    let owned = params_from_bytes(&mut env, &params);
    let result = RUNTIME
        .get()
        .unwrap()
        .block_on(engine(rt).fetch_all_with_params(sql, owned));
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
    let owned = params_from_bytes(&mut env, &params);
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
    let owned = params_from_bytes(&mut env, &params);
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
    let owned = params_from_bytes(&mut env, &params);
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
    let owned = params_from_bytes(&mut env, &params);
    let result = RUNTIME
        .get()
        .unwrap()
        .block_on(engine(rt).tx_fetch_all_with_params(ptr_of(tx), sql, owned));
    finish(&mut env, result)
}
