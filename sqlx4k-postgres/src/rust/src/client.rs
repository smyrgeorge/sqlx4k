//! The pool and the queries run on it, on a connection or in a transaction.

use sqlx::pool::PoolConnection;
use sqlx::postgres::{PgConnectOptions, PgPool};
use sqlx::{Acquire, AssertSqlSafe, Connection, Executor, Postgres, Transaction};
use std::ffi::c_void;
use std::time::Duration;
use tokio::sync::{mpsc, oneshot};

use crate::errors::sqlx4k_postgres_error_result_of;
use crate::params::{bind_params, OwnedParam};
use crate::result::{fetch_last, sqlx4k_postgresql_result_of};
use crate::stream::{
    stream_result_of, stream_rows, stream_rows_of, Sqlx4kPostgresStream, STREAM_CHANNEL_CAPACITY,
};
use crate::types::{Sqlx4kPostgresPtr, Sqlx4kPostgresResult};
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

#[derive(Debug)]
pub(crate) struct Sqlx4kPostgreSql {
    pub(crate) connect_options: PgConnectOptions,
    pub(crate) pool: PgPool,
}

impl Sqlx4kPostgreSql {
    pub(crate) async fn query(&self, sql: String) -> *mut Sqlx4kPostgresResult {
        let result = self.pool.execute(AssertSqlSafe(sql)).await;
        let result = match result {
            Ok(res) => Sqlx4kPostgresResult {
                rows_affected: res.rows_affected(),
                ..Default::default()
            },
            Err(err) => sqlx4k_postgres_error_result_of(err),
        };
        result.leak()
    }

    pub(crate) async fn fetch_all(&self, sql: String) -> *mut Sqlx4kPostgresResult {
        let result = fetch_last(&self.pool, sql).await;
        sqlx4k_postgresql_result_of(result).leak()
    }

    pub(crate) async fn cn_acquire(&self) -> *mut Sqlx4kPostgresResult {
        let cn = self.pool.acquire().await;
        let cn: PoolConnection<Postgres> = match cn {
            Ok(cn) => cn,
            Err(err) => return sqlx4k_postgres_error_result_of(err).leak(),
        };

        let cn = Box::new(cn);
        let cn = Box::leak(cn);
        let result = Sqlx4kPostgresResult {
            cn: cn as *mut _ as *mut c_void,
            ..Default::default()
        };
        result.leak()
    }

    pub(crate) async fn cn_release(&self, cn: Sqlx4kPostgresPtr) -> *mut Sqlx4kPostgresResult {
        if cn.ptr.is_null() {
            return Sqlx4kPostgresResult::default().leak();
        }

        let cn_ptr = cn.ptr as *mut PoolConnection<Postgres>;
        unsafe {
            // Recreate the Box and drop it, returning connection to pool
            let _boxed: Box<PoolConnection<Postgres>> = Box::from_raw(cn_ptr);
        }

        Sqlx4kPostgresResult::default().leak()
    }

    pub(crate) async fn cn_query(&self, cn: Sqlx4kPostgresPtr, sql: String) -> *mut Sqlx4kPostgresResult {
        let cn = unsafe { &mut *(cn.ptr as *mut PoolConnection<Postgres>) };
        let result = cn.execute(AssertSqlSafe(sql)).await;
        let result = match result {
            Ok(res) => Sqlx4kPostgresResult {
                rows_affected: res.rows_affected(),
                ..Default::default()
            },
            Err(err) => sqlx4k_postgres_error_result_of(err),
        };
        result.leak()
    }

    pub(crate) async fn cn_fetch_all(&self, cn: Sqlx4kPostgresPtr, sql: String) -> *mut Sqlx4kPostgresResult {
        let cn = unsafe { &mut *(cn.ptr as *mut PoolConnection<Postgres>) };
        let result = fetch_last(&mut **cn, sql).await;
        sqlx4k_postgresql_result_of(result).leak()
    }

