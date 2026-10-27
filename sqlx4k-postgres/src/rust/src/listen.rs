//! LISTEN / NOTIFY: the listeners Kotlin registers, and the notifications handed to them.

use sqlx::postgres::{PgListener, PgNotification};
use sqlx::Error;
use std::ffi::{c_char, c_int, c_void, CString};
use std::sync::OnceLock;
use std::time::Duration;
use tokio::sync::oneshot;

use crate::client::Sqlx4kPostgreSql;
use crate::errors::sqlx4k_postgres_error_result_of;
use crate::result::c_chars_to_str_postgres;
use crate::types::{
    Sqlx4kPostgresColumn, Sqlx4kPostgresPtr, Sqlx4kPostgresResult, Sqlx4kPostgresRow,
    Sqlx4kPostgresSchema, Sqlx4kPostgresSchemaColumn,
};
use crate::RUNTIME;

#[no_mangle]
pub extern "C" fn sqlx4k_postgresql_listen(
    rt: *mut c_void,
    channels: *const c_char,
    notify_id: c_int,
    notify: extern "C" fn(c_int, *mut Sqlx4kPostgresResult),
    callback: *mut c_void,
    fun: extern "C" fn(Sqlx4kPostgresPtr, *mut Sqlx4kPostgresResult),
) {
    let callback = Sqlx4kPostgresPtr { ptr: callback };
    let channels = c_chars_to_str_postgres(channels).to_owned();
    let runtime = RUNTIME.get().unwrap();
    let sqlx4k = unsafe { &*(rt as *mut Sqlx4kPostgreSql) };
    // Registered before the task starts, so that sqlx4k_postgresql_unlisten finds it as soon as listen returns.
    let (stop_tx, mut stop) = oneshot::channel::<()>();
    let (stopped, stopped_rx) = oneshot::channel::<()>();
    listeners()
        .lock()
        .unwrap()
        .insert(notify_id, (stop_tx, stopped_rx));
    runtime.spawn(async move {
        // A pool of 1 without timeouts (as they don't apply here), only used to handle re-connections.
        let options = sqlx4k
            .pool
            .options()
            .clone()
            .max_connections(1)
            .min_connections(0);
        let listener = async {
            let pool = options.connect_with(sqlx4k.connect_options.clone()).await?;
            let mut listener = PgListener::connect_with(&pool).await?;
            // We don't need to handle close events
            listener.ignore_pool_close_event(true);
            let channels: Vec<&str> = channels.split(',').collect();
            listener.listen_all(channels).await?;
            Ok::<_, Error>((pool, listener))
        }
        .await;
        let (pool, mut listener) = match listener {
            Ok(listener) => listener,
            // Report a failure to start listening (database down, too many clients, ...) instead of aborting.
            Err(err) => return fun(callback, sqlx4k_postgres_error_result_of(err).leak()),
        };

        // Return OK as soon as the stream is ready.
        let result = Sqlx4kPostgresResult::default().leak();
        fun(callback, result);

        // Until stopped by sqlx4k_postgresql_unlisten (a dropped sender stops it too).
        let mut backoff = LISTEN_MIN_BACKOFF;
        loop {
            let stop_now = tokio::select! {
                _ = &mut stop => true,
                received = listener.try_recv() => {
                    match received {
                        Ok(Some(item)) => {
                            backoff = LISTEN_MIN_BACKOFF;
                            let result = sqlx4k_postgresql_result_of_pg_notification(item).leak();
                            notify(notify_id, result);
                            false
                        }
                        // The connection was lost: the next call reconnects and listens again.
                        Ok(None) => false,
                        // The connection failed or could not be re-established (server restart, terminated backend,
                        // database down): retry with a backoff instead of aborting the process.
                        Err(_) => {
                            let stop_now = tokio::select! {
                                _ = &mut stop => true,
                                _ = tokio::time::sleep(backoff) => false,
                            };
                            backoff = (backoff * 2).min(LISTEN_MAX_BACKOFF);
                            stop_now
                        }
                    }
                }
            };
            if stop_now {
                break;
            }
        }

        // Stopped: release the listener's connection.
        drop(listener);
        pool.close().await;
        let _ = stopped.send(());
    });
}

