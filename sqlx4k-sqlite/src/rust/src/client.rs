//! The pool (its options from the URL) and the queries run on it, on a connection or in a transaction.

use sqlx::pool::PoolConnection;
use sqlx::sqlite::{SqlitePool, SqlitePoolOptions};
use sqlx::{Acquire, AssertSqlSafe, Executor, Sqlite, Transaction};
use std::ffi::{c_int, c_void};
use std::time::Duration;
use tokio::sync::{mpsc, oneshot};

use crate::errors::sqlx4k_sqlite_error_result_of;
use crate::params::{bind_params, OwnedParam};
use crate::result::sqlx4k_sqlite_result_of;
use crate::stream::{
    stream_result_of, stream_rows, stream_rows_of, Sqlx4kSqliteStream, STREAM_CHANNEL_CAPACITY,
};
use crate::types::{Sqlx4kSqlitePtr, Sqlx4kSqliteResult};
use crate::upsert::{inserted_rowid, is_upsert, last_insert_rowid};
use crate::RUNTIME;

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

#[derive(Debug)]
pub(crate) struct Sqlx4kSqlite {
    pub(crate) pool: SqlitePool,
}

impl Sqlx4kSqlite {
    pub(crate) async fn query(&self, sql: String) -> *mut Sqlx4kSqliteResult {
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
            Ok(res) => Sqlx4kSqliteResult {
                rows_affected: res.rows_affected(),
                last_insert_id: inserted_rowid(&res, before),
                ..Default::default()
            },
            Err(err) => sqlx4k_sqlite_error_result_of(err),
        };
        result.leak()
    }

    pub(crate) async fn fetch_all(&self, sql: String) -> *mut Sqlx4kSqliteResult {
        let result = self.pool.fetch_all(AssertSqlSafe(sql)).await;
        sqlx4k_sqlite_result_of(result).leak()
    }

    pub(crate) async fn cn_acquire(&self) -> *mut Sqlx4kSqliteResult {
        let cn = self.pool.acquire().await;
        let cn: PoolConnection<Sqlite> = match cn {
            Ok(cn) => cn,
            Err(err) => return sqlx4k_sqlite_error_result_of(err).leak(),
        };

        let cn = Box::new(cn);
        let cn = Box::leak(cn);
        let result = Sqlx4kSqliteResult {
            cn: cn as *mut _ as *mut c_void,
            ..Default::default()
        };
        result.leak()
    }

    pub(crate) async fn cn_release(&self, cn: Sqlx4kSqlitePtr) -> *mut Sqlx4kSqliteResult {
        if cn.ptr.is_null() {
            return Sqlx4kSqliteResult::default().leak();
        }

        let cn_ptr = cn.ptr as *mut PoolConnection<Sqlite>;
        unsafe {
            // Recreate the Box and drop it, returning connection to pool
            let _boxed: Box<PoolConnection<Sqlite>> = Box::from_raw(cn_ptr);
        }

        Sqlx4kSqliteResult::default().leak()
    }

    pub(crate) async fn cn_query(&self, cn: Sqlx4kSqlitePtr, sql: String) -> *mut Sqlx4kSqliteResult {
        let cn = unsafe { &mut *(cn.ptr as *mut PoolConnection<Sqlite>) };
        let before = if is_upsert(&sql) { last_insert_rowid(&mut **cn).await } else { None };
        let result = cn.execute(AssertSqlSafe(sql)).await;
        let result = match result {
            Ok(res) => Sqlx4kSqliteResult {
                rows_affected: res.rows_affected(),
                last_insert_id: inserted_rowid(&res, before),
                ..Default::default()
            },
            Err(err) => sqlx4k_sqlite_error_result_of(err),
        };
        result.leak()
    }

    pub(crate) async fn cn_fetch_all(&self, cn: Sqlx4kSqlitePtr, sql: String) -> *mut Sqlx4kSqliteResult {
        let cn = unsafe { &mut *(cn.ptr as *mut PoolConnection<Sqlite>) };
        let result = cn.fetch_all(AssertSqlSafe(sql)).await;
        sqlx4k_sqlite_result_of(result).leak()
    }

    pub(crate) async fn cn_tx_begin(&self, cn: Sqlx4kSqlitePtr) -> *mut Sqlx4kSqliteResult {
        let cn = unsafe { &mut *(cn.ptr as *mut PoolConnection<Sqlite>) };
        let tx = cn.begin().await;
        let tx = match tx {
            Ok(tx) => tx,
            Err(err) => {
                return sqlx4k_sqlite_error_result_of(err).leak();
            }
        };

        let tx = Box::new(tx);
        let tx = Box::leak(tx);

        let result = Sqlx4kSqliteResult {
            tx: tx as *mut _ as *mut c_void,
            ..Default::default()
        };
        result.leak()
    }

    pub(crate) async fn tx_begin(&self) -> *mut Sqlx4kSqliteResult {
        let tx = self.pool.begin().await;
        let tx = match tx {
            Ok(tx) => tx,
            Err(err) => {
                return sqlx4k_sqlite_error_result_of(err).leak();
            }
        };

        let tx = Box::new(tx);
        let tx = Box::leak(tx);
        let result = Sqlx4kSqliteResult {
            tx: tx as *mut _ as *mut c_void,
            ..Default::default()
        };
        result.leak()
    }

    pub(crate) async fn tx_commit(&self, tx: Sqlx4kSqlitePtr) -> *mut Sqlx4kSqliteResult {
        let tx = unsafe { *Box::from_raw(tx.ptr as *mut Transaction<'_, Sqlite>) };
        let result = match tx.commit().await {
            Ok(_) => Sqlx4kSqliteResult::default(),
            Err(err) => sqlx4k_sqlite_error_result_of(err),
        };
        result.leak()
    }

    pub(crate) async fn tx_rollback(&self, tx: Sqlx4kSqlitePtr) -> *mut Sqlx4kSqliteResult {
        let tx = unsafe { *Box::from_raw(tx.ptr as *mut Transaction<'_, Sqlite>) };
        let result = match tx.rollback().await {
            Ok(_) => Sqlx4kSqliteResult::default(),
            Err(err) => sqlx4k_sqlite_error_result_of(err),
        };
        result.leak()
    }

    pub(crate) async fn tx_query(&self, tx: Sqlx4kSqlitePtr, sql: String) -> *mut Sqlx4kSqliteResult {
        let mut tx = unsafe { *Box::from_raw(tx.ptr as *mut Transaction<'_, Sqlite>) };
        let before = if is_upsert(&sql) { last_insert_rowid(&mut *tx).await } else { None };
        let result = tx.execute(AssertSqlSafe(sql)).await;
        let tx = Box::new(tx);
        let tx = Box::into_raw(tx);
        let result = match result {
            Ok(res) => Sqlx4kSqliteResult {
                rows_affected: res.rows_affected(),
                last_insert_id: inserted_rowid(&res, before),
                ..Default::default()
            },
            Err(err) => sqlx4k_sqlite_error_result_of(err),
        };
        let result = Sqlx4kSqliteResult {
            tx: tx as *mut c_void,
            ..result
        };
        result.leak()
    }

    pub(crate) async fn tx_fetch_all(&self, tx: Sqlx4kSqlitePtr, sql: String) -> *mut Sqlx4kSqliteResult {
        let mut tx = unsafe { *Box::from_raw(tx.ptr as *mut Transaction<'_, Sqlite>) };
        let result = tx.fetch_all(AssertSqlSafe(sql)).await;
        let tx = Box::new(tx);
        let tx = Box::into_raw(tx);
        let result = sqlx4k_sqlite_result_of(result);
        let result = Sqlx4kSqliteResult {
            tx: tx as *mut c_void,
            ..result
        };
        result.leak()
    }

    pub(crate) async fn stream_open(
        &self,
        sql: String,
        params: Vec<OwnedParam>,
        fetch_size: usize,
    ) -> *mut Sqlx4kSqliteResult {
        let mut cn: PoolConnection<Sqlite> = match self.pool.acquire().await {
            Ok(cn) => cn,
            Err(err) => return sqlx4k_sqlite_error_result_of(err).leak(),
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
        stream_result_of(Sqlx4kSqliteStream {
            rx: tokio::sync::Mutex::new(rx),
            cancel,
            task,
        })
    }

    pub(crate) async fn cn_stream_open(
        &self,
        cn: Sqlx4kSqlitePtr,
        sql: String,
        params: Vec<OwnedParam>,
        fetch_size: usize,
    ) -> *mut Sqlx4kSqliteResult {
        let (tx, rx) = mpsc::channel(STREAM_CHANNEL_CAPACITY);
        let (cancel, cancel_rx) = oneshot::channel();
        let task = RUNTIME.get().unwrap().spawn(async move {
            // The connection stays with Kotlin, which serializes its use and closes this stream
            // before any other statement.
            let cn = unsafe { &mut *(cn.raw() as *mut PoolConnection<Sqlite>) };
            let rows = stream_rows_of(&mut **cn, sql, params);
            stream_rows(rows, fetch_size, tx, cancel_rx).await;
        });
        stream_result_of(Sqlx4kSqliteStream {
            rx: tokio::sync::Mutex::new(rx),
            cancel,
            task,
        })
    }

    pub(crate) async fn tx_stream_open(
        &self,
        tx: Sqlx4kSqlitePtr,
        sql: String,
        params: Vec<OwnedParam>,
        fetch_size: usize,
    ) -> *mut Sqlx4kSqliteResult {
        let (chunks, rx) = mpsc::channel(STREAM_CHANNEL_CAPACITY);
        let (cancel, cancel_rx) = oneshot::channel();
        let task = RUNTIME.get().unwrap().spawn(async move {
            // Borrowed in place (not re-boxed like `tx_fetch_all`), so the Kotlin-held pointer stays valid.
            let tx = unsafe { &mut *(tx.raw() as *mut Transaction<'static, Sqlite>) };
            let rows = stream_rows_of(&mut **tx, sql, params);
            stream_rows(rows, fetch_size, chunks, cancel_rx).await;
        });
        stream_result_of(Sqlx4kSqliteStream {
            rx: tokio::sync::Mutex::new(rx),
            cancel,
            task,
        })
    }

    pub(crate) async fn stream_next(&self, stream: Sqlx4kSqlitePtr) -> *mut Sqlx4kSqliteResult {
        let stream = unsafe { &*(stream.ptr as *const Sqlx4kSqliteStream) };
        let mut rx = stream.rx.lock().await;
        match rx.recv().await {
            Some(chunk) => sqlx4k_sqlite_result_of(chunk).leak(),
            // End of the stream: an empty result (size 0, no schema).
            None => Sqlx4kSqliteResult::default().leak(),
        }
    }

    pub(crate) async fn stream_close(&self, stream: Sqlx4kSqlitePtr) -> *mut Sqlx4kSqliteResult {
        let stream = unsafe { Box::from_raw(stream.ptr as *mut Sqlx4kSqliteStream) };
        let Sqlx4kSqliteStream { rx, cancel, task } = *stream;
        // Stop the producer (dropping the sender fires its cancel branch, dropping the receiver fails
        // its next send) and wait for it: only then is the connection it borrowed free again.
        drop(cancel);
        drop(rx);
        let _ = task.await;
        Sqlx4kSqliteResult::default().leak()
    }

    pub(crate) async fn close(&self) -> *mut Sqlx4kSqliteResult {
        close_pool(&self.pool).await;
        Sqlx4kSqliteResult::default().leak()
    }

    pub(crate) async fn query_with_params(
        &self,
        sql: String,
        params: Vec<OwnedParam>,
    ) -> *mut Sqlx4kSqliteResult {
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
            Ok(res) => Sqlx4kSqliteResult {
                rows_affected: res.rows_affected(),
                last_insert_id: inserted_rowid(&res, before),
                ..Default::default()
            },
            Err(err) => sqlx4k_sqlite_error_result_of(err),
        };
        result.leak()
    }

    pub(crate) async fn fetch_all_with_params(
        &self,
        sql: String,
        params: Vec<OwnedParam>,
    ) -> *mut Sqlx4kSqliteResult {
        let q = bind_params(sqlx::query::<Sqlite>(AssertSqlSafe(sql)), params);
        let result = q.fetch_all(&self.pool).await;
        sqlx4k_sqlite_result_of(result).leak()
    }

    pub(crate) async fn cn_query_with_params(
        &self,
        cn: Sqlx4kSqlitePtr,
        sql: String,
        params: Vec<OwnedParam>,
    ) -> *mut Sqlx4kSqliteResult {
        let cn = unsafe { &mut *(cn.ptr as *mut PoolConnection<Sqlite>) };
        let upsert = is_upsert(&sql);
        let q = bind_params(sqlx::query::<Sqlite>(AssertSqlSafe(sql)), params);
        let before = if upsert { last_insert_rowid(&mut **cn).await } else { None };
        let result = q.execute(&mut **cn).await;
        let result = match result {
            Ok(res) => Sqlx4kSqliteResult {
                rows_affected: res.rows_affected(),
                last_insert_id: inserted_rowid(&res, before),
                ..Default::default()
            },
            Err(err) => sqlx4k_sqlite_error_result_of(err),
        };
        result.leak()
    }

    pub(crate) async fn cn_fetch_all_with_params(
        &self,
        cn: Sqlx4kSqlitePtr,
        sql: String,
        params: Vec<OwnedParam>,
    ) -> *mut Sqlx4kSqliteResult {
        let cn = unsafe { &mut *(cn.ptr as *mut PoolConnection<Sqlite>) };
        let q = bind_params(sqlx::query::<Sqlite>(AssertSqlSafe(sql)), params);
        let result = q.fetch_all(&mut **cn).await;
        sqlx4k_sqlite_result_of(result).leak()
    }

    pub(crate) async fn tx_query_with_params(
        &self,
        tx: Sqlx4kSqlitePtr,
        sql: String,
        params: Vec<OwnedParam>,
    ) -> *mut Sqlx4kSqliteResult {
        let mut tx = unsafe { *Box::from_raw(tx.ptr as *mut Transaction<'_, Sqlite>) };
        let upsert = is_upsert(&sql);
        let q = bind_params(sqlx::query::<Sqlite>(AssertSqlSafe(sql)), params);
        let before = if upsert { last_insert_rowid(&mut *tx).await } else { None };
        let result = q.execute(&mut *tx).await;
        let tx = Box::new(tx);
        let tx = Box::into_raw(tx);
        let result = match result {
            Ok(res) => Sqlx4kSqliteResult {
                rows_affected: res.rows_affected(),
                last_insert_id: inserted_rowid(&res, before),
                ..Default::default()
            },
            Err(err) => sqlx4k_sqlite_error_result_of(err),
        };
        let result = Sqlx4kSqliteResult {
            tx: tx as *mut c_void,
            ..result
        };
        result.leak()
    }

    pub(crate) async fn tx_fetch_all_with_params(
        &self,
        tx: Sqlx4kSqlitePtr,
        sql: String,
        params: Vec<OwnedParam>,
    ) -> *mut Sqlx4kSqliteResult {
        let mut tx = unsafe { *Box::from_raw(tx.ptr as *mut Transaction<'_, Sqlite>) };
        let q = bind_params(sqlx::query::<Sqlite>(AssertSqlSafe(sql)), params);
        let result = q.fetch_all(&mut *tx).await;
        let tx = Box::new(tx);
        let tx = Box::into_raw(tx);
        let result = sqlx4k_sqlite_result_of(result);
        let result = Sqlx4kSqliteResult {
            tx: tx as *mut c_void,
            ..result
        };
        result.leak()
    }
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
