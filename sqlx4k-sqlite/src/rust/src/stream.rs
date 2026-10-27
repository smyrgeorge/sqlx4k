//! Streaming the rows of a query to Kotlin, chunk by chunk.

use futures_core::Stream;
use futures_util::TryStreamExt;
use sqlx::sqlite::{SqliteConnection, SqliteRow};
use sqlx::{AssertSqlSafe, Executor, Sqlite};
use std::ffi::{c_int, c_void};
use std::pin::Pin;
use tokio::sync::{mpsc, oneshot};
use tokio::task::JoinHandle;

use crate::params::{bind_params, OwnedParam};
use crate::types::Sqlx4kSqliteResult;

/// Rows are moved to Kotlin in chunks of the requested fetch size; the channel holds at most this
/// many ready chunks, which is the backpressure that keeps a slow consumer from buffering a large
/// result on the Rust side.
pub(crate) const STREAM_CHANNEL_CAPACITY: usize = 2;

pub(crate) const STREAM_DEFAULT_FETCH_SIZE: usize = 1_000;

/// The most rows a chunk is allocated room for up front: a fetch size is a hint, and a large one (`Int.MAX_VALUE`)
/// asked for room for that many rows at once, hundreds of GB, which aborted the process (`panic = "abort"`). A chunk
/// still grows to the fetch size.
pub(crate) const STREAM_MAX_PREALLOCATED_ROWS: usize = 1_000;

pub(crate) type StreamChunk = Result<Vec<SqliteRow>, sqlx::Error>;

pub(crate) type RowStream<'e> = Pin<Box<dyn Stream<Item = Result<SqliteRow, sqlx::Error>> + Send + 'e>>;

/// A result set being streamed to Kotlin, row chunk by row chunk.
///
/// sqlx steps the statement on its SQLite worker thread and hands the rows over a bounded channel;
/// that stream borrows the connection it runs on, so both live in a spawned task that owns them and
/// sends the chunks through `rx`. `sqlx4k_sqlite_stream_next` receives one chunk;
/// `sqlx4k_sqlite_stream_close` stops the task (dropping `cancel`) and joins it before freeing the
/// handle, so the connection or transaction the task was borrowing is no longer in use once Kotlin
/// continues. Dropping the stream resets the statement, so a cancelled stream costs nothing and
/// the connection is simply returned to the pool.
pub(crate) struct Sqlx4kSqliteStream {
    pub(crate) rx: tokio::sync::Mutex<mpsc::Receiver<StreamChunk>>,
    pub(crate) cancel: oneshot::Sender<()>,
    pub(crate) task: JoinHandle<()>,
}

/// Opens the row stream of a query on the given connection: a plain query (as `fetch_all` does),
/// or a prepared statement when there are parameters to bind.
pub(crate) fn stream_rows_of<'e>(
    cn: &'e mut SqliteConnection,
    sql: String,
    params: Vec<OwnedParam>,
) -> RowStream<'e> {
    if params.is_empty() {
        cn.fetch(AssertSqlSafe(sql))
    } else {
        bind_params(sqlx::query::<Sqlite>(AssertSqlSafe(sql)), params).fetch(cn)
    }
}

/// Drives a row stream, sending chunks of `fetch_size` rows until the stream ends or fails, or the
/// consumer closes it (`cancel` fires, or the channel is dropped).
pub(crate) async fn stream_rows(
    mut rows: RowStream<'_>,
    fetch_size: usize,
    tx: mpsc::Sender<StreamChunk>,
    mut cancel: oneshot::Receiver<()>,
) {
    let mut chunk: Vec<SqliteRow> = Vec::with_capacity(fetch_size.min(STREAM_MAX_PREALLOCATED_ROWS));
    loop {
        let next = tokio::select! {
            _ = &mut cancel => return,
            next = rows.try_next() => next,
        };
        match next {
            Ok(Some(row)) => {
                chunk.push(row);
                if chunk.len() >= fetch_size {
                    let full = std::mem::replace(&mut chunk, Vec::with_capacity(fetch_size.min(STREAM_MAX_PREALLOCATED_ROWS)));
                    tokio::select! {
                        _ = &mut cancel => return,
                        sent = tx.send(Ok(full)) => if sent.is_err() { return },
                    }
                }
            }
            Ok(None) => {
                if !chunk.is_empty() {
                    let _ = tx.send(Ok(chunk)).await;
                }
                return;
            }
            Err(err) => {
                let _ = tx.send(Err(err)).await;
                return;
            }
        }
    }
}

pub(crate) fn stream_fetch_size(fetch_size: c_int) -> usize {
    if fetch_size > 0 {
        fetch_size as usize
    } else {
        STREAM_DEFAULT_FETCH_SIZE
    }
}

pub(crate) fn stream_result_of(stream: Sqlx4kSqliteStream) -> *mut Sqlx4kSqliteResult {
    Sqlx4kSqliteResult {
        stream: Box::into_raw(Box::new(stream)) as *mut c_void,
        ..Default::default()
    }
    .leak()
}