/// The listeners started by sqlx4k_postgresql_listen, by notify id: the sender that stops one, and the receiver
/// notified once it has stopped and closed its connection.
pub(crate) type ListenerControl = (oneshot::Sender<()>, oneshot::Receiver<()>);

pub(crate) fn listeners(
) -> &'static std::sync::Mutex<std::collections::HashMap<c_int, ListenerControl>> {
    static LISTENERS: OnceLock<
        std::sync::Mutex<std::collections::HashMap<c_int, ListenerControl>>,
    > = OnceLock::new();
    LISTENERS.get_or_init(Default::default)
}

/// Stops the listener started with `notify_id` and closes its connection; completes once it has (and at once for an
/// unknown id).
#[no_mangle]
pub extern "C" fn sqlx4k_postgresql_unlisten(
    notify_id: c_int,
    callback: *mut c_void,
    fun: extern "C" fn(Sqlx4kPostgresPtr, *mut Sqlx4kPostgresResult),
) {
    let callback = Sqlx4kPostgresPtr { ptr: callback };
    let runtime = RUNTIME.get().unwrap();
    let control = listeners().lock().unwrap().remove(&notify_id);
    runtime.spawn(async move {
        if let Some((stop, stopped)) = control {
            let _ = stop.send(());
            // An error means the task already ended (it failed to start listening).
            let _ = stopped.await;
        }
        fun(callback, Sqlx4kPostgresResult::default().leak())
    });
}

/// Backoff between LISTEN reconnect attempts.
pub(crate) const LISTEN_MIN_BACKOFF: Duration = Duration::from_millis(100);

pub(crate) const LISTEN_MAX_BACKOFF: Duration = Duration::from_secs(30);

