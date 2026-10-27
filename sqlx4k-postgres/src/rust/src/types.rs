//! The C structs handed across the FFI boundary, and the result and error-kind codes they carry.

use std::ffi::{c_char, c_int, c_ulonglong, c_void};
use std::ptr::null_mut;

use crate::params::Sqlx4kPostgresParam;

pub const OK: c_int = -1;

pub const ERROR_DATABASE: c_int = 0;

pub const ERROR_POOL_TIMED_OUT: c_int = 1;

pub const ERROR_POOL_CLOSED: c_int = 2;

pub const ERROR_WORKER_CRASHED: c_int = 3;

/// Reported in `error_native_code` when the database has no numeric error code.
pub const NO_NATIVE_CODE: c_int = -1;

// `SQLError.Kind` ordinals (IMPORTANT: keep in sync with the Kotlin enum; do not reorder).
pub const KIND_UNIQUE_VIOLATION: c_int = 0;

pub const KIND_FOREIGN_KEY_VIOLATION: c_int = 1;

pub const KIND_NOT_NULL_VIOLATION: c_int = 2;

pub const KIND_CHECK_VIOLATION: c_int = 3;

pub const KIND_EXCLUSION_VIOLATION: c_int = 4;

pub const KIND_DEADLOCK: c_int = 5;

pub const KIND_SERIALIZATION_FAILURE: c_int = 6;

pub const KIND_LOCK_TIMEOUT: c_int = 7;

pub const KIND_OTHER: c_int = 8;

#[repr(C)]
pub struct Sqlx4kPostgresPtr {
    pub ptr: *mut c_void,
}

unsafe impl Send for Sqlx4kPostgresPtr {}

unsafe impl Sync for Sqlx4kPostgresPtr {}

impl Sqlx4kPostgresPtr {
    /// The wrapped pointer. Going through a method (rather than the `ptr` field) inside a spawned
    /// task captures the whole `Send` wrapper, not the raw pointer field (edition-2021 captures).
    pub(crate) fn raw(&self) -> *mut c_void {
        self.ptr
    }
}

#[repr(C)]
pub struct Sqlx4kPostgresResult {
    pub error: c_int,
    pub error_message: *mut c_char,
    /// SQLSTATE reported by the database, or null (SQLite has none).
    pub error_sql_state: *mut c_char,
    /// The database's own numeric error code, or `NO_NATIVE_CODE` (PostgreSQL has none).
    pub error_native_code: c_int,
    /// `SQLError.Kind` ordinal (`KIND_*`).
    pub error_kind: c_int,
    pub rows_affected: c_ulonglong,
    pub cn: *mut c_void,
    pub tx: *mut c_void,
    pub rt: *mut c_void,
    /// A row stream opened by `sqlx4k_postgresql_*_stream_open` (see `Sqlx4kPostgresStream`), or null.
    pub stream: *mut c_void,
    pub schema: *mut Sqlx4kPostgresSchema,
    pub size: c_int,
    pub rows: *mut Sqlx4kPostgresRow,
}

impl Sqlx4kPostgresResult {
    pub fn leak(self) -> *mut Sqlx4kPostgresResult {
        let result = Box::new(self);
        let result = Box::leak(result);
        result
    }
}

impl Default for Sqlx4kPostgresResult {
    fn default() -> Self {
        Self {
            error: OK,
            error_message: null_mut(),
            error_sql_state: null_mut(),
            error_native_code: NO_NATIVE_CODE,
            error_kind: KIND_OTHER,
            rows_affected: 0,
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
pub struct Sqlx4kPostgresSchema {
    pub size: c_int,
    pub columns: *mut Sqlx4kPostgresSchemaColumn,
}

impl Default for Sqlx4kPostgresSchema {
    fn default() -> Self {
        Self {
            size: 0,
            columns: null_mut(),
        }
    }
}

#[repr(C)]
pub struct Sqlx4kPostgresSchemaColumn {
    pub ordinal: c_int,
    pub name: *mut c_char,
    pub kind: *mut c_char,
}

#[repr(C)]
pub struct Sqlx4kPostgresRow {
    pub size: c_int,
    pub columns: *mut Sqlx4kPostgresColumn,
}

impl Default for Sqlx4kPostgresRow {
    fn default() -> Self {
        Self {
            size: 0,
            columns: null_mut(),
        }
    }
}

#[repr(C)]
pub struct Sqlx4kPostgresColumn {
    pub ordinal: c_int,
    pub value: *mut c_char,
}

#[no_mangle]
pub extern "C" fn auto_generated_for_struct_postgres_Sqlx4kPostgresPtr(_: Sqlx4kPostgresPtr) {}

#[no_mangle]
pub extern "C" fn auto_generated_for_struct_postgres_Sqlx4kPostgresResult(_: Sqlx4kPostgresResult) {
}

#[no_mangle]
pub extern "C" fn auto_generated_for_struct_postgres_Sqlx4kPostgresParam(_: Sqlx4kPostgresParam) {}
