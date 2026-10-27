//! Opening the pool (its options from the URL, the SQLCipher key) and the queries run on it, on a
//! connection or in a transaction.

use sqlx::pool::PoolConnection;
use sqlx::sqlite::{SqliteConnectOptions, SqlitePool, SqlitePoolOptions};
use sqlx::{Acquire, AssertSqlSafe, ConnectOptions, Connection, Executor, Sqlite, Transaction};
use std::ffi::{c_int, c_void};
use std::time::Duration;
use tokio::runtime::Runtime;
use tokio::sync::{mpsc, oneshot};

use crate::core::errors::error_result_of;
use crate::core::params::{bind_params, OwnedParam};
use crate::core::result::result_of;
use crate::core::stream::{
    stream_result_of, stream_rows, stream_rows_of, Sqlx4kSqliteCipherStream,
    STREAM_CHANNEL_CAPACITY,
};
use crate::core::types::{Sqlx4kSqliteCipherPtr, Sqlx4kSqliteCipherResult};
use crate::core::upsert::{inserted_rowid, is_upsert, last_insert_rowid};
use crate::core::RUNTIME;

#[derive(Debug)]
pub struct Sqlx4kSqliteCipher {
    pub(crate) pool: SqlitePool,
}

impl Sqlx4kSqliteCipher {
    pub async fn query(&self, sql: String) -> *mut Sqlx4kSqliteCipherResult {
        let (result, before) = if is_upsert(&sql) {
            match self.pool.acquire().await {
                Ok(mut cn) => {
                    let before = last_insert_rowid(&mut cn).await;
                    (cn.execute(AssertSqlSafe(sql)).await, before)
                }
                Err(err) => (Err(err), None),
            }
        } else {
            (self.pool.execute(AssertSqlSafe(sql)).await, None)
        };
        let result = match result {
            Ok(res) => Sqlx4kSqliteCipherResult {
                rows_affected: res.rows_affected(),
                last_insert_id: inserted_rowid(&res, before),
                ..Default::default()
            },
            Err(err) => error_result_of(err),
        };
        result.leak()
    }

    pub async fn fetch_all(&self, sql: String) -> *mut Sqlx4kSqliteCipherResult {
        let result = self.pool.fetch_all(AssertSqlSafe(sql)).await;
        result_of(result).leak()
    }

    pub async fn cn_acquire(&self) -> *mut Sqlx4kSqliteCipherResult {
        let cn = self.pool.acquire().await;
        let cn: PoolConnection<Sqlite> = match cn {
            Ok(cn) => cn,
            Err(err) => return error_result_of(err).leak(),
        };

        let cn = Box::new(cn);
        let cn = Box::leak(cn);
        let result = Sqlx4kSqliteCipherResult {
            cn: cn as *mut _ as *mut c_void,
            ..Default::default()
        };
        result.leak()
    }

    pub async fn cn_release(&self, cn: Sqlx4kSqliteCipherPtr) -> *mut Sqlx4kSqliteCipherResult {
        if cn.ptr.is_null() {
            return Sqlx4kSqliteCipherResult::default().leak();
        }

        let cn_ptr = cn.ptr as *mut PoolConnection<Sqlite>;
        unsafe {
            // Recreate the Box and drop it, returning connection to pool
            let _boxed: Box<PoolConnection<Sqlite>> = Box::from_raw(cn_ptr);
        }

        Sqlx4kSqliteCipherResult::default().leak()
    }

    pub async fn cn_query(
        &self,
        cn: Sqlx4kSqliteCipherPtr,
        sql: String,
    ) -> *mut Sqlx4kSqliteCipherResult {
        let cn = unsafe { &mut *(cn.ptr as *mut PoolConnection<Sqlite>) };
        let before = if is_upsert(&sql) { last_insert_rowid(&mut **cn).await } else { None };
        let result = cn.execute(AssertSqlSafe(sql)).await;
        let result = match result {
            Ok(res) => Sqlx4kSqliteCipherResult {
                rows_affected: res.rows_affected(),
                last_insert_id: inserted_rowid(&res, before),
                ..Default::default()
            },
            Err(err) => error_result_of(err),
        };
        result.leak()
    }

    pub async fn cn_fetch_all(
        &self,
        cn: Sqlx4kSqliteCipherPtr,
        sql: String,
    ) -> *mut Sqlx4kSqliteCipherResult {
        let cn = unsafe { &mut *(cn.ptr as *mut PoolConnection<Sqlite>) };
        let result = cn.fetch_all(AssertSqlSafe(sql)).await;
        result_of(result).leak()
    }

    pub async fn cn_tx_begin(&self, cn: Sqlx4kSqliteCipherPtr) -> *mut Sqlx4kSqliteCipherResult {
        let cn = unsafe { &mut *(cn.ptr as *mut PoolConnection<Sqlite>) };
        let tx = cn.begin().await;
        let tx = match tx {
            Ok(tx) => tx,
            Err(err) => {
                return error_result_of(err).leak();
            }
        };

        let tx = Box::new(tx);
        let tx = Box::leak(tx);

        let result = Sqlx4kSqliteCipherResult {
            tx: tx as *mut _ as *mut c_void,
            ..Default::default()
        };
        result.leak()
    }

