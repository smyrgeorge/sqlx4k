//! Turning a sqlx error into an error result (code, message, SQLSTATE, kind).

use sqlx::error::ErrorKind;
use sqlx::Error;
use std::ffi::{c_int, CString};
use std::ptr::null_mut;

use crate::types::{
    Sqlx4kPostgresResult, ERROR_DATABASE, ERROR_POOL_CLOSED, ERROR_POOL_TIMED_OUT,
    ERROR_WORKER_CRASHED, KIND_CHECK_VIOLATION, KIND_DEADLOCK, KIND_EXCLUSION_VIOLATION,
    KIND_FOREIGN_KEY_VIOLATION, KIND_LOCK_TIMEOUT, KIND_NOT_NULL_VIOLATION, KIND_OTHER,
    KIND_SERIALIZATION_FAILURE, KIND_UNIQUE_VIOLATION, NO_NATIVE_CODE,
};

pub fn sqlx4k_postgres_error_result_of(err: sqlx::Error) -> Sqlx4kPostgresResult {
    // A database error also carries what the server reported: the SQLSTATE and a portable kind
    // (unique violation, foreign key violation, ...), surfaced on `SQLError`. PostgreSQL has no
    // numeric error code: the SQLSTATE is the code.
    let (sql_state, native_code, kind): (Option<String>, c_int, c_int) = match &err {
        Error::Database(e) => {
            let sql_state = e.code().map(|c| c.to_string());
            let kind = sqlx4k_postgres_kind_of(e.kind(), sql_state.as_deref());
            (sql_state, NO_NATIVE_CODE, kind)
        }
        _ => (None, NO_NATIVE_CODE, KIND_OTHER),
    };

    let (code, message) = match err {
        Error::Database(e) => match e.code() {
            Some(code) => (ERROR_DATABASE, format!("[{code}] {e}")),
            None => (ERROR_DATABASE, e.to_string()),
        },
        Error::PoolTimedOut => (ERROR_POOL_TIMED_OUT, "PoolTimedOut".to_string()),
        Error::PoolClosed => (
            ERROR_POOL_CLOSED,
            "The connection pool is already closed".to_string(),
        ),
        Error::WorkerCrashed => (ERROR_WORKER_CRASHED, "WorkerCrashed".to_string()),
        // Any other error (Io, Tls, Protocol, Decode, ...) is reported as a database error.
        // Never panic here: with `panic = "abort"`, a dropped connection would terminate the process.
        e => (ERROR_DATABASE, e.to_string()),
    };

    // CString::new fails on interior NUL bytes, which may appear in values echoed back by the server.
    let message = CString::new(message.replace('\0', "")).unwrap();
    let sql_state = sql_state
        .map(|s| CString::new(s.replace('\0', "")).unwrap().into_raw())
        .unwrap_or(null_mut());
    Sqlx4kPostgresResult {
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
pub(crate) fn sqlx4k_postgres_constraint_kind_of(kind: ErrorKind) -> Option<c_int> {
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

/// Classifies a PostgreSQL error as a `SQLError.Kind` ordinal: constraint violations come from sqlx,
/// concurrency failures from the SQLSTATE. Mirrors `postgresKindOf` in the JVM driver.
/// <https://www.postgresql.org/docs/current/errcodes-appendix.html>
pub(crate) fn sqlx4k_postgres_kind_of(kind: ErrorKind, sql_state: Option<&str>) -> c_int {
    if let Some(kind) = sqlx4k_postgres_constraint_kind_of(kind) {
        return kind;
    }
    match sql_state {
        // deadlock_detected, serialization_failure, lock_not_available
        Some("40P01") => KIND_DEADLOCK,
        Some("40001") => KIND_SERIALIZATION_FAILURE,
        Some("55P03") => KIND_LOCK_TIMEOUT,
        _ => KIND_OTHER,
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::test_support::{block_on, connect_options, text};
    use crate::types::OK;
    use sqlx::{Connection, Executor, PgConnection};

    fn mapped(err: Error) -> (c_int, String, Option<String>, c_int, c_int) {
        let r = sqlx4k_postgres_error_result_of(err);
        let mapped = (
            r.error,
            text(r.error_message).unwrap(),
            text(r.error_sql_state),
            r.error_native_code,
            r.error_kind,
        );
        crate::result::sqlx4k_postgres_free_result(r.leak());
        mapped
    }

    #[test]
    fn pool_errors_have_codes_of_their_own() {
        assert_eq!(
            mapped(Error::PoolTimedOut),
            (
                ERROR_POOL_TIMED_OUT,
                "PoolTimedOut".into(),
                None,
                NO_NATIVE_CODE,
                KIND_OTHER
            )
        );
        assert_eq!(
            mapped(Error::PoolClosed),
            (
                ERROR_POOL_CLOSED,
                "The connection pool is already closed".into(),
                None,
                NO_NATIVE_CODE,
                KIND_OTHER
            )
        );
        assert_eq!(
            mapped(Error::WorkerCrashed),
            (
                ERROR_WORKER_CRASHED,
                "WorkerCrashed".into(),
                None,
                NO_NATIVE_CODE,
                KIND_OTHER
            )
        );
    }

    #[test]
    fn other_errors_are_database_errors() {
        let (code, message, sql_state, _, kind) =
            mapped(Error::Protocol("unexpected message".into()));
        assert_eq!(code, ERROR_DATABASE);
        assert!(message.contains("unexpected message"), "{message}");
        assert_eq!((sql_state, kind), (None, KIND_OTHER));
        assert_ne!(code, OK);
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
            assert_eq!(sqlx4k_postgres_constraint_kind_of(kind), Some(ours));
        }
        // The constraint wins over the SQLSTATE.
        for (kind, ours) in kinds() {
            assert_eq!(sqlx4k_postgres_kind_of(kind, Some("40P01")), ours);
        }
        assert_eq!(sqlx4k_postgres_constraint_kind_of(ErrorKind::Other), None);
    }

    #[test]
    fn concurrency_failures_are_classified_by_sqlstate() {
        assert_eq!(
            sqlx4k_postgres_kind_of(ErrorKind::Other, Some("40P01")),
            KIND_DEADLOCK
        );
        assert_eq!(
            sqlx4k_postgres_kind_of(ErrorKind::Other, Some("40001")),
            KIND_SERIALIZATION_FAILURE
        );
        assert_eq!(
            sqlx4k_postgres_kind_of(ErrorKind::Other, Some("55P03")),
            KIND_LOCK_TIMEOUT
        );
        assert_eq!(
            sqlx4k_postgres_kind_of(ErrorKind::Other, Some("42P01")),
            KIND_OTHER
        );
        assert_eq!(sqlx4k_postgres_kind_of(ErrorKind::Other, None), KIND_OTHER);
    }

    #[test]
    #[ignore = "needs the PostgreSQL of docker-compose.yml"]
    fn a_database_error_carries_its_sqlstate_and_kind() {
        block_on(async {
            let mut cn = PgConnection::connect_with(&connect_options())
                .await
                .unwrap();
            cn.execute("create temp table t(id int primary key, v int not null check (v > 0))")
                .await
                .unwrap();
            cn.execute("insert into t values (1, 1)").await.unwrap();
            for (sql, sql_state, kind) in [
                (
                    "insert into t values (1, 1)",
                    "23505",
                    KIND_UNIQUE_VIOLATION,
                ),
                (
                    "insert into t values (2, null)",
                    "23502",
                    KIND_NOT_NULL_VIOLATION,
                ),
                ("insert into t values (3, 0)", "23514", KIND_CHECK_VIOLATION),
                ("select * from missing_table", "42P01", KIND_OTHER),
            ] {
                let err = cn.execute(sql).await.unwrap_err();
                let (code, message, state, native, k) = mapped(err);
                assert_eq!(
                    (code, state.as_deref(), native, k),
                    (ERROR_DATABASE, Some(sql_state), NO_NATIVE_CODE, kind)
                );
                assert!(message.starts_with(&format!("[{sql_state}] ")), "{message}");
            }
            cn.close().await.unwrap();
        });
    }
}
