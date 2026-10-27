//! What the tests share: the PostgreSQL of docker-compose.yml, the exports called as Kotlin calls them (waiting for
//! the result they deliver), and the results read back.
//!
//! The tests that need the database are `#[ignore]`d, so that `cargo test` passes without it: run them with
//! `cargo test -- --include-ignored`, with `docker compose up postgres` (or `SQLX4K_TEST_POSTGRES_URL` set).

use std::ffi::{c_char, c_int, c_void, CStr, CString};
use std::ptr::null;
use std::sync::atomic::{AtomicUsize, Ordering};
use std::sync::mpsc;
use std::time::Duration;

use sqlx::postgres::PgConnectOptions;

use crate::ffi::pool::{sqlx4k_postgresql_close, sqlx4k_postgresql_of};
use crate::params::{Sqlx4kPostgresParam, PARAM_NULL};
use crate::result::sqlx4k_postgres_free_result;
use crate::types::{Sqlx4kPostgresPtr, Sqlx4kPostgresResult, OK};

/// The database of docker-compose.yml (`SQLX4K_TEST_POSTGRES_URL` overrides it); its user and password.
pub(crate) fn url() -> String {
    std::env::var("SQLX4K_TEST_POSTGRES_URL")
        .unwrap_or_else(|_| "postgres://localhost:15432/test".to_string())
}
pub(crate) const USERNAME: &str = "postgres";
pub(crate) const PASSWORD: &str = "postgres";

pub(crate) fn connect_options() -> PgConnectOptions {
    url()
        .parse::<PgConnectOptions>()
        .unwrap()
        .username(USERNAME)
        .password(PASSWORD)
}

/// Runs a future to completion on the tests' own runtime (not the driver's).
pub(crate) fn block_on<F: std::future::Future>(f: F) -> F::Output {
    tokio::runtime::Builder::new_current_thread()
        .enable_all()
        .build()
        .unwrap()
        .block_on(f)
}

pub(crate) fn c(s: &str) -> CString {
    CString::new(s).unwrap()
}

/// The text of a C string, or `None` for a null pointer.
pub(crate) fn text(p: *const c_char) -> Option<String> {
    if p.is_null() {
        None
    } else {
        Some(unsafe { CStr::from_ptr(p) }.to_string_lossy().into_owned())
    }
}

/// A name no other test (or run) uses, for the tables a test creates.
pub(crate) fn unique(prefix: &str) -> String {
    static NEXT: AtomicUsize = AtomicUsize::new(0);
    let nanos = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .unwrap()
        .subsec_nanos();
    format!(
        "{prefix}_{}_{}_{nanos}",
        std::process::id(),
        NEXT.fetch_add(1, Ordering::Relaxed)
    )
}

/// A result handed over by an export, freed when dropped (the handles it carries are not).
pub(crate) struct Res(pub(crate) *mut Sqlx4kPostgresResult);

impl Res {
    pub(crate) fn get(&self) -> &Sqlx4kPostgresResult {
        unsafe { &*self.0 }
    }

    pub(crate) fn is_ok(&self) -> bool {
        self.get().error == OK
    }

    /// The result, which must not be an error.
    pub(crate) fn ok(self) -> Self {
        assert!(self.is_ok(), "unexpected error: {:?}", self.error_message());
        self
    }

    pub(crate) fn error_message(&self) -> Option<String> {
        text(self.get().error_message)
    }

    pub(crate) fn sql_state(&self) -> Option<String> {
        text(self.get().error_sql_state)
    }

    /// The columns of the schema: their name and type.
    pub(crate) fn columns(&self) -> Vec<(String, String)> {
        let r = self.get();
        if r.schema.is_null() {
            return Vec::new();
        }
        let schema = unsafe { &*r.schema };
        (0..schema.size as usize)
            .map(|i| {
                let column = unsafe { &*schema.columns.add(i) };
                (text(column.name).unwrap(), text(column.kind).unwrap())
            })
            .collect()
    }

    /// The values of the rows, as Kotlin reads them (`None` for NULL).
    pub(crate) fn rows(&self) -> Vec<Vec<Option<String>>> {
        let r = self.get();
        (0..r.size as usize)
            .map(|i| {
                let row = unsafe { &*r.rows.add(i) };
                (0..row.size as usize)
                    .map(|j| text(unsafe { &*row.columns.add(j) }.value))
                    .collect()
            })
            .collect()
    }

    /// The single value of a one-row, one-column result.
    pub(crate) fn value(&self) -> Option<String> {
        let rows = self.rows();
        assert_eq!(rows.len(), 1, "rows: {rows:?}");
        assert_eq!(rows[0].len(), 1, "rows: {rows:?}");
        rows[0][0].clone()
    }
}

impl Drop for Res {
    fn drop(&mut self) {
        sqlx4k_postgres_free_result(self.0);
    }
}

/// The completion callback of an asynchronous export (`fun`): hands the result to the waiting [call].
pub(crate) type Fun = extern "C" fn(Sqlx4kPostgresPtr, *mut Sqlx4kPostgresResult);

extern "C" fn deliver(callback: Sqlx4kPostgresPtr, result: *mut Sqlx4kPostgresResult) {
    let sender = unsafe { Box::from_raw(callback.ptr as *mut mpsc::Sender<usize>) };
    let _ = sender.send(result as usize);
}

/// Runs an asynchronous export, given its callback and `fun`, and waits for the result it delivers.
pub(crate) fn call(f: impl FnOnce(*mut c_void, Fun)) -> Res {
    let (sender, receiver) = mpsc::channel::<usize>();
    f(Box::into_raw(Box::new(sender)) as *mut c_void, deliver);
    let result = receiver
        .recv_timeout(Duration::from_secs(60))
        .expect("the export delivered no result");
    Res(result as *mut Sqlx4kPostgresResult)
}

/// A pool of the test database, closed when dropped.
pub(crate) struct Pool {
    pub(crate) rt: *mut c_void,
}

impl Pool {
    pub(crate) fn open(max_connections: c_int) -> Pool {
        let url = c(&url());
        let result = Res(sqlx4k_postgresql_of(
            url.as_ptr(),
            c(USERNAME).as_ptr(),
            c(PASSWORD).as_ptr(),
            -1,
            max_connections,
            10_000,
            -1,
            -1,
        ))
        .ok();
        Pool {
            rt: result.get().rt,
        }
    }
}

impl Drop for Pool {
    fn drop(&mut self) {
        call(|cb, fun| sqlx4k_postgresql_close(self.rt, cb, fun));
    }
}

/// A parameter of the given kind, its value set by `f`.
pub(crate) fn param(kind: c_int, f: impl FnOnce(&mut Sqlx4kPostgresParam)) -> Sqlx4kPostgresParam {
    let mut p = Sqlx4kPostgresParam {
        kind,
        null_type: PARAM_NULL,
        i64_val: 0,
        f64_val: 0.0,
        text: null(),
        text_len: 0,
        blob: null(),
        blob_len: 0,
    };
    f(&mut p);
    p
}

/// A TEXT-shaped parameter (TEXT, DATE, TIME, TIMESTAMP, TIMESTAMPTZ, UUID), reading `s`, which must outlive it.
pub(crate) fn text_param(kind: c_int, s: &CString) -> Sqlx4kPostgresParam {
    param(kind, |p| {
        p.text = s.as_ptr();
        p.text_len = s.as_bytes().len() as c_int;
    })
}
