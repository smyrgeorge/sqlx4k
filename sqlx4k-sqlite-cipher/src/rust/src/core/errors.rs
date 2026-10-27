//! Turning a sqlx error into an error result (code, message, native code, kind).

use sqlx::error::ErrorKind;
use sqlx::Error;
use std::ffi::{c_int, CString};
use std::ptr::null_mut;

use crate::core::types::{
    Sqlx4kSqliteCipherResult, ERROR_DATABASE, ERROR_POOL, ERROR_POOL_CLOSED, ERROR_POOL_TIMED_OUT,
    ERROR_WORKER_CRASHED, KIND_CHECK_VIOLATION, KIND_EXCLUSION_VIOLATION,
    KIND_FOREIGN_KEY_VIOLATION, KIND_LOCK_TIMEOUT, KIND_NOT_NULL_VIOLATION, KIND_OTHER,
    KIND_UNIQUE_VIOLATION, NO_NATIVE_CODE,
};

pub fn error_result_of(err: sqlx::Error) -> Sqlx4kSqliteCipherResult {
    // A database error also carries what SQLite reported: the extended result code (which sqlx
    // exposes through `code()` as a decimal string) and a portable kind (unique violation, foreign
    // key violation, ...), surfaced on `SQLError`. SQLite has no SQLSTATE.
    let (sql_state, native_code, kind): (Option<String>, c_int, c_int) = match &err {
        Error::Database(e) => {
            let code = e.code().and_then(|c| c.parse::<c_int>().ok());
            let kind = kind_of(e.kind(), code);
            (None, code.unwrap_or(NO_NATIVE_CODE), kind)
        }
        _ => (None, NO_NATIVE_CODE, KIND_OTHER),
    };

    let (code, message) = match err {
        Error::Configuration(e) => (ERROR_POOL, format!("Invalid SQLite URL :: {}", e)),
        Error::Database(e) => match e.code() {
            Some(code) => (ERROR_DATABASE, format!("[{}] {}", code, e.to_string())),
            None => (ERROR_DATABASE, format!("{}", e.to_string())),
        },
        Error::PoolTimedOut => (ERROR_POOL_TIMED_OUT, "PoolTimedOut".to_string()),
        Error::PoolClosed => (
            ERROR_POOL_CLOSED,
            "The connection pool is already closed".to_string(),
        ),
        Error::WorkerCrashed => (ERROR_WORKER_CRASHED, "WorkerCrashed".to_string()),
        // Any other error (Io, Protocol, Decode, ...) is reported as a database error.
        // Never panic here: with `panic = "abort"`, a failed I/O operation would terminate the process.
        e => (ERROR_DATABASE, e.to_string()),
    };

    // CString::new fails on interior NUL bytes, which may appear in values echoed back by the database.
    let message = CString::new(message.replace('\0', "")).unwrap();
    let sql_state = sql_state
        .map(|s| CString::new(s.replace('\0', "")).unwrap().into_raw())
        .unwrap_or(null_mut());
    Sqlx4kSqliteCipherResult {
        error: code,
        error_message: message.into_raw(),
        error_sql_state: sql_state,
        error_native_code: native_code,
        error_kind: kind,
        ..Default::default()
    }
}

/// Maps sqlx's portable [`ErrorKind`] (constraint violations) to the `SQLError.Kind` ordinal, or
/// `None` when sqlx does not classify the error.
pub(crate) fn constraint_kind_of(kind: ErrorKind) -> Option<c_int> {
    match kind {
        ErrorKind::UniqueViolation => Some(KIND_UNIQUE_VIOLATION),
        ErrorKind::ForeignKeyViolation => Some(KIND_FOREIGN_KEY_VIOLATION),
        ErrorKind::NotNullViolation => Some(KIND_NOT_NULL_VIOLATION),
        ErrorKind::CheckViolation => Some(KIND_CHECK_VIOLATION),
        ErrorKind::ExclusionViolation => Some(KIND_EXCLUSION_VIOLATION),
        // `ErrorKind` is `#[non_exhaustive]`.
        _ => None,
    }
}

/// Classifies a SQLite error as a `SQLError.Kind` ordinal: constraint violations come from sqlx,
/// lock failures from the primary result code (the low byte of the extended code). Mirrors
/// `sqliteKindOf` in the Kotlin driver.
/// https://www.sqlite.org/rescode.html
pub(crate) fn kind_of(kind: ErrorKind, code: Option<c_int>) -> c_int {
    if let Some(kind) = constraint_kind_of(kind) {
        return kind;
    }
    match code.map(|c| c & 0xff) {
        // SQLITE_BUSY, SQLITE_LOCKED
        Some(5 | 6) => KIND_LOCK_TIMEOUT,
        _ => KIND_OTHER,
    }
}
