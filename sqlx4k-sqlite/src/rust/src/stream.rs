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

#[cfg(test)]
mod tests {
    use super::*;
    use crate::test_support::{block_on, memory};
    use sqlx::{Connection, Row};

    const SERIES: &str = "with recursive s(n) as (select 1 union all select n + 1 from s where n < 25) select n from s";

    #[test]
    fn a_fetch_size_of_zero_or_less_is_the_default() {
        assert_eq!(stream_fetch_size(0), STREAM_DEFAULT_FETCH_SIZE);
        assert_eq!(stream_fetch_size(-1), STREAM_DEFAULT_FETCH_SIZE);
        assert_eq!(stream_fetch_size(7), 7);
    }

    /// Streams `sql` on a connection of its own in chunks of `fetch_size`: the chunks sent (their rows' first values,
    /// or an error). `cancel_first` closes the stream before it starts.
    fn stream(sql: &str, params: Vec<OwnedParam>, fetch_size: usize, cancel_first: bool) -> Vec<Result<Vec<i64>, String>> {
        block_on(async {
            let mut cn = memory().await;
            let (tx, mut rx) = mpsc::channel(STREAM_CHANNEL_CAPACITY);
            let (cancel, cancel_rx) = oneshot::channel();
            if cancel_first {
                drop(cancel);
            } else {
                std::mem::forget(cancel);
            }
            let ((), chunks) = {
                let rows = stream_rows_of(&mut cn, sql.to_string(), params);
                tokio::join!(stream_rows(rows, fetch_size, tx, cancel_rx), async {
                    let mut chunks = Vec::new();
                    while let Some(chunk) = rx.recv().await {
                        chunks.push(chunk.map(|rows| rows.iter().map(|r| r.get::<i64, _>(0)).collect()).map_err(|e| e.to_string()));
                    }
                    chunks
                })
            };
            // The connection is usable after the stream.
            let one: i64 = sqlx::query_scalar("select 1").fetch_one(&mut cn).await.unwrap();
            assert_eq!(one, 1);
            cn.close().await.unwrap();
            chunks
        })
    }

    fn sizes(chunks: Vec<Result<Vec<i64>, String>>) -> Vec<usize> {
        chunks.into_iter().map(|c| c.unwrap().len()).collect()
    }

    #[test]
    fn rows_are_sent_in_chunks_of_the_fetch_size() {
        let chunks = stream(SERIES, vec![], 10, false);
        assert_eq!(chunks.iter().flat_map(|c| c.clone().unwrap()).collect::<Vec<_>>(), (1..=25).collect::<Vec<_>>());
        assert_eq!(sizes(chunks), [10, 10, 5]);
    }

    #[test]
    fn a_huge_fetch_size_is_one_chunk() {
        assert_eq!(sizes(stream(SERIES, vec![], i32::MAX as usize, false)), [25]);
    }

    #[test]
    fn a_prepared_statement_is_streamed() {
        let chunks = stream("select ? union all select ?", vec![OwnedParam::Int(1), OwnedParam::Int(2)], 1, false);
        assert_eq!(chunks, [Ok(vec![1]), Ok(vec![2])]);
    }

    #[test]
    fn an_empty_result_sends_no_chunk() {
        assert!(stream("select 1 where false", vec![], 10, false).is_empty());
    }

    #[test]
    fn a_closed_stream_stops_before_its_end() {
        assert!(stream(SERIES, vec![], 1, true).is_empty());
    }

    #[test]
    fn an_error_is_sent_and_ends_the_stream() {
        let chunks = stream("select * from missing_table", vec![], 10, false);
        assert_eq!(chunks.len(), 1);
        assert!(chunks[0].as_ref().unwrap_err().contains("missing_table"));
    }
}
