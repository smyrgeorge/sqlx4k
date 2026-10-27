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

#[cfg(test)]
mod tests {
    use super::*;
    use crate::test_support::{block_on, memory, text, TempDb};
    use sqlx::{ConnectOptions, Connection, Executor};
    use std::time::Duration;

    fn mapped(err: Error) -> (c_int, String, Option<String>, c_int, c_int) {
        let r = error_result_of(err);
        let mapped = (r.error, text(r.error_message).unwrap(), text(r.error_sql_state), r.error_native_code, r.error_kind);
        crate::core::result::free_result(r.leak());
        mapped
    }

    #[test]
    fn pool_errors_have_codes_of_their_own() {
        assert_eq!(mapped(Error::PoolTimedOut), (ERROR_POOL_TIMED_OUT, "PoolTimedOut".into(), None, NO_NATIVE_CODE, KIND_OTHER));
        assert_eq!(
            mapped(Error::PoolClosed),
            (ERROR_POOL_CLOSED, "The connection pool is already closed".into(), None, NO_NATIVE_CODE, KIND_OTHER)
        );
        assert_eq!(mapped(Error::WorkerCrashed), (ERROR_WORKER_CRASHED, "WorkerCrashed".into(), None, NO_NATIVE_CODE, KIND_OTHER));
    }

    #[test]
    fn an_invalid_url_is_a_pool_error() {
        let (code, message, sql_state, native, kind) = mapped(Error::Configuration("bad mode".into()));
        assert_eq!((code, message.as_str()), (ERROR_POOL, "Invalid SQLite URL :: bad mode"));
        assert_eq!((sql_state, native, kind), (None, NO_NATIVE_CODE, KIND_OTHER));
    }

    #[test]
    fn other_errors_are_database_errors() {
        let (code, message, sql_state, native, kind) = mapped(Error::Protocol("unexpected".into()));
        assert_eq!(code, ERROR_DATABASE);
        assert!(message.contains("unexpected"), "{message}");
        assert_eq!((sql_state, native, kind), (None, NO_NATIVE_CODE, KIND_OTHER));
    }

    #[test]
    fn a_nul_in_a_message_is_dropped() {
        let (_, message, ..) = mapped(Error::Protocol("a\0b".into()));
        assert!(message.contains("ab"), "{message}");
    }

    #[test]
    fn constraint_violations_are_classified_by_sqlx() {
        let kinds = || {
            [
                (ErrorKind::UniqueViolation, KIND_UNIQUE_VIOLATION),
                (ErrorKind::ForeignKeyViolation, KIND_FOREIGN_KEY_VIOLATION),
                (ErrorKind::NotNullViolation, KIND_NOT_NULL_VIOLATION),
                (ErrorKind::CheckViolation, KIND_CHECK_VIOLATION),
                (ErrorKind::ExclusionViolation, KIND_EXCLUSION_VIOLATION),
            ]
        };
        for (kind, ours) in kinds() {
            assert_eq!(constraint_kind_of(kind), Some(ours));
        }
        // The constraint wins over the result code.
        for (kind, ours) in kinds() {
            assert_eq!(kind_of(kind, Some(5)), ours);
        }
        assert_eq!(constraint_kind_of(ErrorKind::Other), None);
    }

    #[test]
    fn lock_failures_are_classified_by_primary_result_code() {
        // SQLITE_BUSY, SQLITE_LOCKED, and extended codes of theirs (SQLITE_BUSY_SNAPSHOT, SQLITE_LOCKED_SHAREDCACHE);
        // not SQLITE_ERROR, SQLITE_CONSTRAINT or SQLITE_IOERR_READ.
        for code in [5, 6, 517, 262] {
            assert_eq!(kind_of(ErrorKind::Other, Some(code)), KIND_LOCK_TIMEOUT, "{code}");
        }
        for code in [Some(1), Some(19), Some(266), None] {
            assert_eq!(kind_of(ErrorKind::Other, code), KIND_OTHER, "{code:?}");
        }
    }

    #[test]
    fn a_database_error_carries_its_extended_code_and_kind() {
        block_on(async {
            let mut cn = memory().await;
            cn.execute("create table p(id integer primary key)").await.unwrap();
            cn.execute(
                "create table t(id integer primary key, u int unique, v int not null check (v > 0), p int references p(id))",
            )
            .await
            .unwrap();
            cn.execute("insert into t values (1, 1, 1, null)").await.unwrap();
            for (sql, native, kind) in [
                // SQLITE_CONSTRAINT_PRIMARYKEY, _UNIQUE, _NOTNULL, _CHECK, _FOREIGNKEY
                ("insert into t values (1, 2, 1, null)", 1555, KIND_UNIQUE_VIOLATION),
                ("insert into t values (2, 1, 1, null)", 2067, KIND_UNIQUE_VIOLATION),
                ("insert into t values (2, 2, null, null)", 1299, KIND_NOT_NULL_VIOLATION),
                ("insert into t values (2, 2, 0, null)", 275, KIND_CHECK_VIOLATION),
                ("insert into t values (2, 2, 1, 9)", 787, KIND_FOREIGN_KEY_VIOLATION),
                // SQLITE_ERROR
                ("select * from missing_table", 1, KIND_OTHER),
            ] {
                let err = cn.execute(sql).await.unwrap_err();
                let (code, message, sql_state, n, k) = mapped(err);
                // SQLite has no SQLSTATE.
                assert_eq!((code, sql_state, n, k), (ERROR_DATABASE, None, native, kind), "{sql}");
                assert!(message.starts_with(&format!("[{native}] ")), "{message}");
            }
            cn.close().await.unwrap();
        });
    }

    #[test]
    fn a_busy_database_is_a_lock_timeout() {
        let db = TempDb::new();
        block_on(async {
            let options = db.connect_options().busy_timeout(Duration::ZERO);
            let mut a = options.connect().await.unwrap();
            let mut b = options.connect().await.unwrap();
            a.execute("begin immediate").await.unwrap();
            let (code, _, _, native, kind) = mapped(b.execute("begin immediate").await.unwrap_err());
            assert_eq!((code, native, kind), (ERROR_DATABASE, 5, KIND_LOCK_TIMEOUT));
            a.execute("rollback").await.unwrap();
            a.close().await.unwrap();
            b.close().await.unwrap();
        });
    }
}
