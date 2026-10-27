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

#[cfg(test)]
mod tests {
    use super::*;
    use crate::test_support::{block_on, connect_options};
    use sqlx::{Connection, Row};

    #[test]
    fn a_fetch_size_of_zero_or_less_is_the_default() {
        assert_eq!(stream_fetch_size(0), STREAM_DEFAULT_FETCH_SIZE);
        assert_eq!(stream_fetch_size(-1), STREAM_DEFAULT_FETCH_SIZE);
        assert_eq!(stream_fetch_size(7), 7);
        assert_eq!(stream_fetch_size(c_int::MAX), c_int::MAX as usize);
    }

    /// Streams `sql` on a connection of its own in chunks of `fetch_size`: whether it was consumed to its end, and the
    /// chunks sent (their rows' sizes, or an error). `cancel_first` closes the stream before it starts.
    fn stream(sql: &str, params: Vec<OwnedParam>, fetch_size: usize, cancel_first: bool) -> (bool, Vec<Result<Vec<usize>, String>>) {
        block_on(async {
            let mut cn = PgConnection::connect_with(&connect_options()).await.unwrap();
            let (tx, mut rx) = mpsc::channel(STREAM_CHANNEL_CAPACITY);
            let (cancel, cancel_rx) = oneshot::channel();
            if cancel_first {
                drop(cancel);
            } else {
                std::mem::forget(cancel);
            }
            let (ended, chunks) = {
                let rows = stream_rows_of(&mut cn, sql.to_string(), params);
                tokio::join!(stream_rows(rows, fetch_size, tx, cancel_rx), async {
                    let mut chunks = Vec::new();
                    while let Some(chunk) = rx.recv().await {
                        chunks.push(chunk.map(|rows| rows.iter().map(|r| r.len()).collect()).map_err(|e| e.to_string()));
                    }
                    chunks
                })
            };
            // The connection is usable after the stream.
            cn.execute("select 1").await.unwrap();
            cn.close().await.unwrap();
            (ended, chunks)
        })
    }

    #[test]
    #[ignore = "needs the PostgreSQL of docker-compose.yml"]
    fn rows_are_sent_in_chunks_of_the_fetch_size() {
        let (ended, chunks) = stream("select g from generate_series(1, 25) g", vec![], 10, false);
        assert!(ended);
        let sizes: Vec<usize> = chunks.into_iter().map(|c| c.unwrap().len()).collect();
        assert_eq!(sizes, [10, 10, 5]);
    }

    #[test]
    #[ignore = "needs the PostgreSQL of docker-compose.yml"]
    fn a_chunk_ends_where_a_statement_does() {
        let (ended, chunks) = stream("select 1 as a; select 2 as a, 3 as b; select 4 as c", vec![], 10, false);
        assert!(ended);
        // A row each, of its own statement's columns.
        let chunks: Vec<Vec<usize>> = chunks.into_iter().map(|c| c.unwrap()).collect();
        assert_eq!(chunks, [vec![1], vec![2], vec![1]]);
    }

    #[test]
    #[ignore = "needs the PostgreSQL of docker-compose.yml"]
    fn a_prepared_statement_is_streamed() {
        let (ended, chunks) = stream("select g from generate_series(1, $1) g", vec![OwnedParam::Int4(3)], 2, false);
        assert!(ended);
        assert_eq!(chunks.into_iter().map(|c| c.unwrap().len()).collect::<Vec<_>>(), [2, 1]);
    }

    #[test]
    #[ignore = "needs the PostgreSQL of docker-compose.yml"]
    fn an_empty_result_sends_no_chunk() {
        let (ended, chunks) = stream("select 1 where false", vec![], 10, false);
        assert!(ended);
        assert!(chunks.is_empty());
    }

    #[test]
    #[ignore = "needs the PostgreSQL of docker-compose.yml"]
    fn a_closed_stream_stops_before_its_end() {
        let (ended, chunks) = stream("select g from generate_series(1, 25) g", vec![], 10, true);
        assert!(!ended);
        assert!(chunks.is_empty());
    }

    #[test]
    #[ignore = "needs the PostgreSQL of docker-compose.yml"]
    fn an_error_is_sent_and_ends_the_stream() {
        let (ended, chunks) = stream("select * from missing_table", vec![], 10, false);
        assert!(ended);
        assert_eq!(chunks.len(), 1);
        assert!(chunks[0].as_ref().unwrap_err().contains("missing_table"));
    }
}