    pub(crate) async fn cn_tx_begin(&self, cn: Sqlx4kPostgresPtr) -> *mut Sqlx4kPostgresResult {
        let cn = unsafe { &mut *(cn.ptr as *mut PoolConnection<Postgres>) };
        let tx = cn.begin().await;
        let tx = match tx {
            Ok(tx) => tx,
            Err(err) => {
                return sqlx4k_postgres_error_result_of(err).leak();
            }
        };

        let tx = Box::new(tx);
        let tx = Box::leak(tx);

        let result = Sqlx4kPostgresResult {
            tx: tx as *mut _ as *mut c_void,
            ..Default::default()
        };
        result.leak()
    }

    pub(crate) async fn tx_begin(&self) -> *mut Sqlx4kPostgresResult {
        let tx = self.pool.begin().await;
        let tx = match tx {
            Ok(tx) => tx,
            Err(err) => {
                return sqlx4k_postgres_error_result_of(err).leak();
            }
        };

        let tx = Box::new(tx);
        let tx = Box::leak(tx);
        let result = Sqlx4kPostgresResult {
            tx: tx as *mut _ as *mut c_void,
            ..Default::default()
        };
        result.leak()
    }

    pub(crate) async fn tx_commit(&self, tx: Sqlx4kPostgresPtr) -> *mut Sqlx4kPostgresResult {
        let tx = unsafe { *Box::from_raw(tx.ptr as *mut Transaction<'_, Postgres>) };
        let result = match tx.commit().await {
            Ok(_) => Sqlx4kPostgresResult::default(),
            Err(err) => sqlx4k_postgres_error_result_of(err),
        };
        result.leak()
    }

    pub(crate) async fn tx_rollback(&self, tx: Sqlx4kPostgresPtr) -> *mut Sqlx4kPostgresResult {
        let tx = unsafe { *Box::from_raw(tx.ptr as *mut Transaction<'_, Postgres>) };
        let result = match tx.rollback().await {
            Ok(_) => Sqlx4kPostgresResult::default(),
            Err(err) => sqlx4k_postgres_error_result_of(err),
        };
        result.leak()
    }

    pub(crate) async fn tx_query(&self, tx: Sqlx4kPostgresPtr, sql: String) -> *mut Sqlx4kPostgresResult {
        let mut tx = unsafe { *Box::from_raw(tx.ptr as *mut Transaction<'_, Postgres>) };
        let result = tx.execute(AssertSqlSafe(sql)).await;
        let tx = Box::new(tx);
        let tx = Box::into_raw(tx);
        let result = match result {
            Ok(res) => Sqlx4kPostgresResult {
                rows_affected: res.rows_affected(),
                ..Default::default()
            },
            Err(err) => sqlx4k_postgres_error_result_of(err),
        };
        let result = Sqlx4kPostgresResult {
            tx: tx as *mut c_void,
            ..result
        };
        result.leak()
    }

    pub(crate) async fn tx_fetch_all(&self, tx: Sqlx4kPostgresPtr, sql: String) -> *mut Sqlx4kPostgresResult {
        let mut tx = unsafe { *Box::from_raw(tx.ptr as *mut Transaction<'_, Postgres>) };
        let result = fetch_last(&mut *tx, sql).await;
        let tx = Box::new(tx);
        let tx = Box::into_raw(tx);
        let result = sqlx4k_postgresql_result_of(result);
        let result = Sqlx4kPostgresResult {
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
    ) -> *mut Sqlx4kPostgresResult {
        let mut cn: PoolConnection<Postgres> = match self.pool.acquire().await {
            Ok(cn) => cn,
            Err(err) => return sqlx4k_postgres_error_result_of(err).leak(),
        };
        let (tx, rx) = mpsc::channel(STREAM_CHANNEL_CAPACITY);
        let (cancel, cancel_rx) = oneshot::channel();
        let task = RUNTIME.get().unwrap().spawn(async move {
            let exhausted = {
                let rows = stream_rows_of(&mut cn, sql, params);
                stream_rows(rows, fetch_size, tx, cancel_rx).await
            };
            if !exhausted {
                // Cancelled with rows pending: sqlx would read and discard the remainder on the next
                // use of this connection, which for a large result costs far more than a reconnect,
                // so the connection is closed instead of being returned to the pool.
                let _ = cn.detach().close().await;
            }
        });
        stream_result_of(Sqlx4kPostgresStream {
            rx: tokio::sync::Mutex::new(rx),
            cancel,
            task,
        })
    }

    pub(crate) async fn cn_stream_open(
        &self,
        cn: Sqlx4kPostgresPtr,
        sql: String,
        params: Vec<OwnedParam>,
        fetch_size: usize,
    ) -> *mut Sqlx4kPostgresResult {
        let (tx, rx) = mpsc::channel(STREAM_CHANNEL_CAPACITY);
        let (cancel, cancel_rx) = oneshot::channel();
        let task = RUNTIME.get().unwrap().spawn(async move {
            // The connection stays with Kotlin, which serializes its use and closes this stream
            // before any other statement; a cancelled stream leaves sqlx to drain the remaining
            // rows on the connection's next use.
            let cn = unsafe { &mut *(cn.raw() as *mut PoolConnection<Postgres>) };
            let rows = stream_rows_of(&mut **cn, sql, params);
            stream_rows(rows, fetch_size, tx, cancel_rx).await;
        });
        stream_result_of(Sqlx4kPostgresStream {
            rx: tokio::sync::Mutex::new(rx),
            cancel,
            task,
        })
    }

    pub(crate) async fn tx_stream_open(
        &self,
        tx: Sqlx4kPostgresPtr,
        sql: String,
        params: Vec<OwnedParam>,
        fetch_size: usize,
    ) -> *mut Sqlx4kPostgresResult {
        let (chunks, rx) = mpsc::channel(STREAM_CHANNEL_CAPACITY);
        let (cancel, cancel_rx) = oneshot::channel();
        let task = RUNTIME.get().unwrap().spawn(async move {
            // Borrowed in place (not re-boxed like `tx_fetch_all`), so the Kotlin-held pointer stays valid.
            let tx = unsafe { &mut *(tx.raw() as *mut Transaction<'static, Postgres>) };
            let rows = stream_rows_of(&mut **tx, sql, params);
            stream_rows(rows, fetch_size, chunks, cancel_rx).await;
        });
        stream_result_of(Sqlx4kPostgresStream {
            rx: tokio::sync::Mutex::new(rx),
            cancel,
            task,
        })
    }

    pub(crate) async fn stream_next(&self, stream: Sqlx4kPostgresPtr) -> *mut Sqlx4kPostgresResult {
        let stream = unsafe { &*(stream.ptr as *const Sqlx4kPostgresStream) };
        let mut rx = stream.rx.lock().await;
        match rx.recv().await {
            Some(chunk) => sqlx4k_postgresql_result_of(chunk).leak(),
            // End of the stream: an empty result (size 0, no schema).
            None => Sqlx4kPostgresResult::default().leak(),
        }
    }

    pub(crate) async fn stream_close(&self, stream: Sqlx4kPostgresPtr) -> *mut Sqlx4kPostgresResult {
        let stream = unsafe { Box::from_raw(stream.ptr as *mut Sqlx4kPostgresStream) };
        let Sqlx4kPostgresStream { rx, cancel, task } = *stream;
        // Stop the producer (dropping the sender fires its cancel branch, dropping the receiver fails
        // its next send) and wait for it: only then is the connection it borrowed free again.
        drop(cancel);
        drop(rx);
        let _ = task.await;
        Sqlx4kPostgresResult::default().leak()
    }

    pub(crate) async fn close(&self) -> *mut Sqlx4kPostgresResult {
        close_pool(&self.pool).await;
        Sqlx4kPostgresResult::default().leak()
    }

    pub(crate) async fn query_with_params(
        &self,
        sql: String,
        params: Vec<OwnedParam>,
    ) -> *mut Sqlx4kPostgresResult {
        let q = bind_params(sqlx::query::<Postgres>(AssertSqlSafe(sql)), params);
        let result = q.execute(&self.pool).await;
        let result = match result {
            Ok(res) => Sqlx4kPostgresResult {
                rows_affected: res.rows_affected(),
                ..Default::default()
            },
            Err(err) => sqlx4k_postgres_error_result_of(err),
        };
        result.leak()
    }

    pub(crate) async fn fetch_all_with_params(
        &self,
        sql: String,
        params: Vec<OwnedParam>,
    ) -> *mut Sqlx4kPostgresResult {
        let q = bind_params(sqlx::query::<Postgres>(AssertSqlSafe(sql)), params);
        let result = q.fetch_all(&self.pool).await;
        sqlx4k_postgresql_result_of(result).leak()
    }

    pub(crate) async fn cn_query_with_params(
        &self,
        cn: Sqlx4kPostgresPtr,
        sql: String,
        params: Vec<OwnedParam>,
    ) -> *mut Sqlx4kPostgresResult {
        let cn = unsafe { &mut *(cn.ptr as *mut PoolConnection<Postgres>) };
        let q = bind_params(sqlx::query::<Postgres>(AssertSqlSafe(sql)), params);
        let result = q.execute(&mut **cn).await;
        let result = match result {
            Ok(res) => Sqlx4kPostgresResult {
                rows_affected: res.rows_affected(),
                ..Default::default()
            },
            Err(err) => sqlx4k_postgres_error_result_of(err),
        };
        result.leak()
    }

    pub(crate) async fn cn_fetch_all_with_params(
        &self,
        cn: Sqlx4kPostgresPtr,
        sql: String,
        params: Vec<OwnedParam>,
    ) -> *mut Sqlx4kPostgresResult {
        let cn = unsafe { &mut *(cn.ptr as *mut PoolConnection<Postgres>) };
        let q = bind_params(sqlx::query::<Postgres>(AssertSqlSafe(sql)), params);
        let result = q.fetch_all(&mut **cn).await;
        sqlx4k_postgresql_result_of(result).leak()
    }

    pub(crate) async fn tx_query_with_params(
        &self,
        tx: Sqlx4kPostgresPtr,
        sql: String,
        params: Vec<OwnedParam>,
    ) -> *mut Sqlx4kPostgresResult {
        let mut tx = unsafe { *Box::from_raw(tx.ptr as *mut Transaction<'_, Postgres>) };
        let q = bind_params(sqlx::query::<Postgres>(AssertSqlSafe(sql)), params);
        let result = q.execute(&mut *tx).await;
        let tx = Box::new(tx);
        let tx = Box::into_raw(tx);
        let result = match result {
            Ok(res) => Sqlx4kPostgresResult {
                rows_affected: res.rows_affected(),
                ..Default::default()
            },
            Err(err) => sqlx4k_postgres_error_result_of(err),
        };
        let result = Sqlx4kPostgresResult {
            tx: tx as *mut c_void,
            ..result
        };
        result.leak()
    }

    pub(crate) async fn tx_fetch_all_with_params(
        &self,
        tx: Sqlx4kPostgresPtr,
        sql: String,
        params: Vec<OwnedParam>,
    ) -> *mut Sqlx4kPostgresResult {
        let mut tx = unsafe { *Box::from_raw(tx.ptr as *mut Transaction<'_, Postgres>) };
        let q = bind_params(sqlx::query::<Postgres>(AssertSqlSafe(sql)), params);
        let result = q.fetch_all(&mut *tx).await;
        let tx = Box::new(tx);
        let tx = Box::into_raw(tx);
        let result = sqlx4k_postgresql_result_of(result);
        let result = Sqlx4kPostgresResult {
            tx: tx as *mut c_void,
            ..result
        };
        result.leak()
    }
}
