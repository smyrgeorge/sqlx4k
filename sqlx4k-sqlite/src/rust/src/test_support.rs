//! What the tests share: a database file of their own, the exports called as Kotlin calls them (waiting for the result
//! they deliver), and the results read back.

use std::ffi::{c_char, c_int, c_void, CStr, CString};
use std::path::PathBuf;
use std::ptr::null;
use std::sync::atomic::{AtomicUsize, Ordering};
use std::sync::mpsc;
use std::time::Duration;

use sqlx::sqlite::SqliteConnectOptions;
use sqlx::{Connection, SqliteConnection};

use crate::ffi::pool::{sqlx4k_sqlite_close, sqlx4k_sqlite_of};
use crate::params::Sqlx4kSqliteParam;
use crate::result::sqlx4k_sqlite_free_result;
use crate::types::{Sqlx4kSqlitePtr, Sqlx4kSqliteResult, OK};

/// Runs a future to completion on the tests' own runtime (not the driver's).
pub(crate) fn block_on<F: std::future::Future>(f: F) -> F::Output {
    tokio::runtime::Builder::new_current_thread().enable_all().build().unwrap().block_on(f)
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

/// A name no other test (or run) uses, for the files and tables a test creates.
pub(crate) fn unique(prefix: &str) -> String {
    static NEXT: AtomicUsize = AtomicUsize::new(0);
    let nanos = std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).unwrap().subsec_nanos();
    format!("{prefix}_{}_{}_{nanos}", std::process::id(), NEXT.fetch_add(1, Ordering::Relaxed))
}

/// A database file in the temporary directory, not created yet, removed (with its WAL files) when dropped.
pub(crate) struct TempDb {
    pub(crate) path: PathBuf,
}

impl TempDb {
    pub(crate) fn new() -> TempDb {
        TempDb { path: std::env::temp_dir().join(unique("sqlx4k_test") + ".db") }
    }

    pub(crate) fn url(&self) -> String {
        format!("sqlite://{}", self.path.display())
    }

    pub(crate) fn connect_options(&self) -> SqliteConnectOptions {
        SqliteConnectOptions::new().filename(&self.path).create_if_missing(true)
    }
}

impl Drop for TempDb {
    fn drop(&mut self) {
        for suffix in ["", "-wal", "-shm", "-journal"] {
            let mut path = self.path.clone().into_os_string();
            path.push(suffix);
            let _ = std::fs::remove_file(path);
        }
    }
}

/// A connection to a database of its own, in memory.
pub(crate) async fn memory() -> SqliteConnection {
    SqliteConnection::connect("sqlite::memory:").await.unwrap()
}

/// A result handed over by an export, freed when dropped (the handles it carries are not).
pub(crate) struct Res(pub(crate) *mut Sqlx4kSqliteResult);

impl Res {
    pub(crate) fn get(&self) -> &Sqlx4kSqliteResult {
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

    /// The values of the rows, as Kotlin reads them (by their length; `None` for NULL).
    pub(crate) fn rows(&self) -> Vec<Vec<Option<String>>> {
        let r = self.get();
        (0..r.size as usize)
            .map(|i| {
                let row = unsafe { &*r.rows.add(i) };
                (0..row.size as usize)
                    .map(|j| {
                        let column = unsafe { &*row.columns.add(j) };
                        (!column.value.is_null()).then(|| {
                            let bytes = unsafe { std::slice::from_raw_parts(column.value as *const u8, column.value_len as usize) };
                            String::from_utf8_lossy(bytes).into_owned()
                        })
                    })
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
        sqlx4k_sqlite_free_result(self.0);
    }
}

/// The completion callback of an asynchronous export (`fun`): hands the result to the waiting [call].
pub(crate) type Fun = extern "C" fn(Sqlx4kSqlitePtr, *mut Sqlx4kSqliteResult);

extern "C" fn deliver(callback: Sqlx4kSqlitePtr, result: *mut Sqlx4kSqliteResult) {
    let sender = unsafe { Box::from_raw(callback.ptr as *mut mpsc::Sender<usize>) };
    let _ = sender.send(result as usize);
}

/// Runs an asynchronous export, given its callback and `fun`, and waits for the result it delivers.
pub(crate) fn call(f: impl FnOnce(*mut c_void, Fun)) -> Res {
    let (sender, receiver) = mpsc::channel::<usize>();
    f(Box::into_raw(Box::new(sender)) as *mut c_void, deliver);
    let result = receiver.recv_timeout(Duration::from_secs(60)).expect("the export delivered no result");
    Res(result as *mut Sqlx4kSqliteResult)
}

/// Creates a pool of `url`, as Kotlin does (SQLite has no user or password).
pub(crate) fn open(url: &str, min_connections: c_int, max_connections: c_int) -> Res {
    let url = c(url);
    Res(sqlx4k_sqlite_of(url.as_ptr(), null(), null(), min_connections, max_connections, 10_000, -1, -1))
}

/// A pool of a database file of its own, closed (and the file removed) when dropped.
pub(crate) struct Pool {
    pub(crate) rt: *mut c_void,
    // Kept for its drop, which removes the file once the pool is closed.
    _db: TempDb,
}

impl Pool {
    pub(crate) fn open(max_connections: c_int) -> Pool {
        let db = TempDb::new();
        let rt = open(&db.url(), -1, max_connections).ok().get().rt;
        Pool { rt, _db: db }
    }
}

impl Drop for Pool {
    fn drop(&mut self) {
        call(|cb, fun| sqlx4k_sqlite_close(self.rt, cb, fun));
    }
}

/// A parameter of the given kind, its value set by `f`.
pub(crate) fn param(kind: c_int, f: impl FnOnce(&mut Sqlx4kSqliteParam)) -> Sqlx4kSqliteParam {
    let mut p = Sqlx4kSqliteParam {
        kind,
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

/// A TEXT parameter reading `s`, which must outlive it.
pub(crate) fn text_param(kind: c_int, s: &CString) -> Sqlx4kSqliteParam {
    param(kind, |p| {
        p.text = s.as_ptr();
        p.text_len = s.as_bytes().len() as c_int;
    })
}