pub(crate) fn sqlx4k_postgresql_result_of_pg_notification(
    item: PgNotification,
) -> Sqlx4kPostgresResult {
    let column = Sqlx4kPostgresSchemaColumn {
        ordinal: 0,
        name: CString::new(item.channel()).unwrap().into_raw(),
        kind: CString::new("TEXT").unwrap().into_raw(),
    };
    let columns = vec![column];
    let columns: Box<[Sqlx4kPostgresSchemaColumn]> = columns.into_boxed_slice();
    let columns: &mut [Sqlx4kPostgresSchemaColumn] = Box::leak(columns);
    let columns: *mut Sqlx4kPostgresSchemaColumn = columns.as_mut_ptr();
    let schema = Sqlx4kPostgresSchema { size: 1, columns };
    let schema = Box::new(schema);
    let schema = Box::leak(schema);

    let column = Sqlx4kPostgresColumn {
        ordinal: 0,
        value: CString::new(item.payload()).unwrap().into_raw(),
    };

    let columns = vec![column];
    let columns: Box<[Sqlx4kPostgresColumn]> = columns.into_boxed_slice();
    let columns: &mut [Sqlx4kPostgresColumn] = Box::leak(columns);
    let columns: *mut Sqlx4kPostgresColumn = columns.as_mut_ptr();

    let row = Sqlx4kPostgresRow { size: 1, columns };
    let rows = vec![row];
    let rows: Box<[Sqlx4kPostgresRow]> = rows.into_boxed_slice();
    let rows: &mut [Sqlx4kPostgresRow] = Box::leak(rows);
    let rows: *mut Sqlx4kPostgresRow = rows.as_mut_ptr();

    Sqlx4kPostgresResult {
        schema,
        size: 1,
        rows,
        ..Default::default()
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::ffi::pool::sqlx4k_postgresql_query;
    use crate::test_support::{c, call, unique, Pool, Res};
    use std::collections::HashMap;
    use std::sync::atomic::{AtomicI32, Ordering};
    use std::sync::{mpsc, Mutex};

    /// Where the notifications of each listener (by notify id) go.
    fn receivers() -> &'static Mutex<HashMap<c_int, mpsc::Sender<usize>>> {
        static RECEIVERS: OnceLock<Mutex<HashMap<c_int, mpsc::Sender<usize>>>> = OnceLock::new();
        RECEIVERS.get_or_init(Default::default)
    }

    extern "C" fn notify(id: c_int, result: *mut Sqlx4kPostgresResult) {
        match receivers().lock().unwrap().get(&id) {
            Some(sender) => sender.send(result as usize).unwrap(),
            None => drop(Res(result)),
        }
    }

    /// Listens to `channels` (comma-separated): the listener's notify id, and where its notifications arrive.
    fn listen(pool: &Pool, channels: &str) -> (c_int, mpsc::Receiver<usize>) {
        static NEXT: AtomicI32 = AtomicI32::new(1_000);
        let id = NEXT.fetch_add(1, Ordering::Relaxed);
        let (sender, receiver) = mpsc::channel();
        receivers().lock().unwrap().insert(id, sender);
        let channels = c(channels);
        call(|cb, fun| sqlx4k_postgresql_listen(pool.rt, channels.as_ptr(), id, notify, cb, fun))
            .ok();
        (id, receiver)
    }

    fn notify_on(pool: &Pool, channel: &str, payload: &str) {
        let sql = c(&format!("select pg_notify('{channel}', '{payload}')"));
        call(|cb, fun| sqlx4k_postgresql_query(pool.rt, sql.as_ptr(), cb, fun)).ok();
    }

    /// The channel and payload of the next notification.
    fn next(receiver: &mpsc::Receiver<usize>) -> (String, String) {
        let r = Res(receiver
            .recv_timeout(Duration::from_secs(10))
            .expect("no notification") as *mut Sqlx4kPostgresResult);
        let (channel, kind) = r.columns().remove(0);
        assert_eq!(kind, "TEXT");
        (channel, r.value().unwrap())
    }

    #[test]
    #[ignore = "needs the PostgreSQL of docker-compose.yml"]
    fn notifications_of_the_channels_listened_to_are_delivered_in_order() {
        let pool = Pool::open(2);
        let (a, b, other) = (unique("ch_a"), unique("ch_b"), unique("ch_other"));
        let (id, receiver) = listen(&pool, &format!("{a},{b}"));
        notify_on(&pool, &other, "ignored");
        notify_on(&pool, &a, "first");
        notify_on(&pool, &b, "second");
        assert_eq!(next(&receiver), (a.clone(), "first".to_string()));
        assert_eq!(next(&receiver), (b.clone(), "second".to_string()));
        call(|cb, fun| sqlx4k_postgresql_unlisten(id, cb, fun)).ok();
        receivers().lock().unwrap().remove(&id);
    }

    #[test]
    #[ignore = "needs the PostgreSQL of docker-compose.yml"]
    fn a_listener_stopped_receives_nothing_more() {
        let pool = Pool::open(2);
        let channel = unique("ch_stop");
        let (id, receiver) = listen(&pool, &channel);
        notify_on(&pool, &channel, "before");
        assert_eq!(next(&receiver).1, "before");
        call(|cb, fun| sqlx4k_postgresql_unlisten(id, cb, fun)).ok();
        assert!(!listeners().lock().unwrap().contains_key(&id));
        notify_on(&pool, &channel, "after");
        assert!(receiver.recv_timeout(Duration::from_millis(500)).is_err());
        receivers().lock().unwrap().remove(&id);
    }

    #[test]
    #[ignore = "needs the PostgreSQL of docker-compose.yml"]
    fn stopping_an_unknown_listener_does_nothing() {
        let _pool = Pool::open(1);
        call(|cb, fun| sqlx4k_postgresql_unlisten(-42, cb, fun)).ok();
    }
}
