//! The C structs handed across the FFI boundary, and the result and error-kind codes they carry.

use std::ffi::{c_char, c_int, c_ulonglong, c_void};
use std::ptr::null_mut;

use crate::params::Sqlx4kSqliteParam;

pub const OK: c_int = -1;

pub const ERROR_DATABASE: c_int = 0;

pub const ERROR_POOL_TIMED_OUT: c_int = 1;

pub const ERROR_POOL_CLOSED: c_int = 2;

pub const ERROR_WORKER_CRASHED: c_int = 3;

pub const ERROR_POOL: c_int = 5;

/// Reported in `error_native_code` when the database has no numeric error code.
pub const NO_NATIVE_CODE: c_int = -1;

// `SQLError.Kind` ordinals (IMPORTANT: keep in sync with the Kotlin enum; do not reorder).
pub const KIND_UNIQUE_VIOLATION: c_int = 0;

pub const KIND_FOREIGN_KEY_VIOLATION: c_int = 1;

pub const KIND_NOT_NULL_VIOLATION: c_int = 2;

pub const KIND_CHECK_VIOLATION: c_int = 3;

pub const KIND_EXCLUSION_VIOLATION: c_int = 4;

// Not reported by SQLite, kept so that the ordinals match the Kotlin enum.
#[allow(dead_code)]
pub const KIND_DEADLOCK: c_int = 5;

// Not reported by SQLite, kept so that the ordinals match the Kotlin enum.
#[allow(dead_code)]
pub const KIND_SERIALIZATION_FAILURE: c_int = 6;

pub const KIND_LOCK_TIMEOUT: c_int = 7;

pub const KIND_OTHER: c_int = 8;

#[repr(C)]
pub struct Sqlx4kSqlitePtr {
    pub ptr: *mut c_void,
}

unsafe impl Send for Sqlx4kSqlitePtr {}

unsafe impl Sync for Sqlx4kSqlitePtr {}

impl Sqlx4kSqlitePtr {
    /// The wrapped pointer. Going through a method (rather than the `ptr` field) inside a spawned
    /// task captures the whole `Send` wrapper, not the raw pointer field (edition-2021 captures).
    pub(crate) fn raw(&self) -> *mut c_void {
        self.ptr
    }
}

#[repr(C)]
pub struct Sqlx4kSqliteResult {
    pub error: c_int,
    pub error_message: *mut c_char,
    /// SQLSTATE reported by the database, or null (SQLite has none).
    pub error_sql_state: *mut c_char,
    /// The database's own numeric error code, or `NO_NATIVE_CODE` (PostgreSQL has none).
    pub error_native_code: c_int,
    /// `SQLError.Kind` ordinal (`KIND_*`).
    pub error_kind: c_int,
    pub rows_affected: c_ulonglong,
    /// The rowid of the last row inserted on the connection (SQLite tracks it per connection, not per statement).
    pub last_insert_id: i64,
    pub cn: *mut c_void,
    pub tx: *mut c_void,
    pub rt: *mut c_void,
    /// A row stream opened by `sqlx4k_sqlite_*_stream_open` (see `Sqlx4kSqliteStream`), or null.
    pub stream: *mut c_void,
    pub schema: *mut Sqlx4kSqliteSchema,
    pub size: c_int,
    pub rows: *mut Sqlx4kSqliteRow,
}

impl Sqlx4kSqliteResult {
    pub fn leak(self) -> *mut Sqlx4kSqliteResult {
        let result = Box::new(self);
        let result = Box::leak(result);
        result
    }
}

impl Default for Sqlx4kSqliteResult {
    fn default() -> Self {
        Self {
            error: OK,
            error_message: null_mut(),
            error_sql_state: null_mut(),
            error_native_code: NO_NATIVE_CODE,
            error_kind: KIND_OTHER,
            rows_affected: 0,
            last_insert_id: 0,
            cn: null_mut(),
            tx: null_mut(),
            rt: null_mut(),
            stream: null_mut(),
            schema: null_mut(),
            size: 0,
            rows: null_mut(),
        }
    }
}

#[repr(C)]
pub struct Sqlx4kSqliteSchema {
    pub size: c_int,
    pub columns: *mut Sqlx4kSqliteSchemaColumn,
}

impl Default for Sqlx4kSqliteSchema {
    fn default() -> Self {
        Self {
            size: 0,
            columns: null_mut(),
        }
    }
}

#[repr(C)]
pub struct Sqlx4kSqliteSchemaColumn {
    pub ordinal: c_int,
    pub name: *mut c_char,
    pub kind: *mut c_char,
}

#[repr(C)]
pub struct Sqlx4kSqliteRow {
    pub size: c_int,
    pub columns: *mut Sqlx4kSqliteColumn,
}

impl Default for Sqlx4kSqliteRow {
    fn default() -> Self {
        Self {
            size: 0,
            columns: null_mut(),
        }
    }
}

#[repr(C)]
pub struct Sqlx4kSqliteColumn {
    pub ordinal: c_int,
    /// NUL-terminated, but TEXT may itself contain NUL bytes: read `value_len` bytes.
    pub value: *mut c_char,
    /// Length of `value` in bytes, excluding the terminator.
    pub value_len: c_int,
}

#[no_mangle]
pub extern "C" fn auto_generated_for_struct_sqlite_Sqlx4kSqlitePtr(_: Sqlx4kSqlitePtr) {}

#[no_mangle]
pub extern "C" fn auto_generated_for_struct_sqlite_Sqlx4kSqliteResult(_: Sqlx4kSqliteResult) {}

#[no_mangle]
pub extern "C" fn auto_generated_for_struct_sqlite_Sqlx4kSqliteParam(_: Sqlx4kSqliteParam) {}