    pub async fn tx_begin(&self) -> *mut Sqlx4kSqliteCipherResult {
        let tx = self.pool.begin().await;
        let tx = match tx {
            Ok(tx) => tx,
            Err(err) => {
                return error_result_of(err).leak();
            }
        };

        let tx = Box::new(tx);
        let tx = Box::leak(tx);
        let result = Sqlx4kSqliteCipherResult {
            tx: tx as *mut _ as *mut c_void,
            ..Default::default()
        };
        result.leak()
    }

    pub async fn tx_commit(&self, tx: Sqlx4kSqliteCipherPtr) -> *mut Sqlx4kSqliteCipherResult {
        let tx = unsafe { *Box::from_raw(tx.ptr as *mut Transaction<'_, Sqlite>) };
        let result = match tx.commit().await {
            Ok(_) => Sqlx4kSqliteCipherResult::default(),
            Err(err) => error_result_of(err),
        };
        result.leak()
    }

    pub async fn tx_rollback(&self, tx: Sqlx4kSqliteCipherPtr) -> *mut Sqlx4kSqliteCipherResult {
        let tx = unsafe { *Box::from_raw(tx.ptr as *mut Transaction<'_, Sqlite>) };
        let result = match tx.rollback().await {
            Ok(_) => Sqlx4kSqliteCipherResult::default(),
            Err(err) => error_result_of(err),
        };
        result.leak()
    }

    pub async fn tx_query(
        &self,
        tx: Sqlx4kSqliteCipherPtr,
        sql: String,
    ) -> *mut Sqlx4kSqliteCipherResult {
        let mut tx = unsafe { *Box::from_raw(tx.ptr as *mut Transaction<'_, Sqlite>) };
        let before = if is_upsert(&sql) { last_insert_rowid(&mut *tx).await } else { None };
        let result = tx.execute(AssertSqlSafe(sql)).await;
        let tx = Box::new(tx);
        let tx = Box::into_raw(tx);
        let result = match result {
            Ok(res) => Sqlx4kSqliteCipherResult {
                rows_affected: res.rows_affected(),
                last_insert_id: inserted_rowid(&res, before),
                ..Default::default()
            },
            Err(err) => error_result_of(err),
        };
        let result = Sqlx4kSqliteCipherResult {
            tx: tx as *mut c_void,
            ..result
        };
        result.leak()
    }

    pub async fn tx_fetch_all(
        &self,
        tx: Sqlx4kSqliteCipherPtr,
        sql: String,
    ) -> *mut Sqlx4kSqliteCipherResult {
        let mut tx = unsafe { *Box::from_raw(tx.ptr as *mut Transaction<'_, Sqlite>) };
        let result = tx.fetch_all(AssertSqlSafe(sql)).await;
        let tx = Box::new(tx);
        let tx = Box::into_raw(tx);
        let result = result_of(result);
        let result = Sqlx4kSqliteCipherResult {
            tx: tx as *mut c_void,
            ..result
        };
        result.leak()
    }

    pub async fn stream_open(
        &self,
        sql: String,
        params: Vec<OwnedParam>,
        fetch_size: usize,
    ) -> *mut Sqlx4kSqliteCipherResult {
        let mut cn: PoolConnection<Sqlite> = match self.pool.acquire().await {
            Ok(cn) => cn,
            Err(err) => return error_result_of(err).leak(),
        };
        let (tx, rx) = mpsc::channel(STREAM_CHANNEL_CAPACITY);
        let (cancel, cancel_rx) = oneshot::channel();
        let task = RUNTIME.get().unwrap().spawn(async move {
            // The connection goes back to the pool when the task ends, consumed or cancelled alike
            // (unlike the network drivers there is nothing to drain: dropping the stream resets the
            // statement; and closing it would lose an in-memory database).
            let rows = stream_rows_of(&mut cn, sql, params);
            stream_rows(rows, fetch_size, tx, cancel_rx).await;
        });
        stream_result_of(Sqlx4kSqliteCipherStream {
            rx: tokio::sync::Mutex::new(rx),
            cancel,
            task,
        })
    }

    pub async fn cn_stream_open(
        &self,
        cn: Sqlx4kSqliteCipherPtr,
        sql: String,
        params: Vec<OwnedParam>,
        fetch_size: usize,
    ) -> *mut Sqlx4kSqliteCipherResult {
        let (tx, rx) = mpsc::channel(STREAM_CHANNEL_CAPACITY);
        let (cancel, cancel_rx) = oneshot::channel();
        let task = RUNTIME.get().unwrap().spawn(async move {
            // The connection stays with Kotlin, which serializes its use and closes this stream
            // before any other statement.
            let cn = unsafe { &mut *(cn.raw() as *mut PoolConnection<Sqlite>) };
            let rows = stream_rows_of(&mut **cn, sql, params);
            stream_rows(rows, fetch_size, tx, cancel_rx).await;
        });
        stream_result_of(Sqlx4kSqliteCipherStream {
            rx: tokio::sync::Mutex::new(rx),
            cancel,
            task,
        })
    }

    pub async fn tx_stream_open(
        &self,
        tx: Sqlx4kSqliteCipherPtr,
        sql: String,
        params: Vec<OwnedParam>,
        fetch_size: usize,
    ) -> *mut Sqlx4kSqliteCipherResult {
        let (chunks, rx) = mpsc::channel(STREAM_CHANNEL_CAPACITY);
        let (cancel, cancel_rx) = oneshot::channel();
        let task = RUNTIME.get().unwrap().spawn(async move {
            // Borrowed in place (not re-boxed like `tx_fetch_all`), so the Kotlin-held pointer stays valid.
            let tx = unsafe { &mut *(tx.raw() as *mut Transaction<'static, Sqlite>) };
            let rows = stream_rows_of(&mut **tx, sql, params);
            stream_rows(rows, fetch_size, chunks, cancel_rx).await;
        });
        stream_result_of(Sqlx4kSqliteCipherStream {
            rx: tokio::sync::Mutex::new(rx),
            cancel,
            task,
        })
    }

    pub async fn stream_next(
        &self,
        stream: Sqlx4kSqliteCipherPtr,
    ) -> *mut Sqlx4kSqliteCipherResult {
        let stream = unsafe { &*(stream.ptr as *const Sqlx4kSqliteCipherStream) };
        let mut rx = stream.rx.lock().await;
        match rx.recv().await {
            Some(chunk) => result_of(chunk).leak(),
            // End of the stream: an empty result (size 0, no schema).
            None => Sqlx4kSqliteCipherResult::default().leak(),
        }
    }

    pub async fn stream_close(
        &self,
        stream: Sqlx4kSqliteCipherPtr,
    ) -> *mut Sqlx4kSqliteCipherResult {
        let stream = unsafe { Box::from_raw(stream.ptr as *mut Sqlx4kSqliteCipherStream) };
        let Sqlx4kSqliteCipherStream { rx, cancel, task } = *stream;
        // Stop the producer (dropping the sender fires its cancel branch, dropping the receiver fails
        // its next send) and wait for it: only then is the connection it borrowed free again.
        drop(cancel);
        drop(rx);
        let _ = task.await;
        Sqlx4kSqliteCipherResult::default().leak()
    }

    pub async fn close(&self) -> *mut Sqlx4kSqliteCipherResult {
        close_pool(&self.pool).await;
        Sqlx4kSqliteCipherResult::default().leak()
    }

    pub async fn query_with_params(
        &self,
        sql: String,
        params: Vec<OwnedParam>,
    ) -> *mut Sqlx4kSqliteCipherResult {
        let upsert = is_upsert(&sql);
        let q = bind_params(sqlx::query::<Sqlite>(AssertSqlSafe(sql)), params);
        let (result, before) = if upsert {
            match self.pool.acquire().await {
                Ok(mut cn) => {
                    let before = last_insert_rowid(&mut cn).await;
                    (q.execute(&mut *cn).await, before)
                }
                Err(err) => (Err(err), None),
            }
        } else {
            (q.execute(&self.pool).await, None)
        };
        let result = match result {
            Ok(res) => Sqlx4kSqliteCipherResult {
                rows_affected: res.rows_affected(),
                last_insert_id: inserted_rowid(&res, before),
                ..Default::default()
            },
            Err(err) => error_result_of(err),
        };
        result.leak()
    }

    pub async fn fetch_all_with_params(
        &self,
        sql: String,
        params: Vec<OwnedParam>,
    ) -> *mut Sqlx4kSqliteCipherResult {
        let q = bind_params(sqlx::query::<Sqlite>(AssertSqlSafe(sql)), params);
        let result = q.fetch_all(&self.pool).await;
        result_of(result).leak()
    }

    pub async fn cn_query_with_params(
        &self,
        cn: Sqlx4kSqliteCipherPtr,
        sql: String,
        params: Vec<OwnedParam>,
    ) -> *mut Sqlx4kSqliteCipherResult {
        let cn = unsafe { &mut *(cn.ptr as *mut PoolConnection<Sqlite>) };
        let upsert = is_upsert(&sql);
        let q = bind_params(sqlx::query::<Sqlite>(AssertSqlSafe(sql)), params);
        let before = if upsert { last_insert_rowid(&mut **cn).await } else { None };
        let result = q.execute(&mut **cn).await;
        let result = match result {
            Ok(res) => Sqlx4kSqliteCipherResult {
                rows_affected: res.rows_affected(),
                last_insert_id: inserted_rowid(&res, before),
                ..Default::default()
            },
            Err(err) => error_result_of(err),
        };
        result.leak()
    }

    pub async fn cn_fetch_all_with_params(
        &self,
        cn: Sqlx4kSqliteCipherPtr,
        sql: String,
        params: Vec<OwnedParam>,
    ) -> *mut Sqlx4kSqliteCipherResult {
        let cn = unsafe { &mut *(cn.ptr as *mut PoolConnection<Sqlite>) };
        let q = bind_params(sqlx::query::<Sqlite>(AssertSqlSafe(sql)), params);
        let result = q.fetch_all(&mut **cn).await;
        result_of(result).leak()
    }

    pub async fn tx_query_with_params(
        &self,
        tx: Sqlx4kSqliteCipherPtr,
        sql: String,
        params: Vec<OwnedParam>,
    ) -> *mut Sqlx4kSqliteCipherResult {
        let mut tx = unsafe { *Box::from_raw(tx.ptr as *mut Transaction<'_, Sqlite>) };
        let upsert = is_upsert(&sql);
        let q = bind_params(sqlx::query::<Sqlite>(AssertSqlSafe(sql)), params);
        let before = if upsert { last_insert_rowid(&mut *tx).await } else { None };
        let result = q.execute(&mut *tx).await;
        let tx = Box::new(tx);
        let tx = Box::into_raw(tx);
        let result = match result {
            Ok(res) => Sqlx4kSqliteCipherResult {
                rows_affected: res.rows_affected(),
                last_insert_id: inserted_rowid(&res, before),
                ..Default::default()
            },
            Err(err) => error_result_of(err),
        };
        let result = Sqlx4kSqliteCipherResult {
            tx: tx as *mut c_void,
            ..result
        };
        result.leak()
    }

    pub async fn tx_fetch_all_with_params(
        &self,
        tx: Sqlx4kSqliteCipherPtr,
        sql: String,
        params: Vec<OwnedParam>,
    ) -> *mut Sqlx4kSqliteCipherResult {
        let mut tx = unsafe { *Box::from_raw(tx.ptr as *mut Transaction<'_, Sqlite>) };
        let q = bind_params(sqlx::query::<Sqlite>(AssertSqlSafe(sql)), params);
        let result = q.fetch_all(&mut *tx).await;
        let tx = Box::new(tx);
        let tx = Box::into_raw(tx);
        let result = result_of(result);
        let result = Sqlx4kSqliteCipherResult {
            tx: tx as *mut c_void,
            ..result
        };
        result.leak()
    }
}

/// How long closing a pool waits for its checked-out connections to be given back (see [close_pool]).
pub(crate) const CLOSE_GRACE: Duration = Duration::from_secs(1);

/// Closes the pool without waiting for good for the connections still checked out. `Pool::close()` waits until every
/// checked-out connection is given back (and only then closes the idle ones): one the caller still holds (or abandoned)
/// would hang it for good, and the caller cannot cancel it. The idle connections are closed here instead, and the
/// pool is marked closed; the checked-out ones are closed as they are given back, as the JVM drivers do.
///
/// The idle connections are taken out before the pool is marked closed (a closed pool gives none), but only detached
/// and closed after: a connection detached from an open pool that keeps `min_connections` makes it open a replacement.
///
/// The close is then given [CLOSE_GRACE] to finish: sqlx gives a released connection back in a task of its own, so
/// the connection of a query (or of a connection) released just before is usually still on its way back, and would
/// otherwise stay open after this returns (with its database file, on SQLite). A connection the caller still holds is
/// closed once given back.
pub(crate) async fn close_pool<DB: sqlx::Database>(pool: &sqlx::Pool<DB>) {
    let idle: Vec<_> = std::iter::from_fn(|| pool.try_acquire()).collect();
    // Marks the pool closed when called, before it is awaited: no connection is acquired or opened after this.
    let closing = pool.close();
    for cn in idle {
        let _ = sqlx::Connection::close(cn.detach()).await;
    }
    if tokio::time::timeout(CLOSE_GRACE, closing).await.is_err() {
        let closing = pool.clone();
        tokio::spawn(async move { closing.close().await });
    }
}

/// The `mode` query parameter of an sqlx-style SQLite URL (`sqlite:///path.db?mode=ro`), if any.
/// The last occurrence wins, as in sqlx's own parser.
pub(crate) fn sqlite_url_mode(url: &str) -> Option<&str> {
    let query = url.splitn(2, '?').nth(1)?;
    query
        .split('&')
        .filter_map(|kv| {
            let mut it = kv.splitn(2, '=');
            match (it.next(), it.next()) {
                (Some("mode"), Some(v)) => Some(v),
                _ => None,
            }
        })
        .last()
}

/// Opens (creating if necessary) an encrypted SQLite database and returns a result whose
/// `rt` field holds the leaked [`Sqlx4kSqliteCipher`] handle, or an error result.
///
/// `password`, when non-empty, is applied as `PRAGMA key` before any other statement, which
/// is how SQLCipher derives the encryption key. `create_if_missing` (for the default mode and
/// `mode=rwc`) ensures a freshly created database file is created *encrypted* (using the key),
/// avoiding the trap of creating an unencrypted file first and then failing to open it with a key.
pub fn open(
    url: &str,
    password: &str,
    min_connections: c_int,
    max_connections: c_int,
    acquire_timeout_milis: c_int,
    idle_timeout_milis: c_int,
    max_lifetime_milis: c_int,
) -> *mut Sqlx4kSqliteCipherResult {
    // SQLite would read the `PRAGMA key` statement up to the NUL, and report an error quoting the passphrase.
    if password.contains('\0') {
        return error_result_of(sqlx::Error::Configuration(
            "The SQLCipher passphrase must not contain a NUL character (U+0000).".into(),
        ))
        .leak();
    }
    let mut options: SqliteConnectOptions = match url.parse() {
        Ok(o) => o,
        Err(err) => return error_result_of(err).leak(),
    };
    // `mode` decides whether a missing file may be created and whether the connection is writable, as on the plain
    // SQLite driver (and as SQLite defines `ro` / `rw` / `rwc`): sqlx parses `mode=rw` as a no-op, so creating the
    // file unconditionally turned a mistyped path into a new, empty database.
    let mode = sqlite_url_mode(url);
    let read_only = mode == Some("ro");
    options = options.create_if_missing(matches!(mode, None | Some("rwc")));
    if !password.is_empty() {
        // SQLCipher derives the key from `PRAGMA key = '<passphrase>'`. sqlx inserts the pragma
        // value verbatim, so we must quote it as a SQL string literal ourselves (escaping any
        // embedded single quotes by doubling them); otherwise a passphrase containing characters
        // like '-' produces a SQL syntax error.
        let quoted = format!("'{}'", password.replace('\'', "''"));
        options = options.pragma("key", quoted);
    }

    // Create the tokio runtime, once: get_or_init also when two pools are created concurrently (checking and then
    // setting raced: a losing set().unwrap() panicked, and the process aborted).
    let runtime = RUNTIME.get_or_init(|| Runtime::new().expect("failed to create the tokio runtime"));

    // Create the db pool options.
    //
    // WAL must be enabled *after* `PRAGMA key`: sqlx applies its built-in `journal_mode` before our
    // custom `key` pragma, so on an encrypted database that switch runs on the still-encrypted file
    // and silently leaves the connection in rollback-journal ("delete") mode. `after_connect` runs
    // once the connection is fully established (key applied), so the WAL switch takes effect. This
    // is a no-op for in-memory databases (they ignore WAL and report "memory"). It is skipped for `mode=ro`: changing
    // the journal mode writes to the database header, which a read-only connection cannot do (it would fail with
    // "attempt to write a readonly database").
    let pool = SqlitePoolOptions::new()
        .max_connections(max_connections as u32)
        .after_connect(move |conn, _meta| {
            Box::pin(async move {
                if !read_only {
                    sqlx::query("PRAGMA journal_mode = WAL;")
                        .execute(&mut *conn)
                        .await?;
                }
                Ok(())
            })
        });

    let pool = if min_connections > 0 {
        pool.min_connections(min_connections as u32)
    } else {
        pool
    };

    let pool = if acquire_timeout_milis > 0 {
        pool.acquire_timeout(Duration::from_millis(acquire_timeout_milis as u64))
    } else {
        pool
    };

    let pool = with_pool_timeouts(pool, idle_timeout_milis, max_lifetime_milis);

    if let Err(err) = runtime.block_on(check_key(&options)) {
        return error_result_of(err).leak();
    }

    let pool = pool.connect_with(options);

    // Create the pool here.
    let pool = runtime.block_on(pool);
    let pool: SqlitePool = match pool {
        Ok(pool) => pool,
        Err(err) => return error_result_of(err).leak(),
    };
    let sqlx4k = Sqlx4kSqliteCipher { pool };
    let sqlx4k = Box::new(sqlx4k);
    let sqlx4k = Box::leak(sqlx4k);

    Sqlx4kSqliteCipherResult {
        rt: sqlx4k as *mut _ as *mut c_void,
        ..Default::default()
    }
    .leak()
}

/// Reads the database on a connection of its own, so that a wrong key (or none, or a key for a database not encrypted,
/// or a file that is no database) fails the open at once with SQLITE_NOTADB. The pool would find it only in
/// `after_connect`, its first read: sqlx closes a connection whose `after_connect` fails and opens another, until the
/// acquire timeout, so the open waited for it (30 seconds by default) and failed with PoolTimedOut.
async fn check_key(options: &SqliteConnectOptions) -> Result<(), sqlx::Error> {
    let mut cn = options.connect().await?;
    let read = sqlx::query("select count(*) from sqlite_master").execute(&mut cn).await;
    let _ = cn.close().await;
    read.map(|_| ())
}

pub fn pool_size(rt: *mut c_void) -> c_int {
    let sqlx4k = unsafe { &*(rt as *mut Sqlx4kSqliteCipher) };
    sqlx4k.pool.size() as c_int
}

pub fn pool_idle_size(rt: *mut c_void) -> c_int {
    let sqlx4k = unsafe { &*(rt as *mut Sqlx4kSqliteCipher) };
    sqlx4k.pool.num_idle() as c_int
}

/// Applies the idle timeout and max lifetime. A value of 0 or less (Kotlin passes -1 for "none") disables the
/// setting, as on the JVM. Keeping sqlx's defaults (10 minutes idle, 30 minutes lifetime) closed the connections of an
/// in-memory database, which only lives as long as one of them is open, and so silently wiped it.
pub(crate) fn with_pool_timeouts(
    pool: SqlitePoolOptions,
    idle_timeout_milis: c_int,
    max_lifetime_milis: c_int,
) -> SqlitePoolOptions {
    let millis = |m: c_int| (m > 0).then(|| Duration::from_millis(m as u64));
    pool.idle_timeout(millis(idle_timeout_milis)).max_lifetime(millis(max_lifetime_milis))
}

#[cfg(test)]
mod pool_options_tests {
    use super::*;

    #[test]
    fn a_missing_timeout_disables_the_setting() {
        let pool = with_pool_timeouts(SqlitePoolOptions::new(), -1, -1);
        assert_eq!(pool.get_idle_timeout(), None);
        assert_eq!(pool.get_max_lifetime(), None);
    }

    #[test]
    fn a_timeout_is_applied() {
        let pool = with_pool_timeouts(SqlitePoolOptions::new(), 1_000, 2_000);
        assert_eq!(pool.get_idle_timeout(), Some(Duration::from_secs(1)));
        assert_eq!(pool.get_max_lifetime(), Some(Duration::from_secs(2)));
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::core::params::{read_params, PARAM_TEXT};
    use crate::core::types::{ERROR_DATABASE, ERROR_POOL, ERROR_POOL_CLOSED};
    use crate::test_support::{block_on, c, text_param, Pool, Res, TempDb, KEY};
    use std::time::Instant;

    /// Runs `f` on the driver's runtime, as the exports do.
    fn on_runtime<F: std::future::Future>(f: F) -> F::Output {
        RUNTIME.get().unwrap().block_on(f)
    }

    fn client(pool: &Pool) -> &'static Sqlx4kSqliteCipher {
        unsafe { &*(pool.rt as *const Sqlx4kSqliteCipher) }
    }

    fn ptr(p: *mut c_void) -> Sqlx4kSqliteCipherPtr {
        Sqlx4kSqliteCipherPtr { ptr: p }
    }

    fn count(db: &Sqlx4kSqliteCipher) -> String {
        Res(on_runtime(db.fetch_all("select count(*) from t".to_string()))).ok().value().unwrap()
    }

    /// Waits (a while) until the pool has `n` idle connections.
    fn wait_idle(db: &Sqlx4kSqliteCipher, n: usize) {
        for _ in 0..100 {
            if db.pool.num_idle() == n {
                break;
            }
            std::thread::sleep(Duration::from_millis(10));
        }
        assert_eq!(db.pool.num_idle(), n);
    }

    #[test]
    fn the_mode_of_a_url_is_its_last_mode_parameter() {
        assert_eq!(sqlite_url_mode("sqlite://a.db"), None);
        assert_eq!(sqlite_url_mode("sqlite://a.db?cache=shared"), None);
        assert_eq!(sqlite_url_mode("sqlite://a.db?mode=ro"), Some("ro"));
        assert_eq!(sqlite_url_mode("sqlite://a.db?cache=shared&mode=rw"), Some("rw"));
        assert_eq!(sqlite_url_mode("sqlite://a.db?mode=ro&mode=rwc"), Some("rwc"));
        assert_eq!(sqlite_url_mode("sqlite://a.db?mode"), None);
        assert_eq!(sqlite_url_mode("sqlite::memory:?mode=memory"), Some("memory"));
    }

    #[test]
    fn closing_a_pool_closes_its_idle_connections_at_once() {
        let db = TempDb::new();
        block_on(async {
            let pool = SqlitePoolOptions::new().min_connections(2).max_connections(2).connect_with(db.connect_options()).await.unwrap();
            while pool.num_idle() < 2 {
                tokio::time::sleep(Duration::from_millis(10)).await;
            }
            let started = Instant::now();
            close_pool(&pool).await;
            assert!(pool.is_closed());
            assert_eq!(pool.size(), 0);
            assert!(started.elapsed() < CLOSE_GRACE);
        });
    }

    #[test]
    fn closing_a_pool_does_not_wait_for_a_connection_held() {
        let db = TempDb::new();
        block_on(async {
            let pool = SqlitePoolOptions::new().max_connections(1).connect_with(db.connect_options()).await.unwrap();
            let held = pool.acquire().await.unwrap();
            let started = Instant::now();
            close_pool(&pool).await;
            assert!(started.elapsed() < CLOSE_GRACE * 2);
            assert!(pool.is_closed());
            drop(held);
            for _ in 0..100 {
                if pool.size() == 0 {
                    break;
                }
                tokio::time::sleep(Duration::from_millis(10)).await;
            }
            assert_eq!(pool.size(), 0);
        });
    }

    #[test]
    fn queries_run_on_the_pool() {
        let pool = Pool::open(2);
        let db = client(&pool);
        Res(on_runtime(db.query("create table t(id integer primary key, k text unique, v text)".to_string()))).ok();
        let r = Res(on_runtime(db.query("insert into t(k, v) values ('a', 'a'), ('b', 'b')".to_string()))).ok();
        assert_eq!((r.get().rows_affected, r.get().last_insert_id), (2, 2));
        let v = c("c");
        let params = read_params([text_param(PARAM_TEXT, &v)].as_ptr(), 1).unwrap();
        let r = Res(on_runtime(db.query_with_params("update t set v = ? where id = 1".to_string(), params))).ok();
        assert_eq!(r.get().rows_affected, 1);
        let r = Res(on_runtime(db.fetch_all("select v from t order by id".to_string()))).ok();
        assert_eq!(r.rows(), [[Some("c".into())], [Some("b".into())]]);
        let params = read_params([text_param(PARAM_TEXT, &v)].as_ptr(), 1).unwrap();
        let r = Res(on_runtime(db.fetch_all_with_params("select id from t where v = ?".to_string(), params))).ok();
        assert_eq!(r.rows(), [[Some("1".into())]]);
    }

    #[test]
    fn an_upsert_reports_the_rowid_it_inserted_only() {
        let pool = Pool::open(1);
        let db = client(&pool);
        Res(on_runtime(db.query("create table t(id integer primary key, k text unique, v int)".to_string()))).ok();
        let upsert = |k: &str| format!("insert into t(k, v) values ('{k}', 1) on conflict(k) do update set v = v + 1");
        let r = Res(on_runtime(db.query(upsert("a")))).ok();
        assert_eq!((r.get().rows_affected, r.get().last_insert_id), (1, 1));
        let r = Res(on_runtime(db.query(upsert("a")))).ok();
        assert_eq!((r.get().rows_affected, r.get().last_insert_id), (1, 0));
        let r = Res(on_runtime(db.query_with_params(upsert("b"), vec![]))).ok();
        assert_eq!(r.get().last_insert_id, 2);
        let r = Res(on_runtime(db.query_with_params(upsert("b"), vec![]))).ok();
        assert_eq!(r.get().last_insert_id, 0);
    }

    #[test]
    fn a_transaction_is_committed_or_rolled_back() {
        let pool = Pool::open(2);
        let db = client(&pool);
        Res(on_runtime(db.query("create table t(id int)".to_string()))).ok();
        for commit in [true, false] {
            let tx = Res(on_runtime(db.tx_begin())).ok().get().tx;
            // The transaction moves into each call and comes back in its result.
            let tx = Res(on_runtime(db.tx_query(ptr(tx), "insert into t values (1)".to_string()))).ok().get().tx;
            let r = Res(on_runtime(db.tx_fetch_all(ptr(tx), "select count(*) from t".to_string()))).ok();
            let tx = r.get().tx;
            assert_eq!(r.value().as_deref(), Some(if commit { "1" } else { "2" }));
            if commit {
                Res(on_runtime(db.tx_commit(ptr(tx)))).ok();
            } else {
                Res(on_runtime(db.tx_rollback(ptr(tx)))).ok();
            }
        }
        assert_eq!(count(db), "1");
    }

    #[test]
    fn a_transaction_begun_in_another_one_on_a_connection_is_a_savepoint() {
        let pool = Pool::open(2);
        let db = client(&pool);
        Res(on_runtime(db.query("create table t(id int)".to_string()))).ok();
        let cn = Res(on_runtime(db.cn_acquire())).ok().get().cn;
        let outer = Res(on_runtime(db.cn_tx_begin(ptr(cn)))).ok().get().tx;
        let outer = Res(on_runtime(db.tx_query(ptr(outer), "insert into t values (1)".to_string()))).ok().get().tx;
        let inner = Res(on_runtime(db.cn_tx_begin(ptr(cn)))).ok().get().tx;
        let inner = Res(on_runtime(db.tx_query(ptr(inner), "insert into t values (2)".to_string()))).ok().get().tx;
        Res(on_runtime(db.tx_rollback(ptr(inner)))).ok();
        Res(on_runtime(db.tx_commit(ptr(outer)))).ok();
        Res(on_runtime(db.cn_release(ptr(cn)))).ok();
        assert_eq!(count(db), "1");
    }

    #[test]
    fn releasing_no_connection_does_nothing() {
        let pool = Pool::open(1);
        Res(on_runtime(client(&pool).cn_release(ptr(std::ptr::null_mut())))).ok();
    }

    #[test]
    fn a_pool_stream_gives_its_connection_back_consumed_or_not() {
        let pool = Pool::open(1);
        let db = client(&pool);
        let sql = "with recursive s(n) as (select 1 union all select n + 1 from s where n < 10000) select n from s";
        let stream = Res(on_runtime(db.stream_open(sql.to_string(), vec![], 10))).ok().get().stream;
        assert_eq!(Res(on_runtime(db.stream_next(ptr(stream)))).ok().rows().len(), 10);
        Res(on_runtime(db.stream_close(ptr(stream)))).ok();
        // Kept open (unlike the network drivers'): there is nothing to drain, and an in-memory database would be lost.
        wait_idle(db, 1);
        assert_eq!(db.pool.size(), 1);
        let stream = Res(on_runtime(db.stream_open("select 1".to_string(), vec![], 10))).ok().get().stream;
        assert_eq!(Res(on_runtime(db.stream_next(ptr(stream)))).ok().rows().len(), 1);
        assert_eq!(Res(on_runtime(db.stream_next(ptr(stream)))).ok().get().size, 0);
        Res(on_runtime(db.stream_close(ptr(stream)))).ok();
        wait_idle(db, 1);
    }

    /// The first 16 bytes of a database file: SQLite's magic string, unless it is encrypted.
    fn header(db: &TempDb) -> Vec<u8> {
        std::fs::read(&db.path).unwrap()[..16].to_vec()
    }

    const PLAIN_HEADER: &[u8] = b"SQLite format 3\0";

    /// Opens the database file of `db` with `key`, runs `sql` on it and closes it: the result of `sql`, or of the
    /// open when it failed.
    fn with_key(db: &TempDb, key: &str, sql: &str) -> Res {
        let opened = Res(open(&db.url(), key, -1, 1, 10_000, -1, -1));
        if !opened.is_ok() {
            return opened;
        }
        let pool = unsafe { &*(opened.get().rt as *const Sqlx4kSqliteCipher) };
        let r = Res(on_runtime(pool.fetch_all(sql.to_string())));
        Res(on_runtime(pool.close())).ok();
        r
    }

    #[test]
    fn a_database_is_encrypted_with_its_key() {
        let db = TempDb::new();
        with_key(&db, KEY, "create table t(v text); insert into t values ('secret')").ok();
        let file = std::fs::read(&db.path).unwrap();
        assert_ne!(&file[..16], PLAIN_HEADER);
        assert!(!file.windows(6).any(|w| w == b"secret"));
        assert_eq!(with_key(&db, KEY, "select v from t").ok().value().as_deref(), Some("secret"));
        // A wrong key, or none, fails the open at once (not after the acquire timeout) with SQLITE_NOTADB.
        for key in ["wrong", ""] {
            let started = Instant::now();
            let r = with_key(&db, key, "select v from t");
            assert_eq!((r.get().error, r.get().error_native_code), (ERROR_DATABASE, 26), "{key:?}: {:?}", r.error_message());
            assert!(r.get().rt.is_null());
            assert!(started.elapsed() < Duration::from_secs(5));
        }
    }

    #[test]
    fn an_empty_key_opens_an_unencrypted_database() {
        let db = TempDb::new();
        with_key(&db, "", "create table t(v text)").ok();
        assert_eq!(header(&db), PLAIN_HEADER);
        // An unencrypted database cannot be opened with a key.
        assert_eq!(with_key(&db, KEY, "select * from t").get().error_native_code, 26);
    }

    #[test]
    fn a_file_that_is_no_database_fails_the_open() {
        let db = TempDb::new();
        std::fs::write(&db.path, vec![b'x'; 4096]).unwrap();
        for key in [KEY, ""] {
            assert_eq!(with_key(&db, key, "select 1").get().error_native_code, 26, "{key:?}");
        }
    }

    #[test]
    fn a_read_only_database_is_checked_too() {
        let db = TempDb::new();
        with_key(&db, KEY, "create table t(v text)").ok();
        let url = format!("{}?mode=ro", db.url());
        let r = Res(open(&url, "wrong", -1, 1, 10_000, -1, -1));
        assert_eq!(r.get().error_native_code, 26);
        let r = Res(open(&url, KEY, -1, 1, 10_000, -1, -1)).ok();
        let pool = unsafe { &*(r.get().rt as *const Sqlx4kSqliteCipher) };
        Res(on_runtime(pool.close())).ok();
    }

    #[test]
    fn a_key_with_a_nul_is_refused() {
        let db = TempDb::new();
        let r = Res(open(&db.url(), "a\0b", -1, 1, 10_000, -1, -1));
        assert_eq!(r.get().error, ERROR_POOL);
        assert!(r.error_message().unwrap().contains("NUL"), "{:?}", r.error_message());
        // Refused before the file is created; and the key is not quoted back.
        assert!(!db.path.exists());
        assert!(!r.error_message().unwrap().contains("a\0b"));
    }

    #[test]
    fn the_sizes_of_a_pool_are_read() {
        let db = TempDb::new();
        let r = Res(open(&db.url(), KEY, 2, 3, 10_000, -1, -1)).ok();
        let rt = r.get().rt;
        for _ in 0..100 {
            if pool_idle_size(rt) == 2 {
                break;
            }
            std::thread::sleep(Duration::from_millis(10));
        }
        assert_eq!((pool_size(rt), pool_idle_size(rt)), (2, 2));
        let pool = unsafe { &*(rt as *const Sqlx4kSqliteCipher) };
        Res(on_runtime(pool.close())).ok();
    }

    #[test]
    fn a_closed_pool_runs_nothing() {
        let pool = Pool::open(1);
        let db = client(&pool);
        Res(on_runtime(db.close())).ok();
        assert_eq!(Res(on_runtime(db.query("select 1".to_string()))).get().error, ERROR_POOL_CLOSED);
    }
}
