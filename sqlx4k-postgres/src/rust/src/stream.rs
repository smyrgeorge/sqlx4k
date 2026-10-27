//! Streaming the rows of a query to Kotlin, chunk by chunk.

use futures_core::Stream;
use futures_util::TryStreamExt;
use sqlx::postgres::{PgConnection, PgQueryResult, PgRow};
use sqlx::{AssertSqlSafe, Executor, Postgres};
use std::ffi::{c_int, c_void};
use std::pin::Pin;
use tokio::sync::{mpsc, oneshot};
use tokio::task::JoinHandle;

use crate::params::{bind_params, OwnedParam};
use crate::types::Sqlx4kPostgresResult;

/// Rows are moved to Kotlin in chunks of the requested fetch size; the channel holds at most this
/// many ready chunks, which is the backpressure that keeps a slow consumer from buffering a large
/// result on the Rust side.
pub(crate) const STREAM_CHANNEL_CAPACITY: usize = 2;

pub(crate) const STREAM_DEFAULT_FETCH_SIZE: usize = 500;

/// The most rows a chunk is allocated room for up front: a fetch size is a hint, and a large one (`Int.MAX_VALUE`)
/// asked for room for that many rows at once, hundreds of GB, which aborted the process (`panic = "abort"`). A chunk
/// still grows to the fetch size.
pub(crate) const STREAM_MAX_PREALLOCATED_ROWS: usize = 1_000;

pub(crate) type StreamChunk = Result<Vec<PgRow>, sqlx::Error>;

/// The rows of a query, each statement's rows followed by its result (`Left`), so a chunk can end where a
/// statement does.
pub(crate) type RowStream<'e> =
    Pin<Box<dyn Stream<Item = Result<sqlx::Either<PgQueryResult, PgRow>, sqlx::Error>> + Send + 'e>>;

/// A result set being streamed to Kotlin, row chunk by row chunk.
///
/// sqlx streams rows straight off the wire (no server-side cursor), and the stream borrows the
/// connection it runs on, so both live in a spawned task that owns them and sends the chunks through
/// `rx`. `sqlx4k_postgresql_stream_next` receives one chunk; `sqlx4k_postgresql_stream_close` stops
/// the task (dropping `cancel`) and joins it before freeing the handle, so the connection or
/// transaction the task was borrowing is no longer in use once Kotlin continues.
pub(crate) struct Sqlx4kPostgresStream {
    pub(crate) rx: tokio::sync::Mutex<mpsc::Receiver<StreamChunk>>,
    pub(crate) cancel: oneshot::Sender<()>,
    pub(crate) task: JoinHandle<()>,
}

/// Opens the row stream of a query on the given connection: the simple protocol for a plain query
/// (as `fetch_all` does), a prepared statement when there are parameters to bind. The simple protocol
/// runs every statement of the SQL and returns the rows of each one after the other.
pub(crate) fn stream_rows_of<'e>(
    cn: &'e mut PgConnection,
    sql: String,
    params: Vec<OwnedParam>,
) -> RowStream<'e> {
    if params.is_empty() {
        cn.fetch_many(AssertSqlSafe(sql))
    } else {
        Box::pin(
            bind_params(sqlx::query::<Postgres>(AssertSqlSafe(sql)), params)
                .fetch(cn)
                .map_ok(sqlx::Either::Right),
        )
    }
}

/// Drives a row stream, sending chunks of `fetch_size` rows until the stream ends or fails, or the
/// consumer closes it (`cancel` fires, or the channel is dropped). Returns whether the stream was
/// consumed to its end (an error counts as the end: the connection is in a clean state again).
///
/// A chunk also ends where a statement does: its schema is read from its first row, so it must not
/// hold the rows of two statements, whose columns differ.
pub(crate) async fn stream_rows(
    mut rows: RowStream<'_>,
    fetch_size: usize,
    tx: mpsc::Sender<StreamChunk>,
    mut cancel: oneshot::Receiver<()>,
) -> bool {
    let mut chunk: Vec<PgRow> = Vec::with_capacity(fetch_size.min(STREAM_MAX_PREALLOCATED_ROWS));
    // The item after the end of a statement, read before sending the chunk the statement ended (see below).
    let mut lookahead = None;
    loop {
        let next = if let Some(next) = lookahead.take() {
            next
        } else {
            tokio::select! {
                _ = &mut cancel => return false,
                next = rows.try_next() => next,
            }
        };
        match next {
            Ok(Some(item)) => {
                let full = match item {
                    sqlx::Either::Right(row) => {
                        chunk.push(row);
                        chunk.len() >= fetch_size
                    }
                    // The end of a statement: what follows is read first, so that a stream stopped while sending
                    // its last chunk is known to have been consumed to its end (its connection is clean).
                    sqlx::Either::Left(_) => {
                        lookahead = Some(rows.try_next().await);
                        !chunk.is_empty()
                    }
                };
                if full {
                    let ended = matches!(lookahead, Some(Ok(None)));
                    let full = std::mem::replace(&mut chunk, Vec::with_capacity(fetch_size.min(STREAM_MAX_PREALLOCATED_ROWS)));
                    tokio::select! {
                        _ = &mut cancel => return ended,
                        sent = tx.send(Ok(full)) => if sent.is_err() { return ended },
                    }
                }
            }
            Ok(None) => {
                if !chunk.is_empty() {
                    let _ = tx.send(Ok(chunk)).await;
                }
                return true;
            }
            Err(err) => {
                let _ = tx.send(Err(err)).await;
                return true;
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

pub(crate) fn stream_result_of(stream: Sqlx4kPostgresStream) -> *mut Sqlx4kPostgresResult {
    Sqlx4kPostgresResult {
        stream: Box::into_raw(Box::new(stream)) as *mut c_void,
        ..Default::default()
    }
    .leak()
}
