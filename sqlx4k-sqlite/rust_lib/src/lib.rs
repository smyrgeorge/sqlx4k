use sqlx::sqlite::{SqlitePool, SqlitePoolOptions, SqliteRow, SqliteTypeInfo, SqliteValueRef};
use sqlx::{Column, Error, Executor, Row, Sqlite, Transaction, TypeInfo, ValueRef};
use std::{
    ffi::{c_char, c_int, c_void, CStr, CString},
    ptr::null_mut,
    sync::OnceLock,
};
use tokio::runtime::Runtime;

pub const OK: c_int = -1;
pub const ERROR_DATABASE: c_int = 0;
pub const ERROR_POOL_TIMED_OUT: c_int = 1;
pub const ERROR_POOL_CLOSED: c_int = 2;
pub const ERROR_WORKER_CRASHED: c_int = 3;

static RUNTIME: OnceLock<Runtime> = OnceLock::new();
static mut SQLX4K: OnceLock<Sqlx4k> = OnceLock::new();

#[repr(C)]
pub struct Sqlx4kResult {
    pub error: c_int,
    pub error_message: *mut c_char,
    pub tx: *mut c_void,
    pub size: c_int,
    pub rows: *mut Sqlx4kRow,
}

impl Sqlx4kResult {
    fn leak(self) -> *mut Sqlx4kResult {
        let result = Box::new(self);
        let result = Box::leak(result);
        result
    }
}

impl Default for Sqlx4kResult {
    fn default() -> Self {
        Self {
            error: OK,
            error_message: null_mut(),
            tx: null_mut(),
            size: 0,
            rows: null_mut(),
        }
    }
}

#[repr(C)]
pub struct Sqlx4kRow {
    pub size: c_int,
    pub columns: *mut Sqlx4kColumn,
}

impl Default for Sqlx4kRow {
    fn default() -> Self {
        Self {
            size: 0,
            columns: null_mut(),
        }
    }
}

#[repr(C)]
pub struct Sqlx4kColumn {
    pub ordinal: c_int,
    pub name: *mut c_char,
    pub kind: *mut c_char,
    pub value: *mut c_char,
}

#[derive(Debug)]
struct Sqlx4k {
    pool: SqlitePool,
}

impl Sqlx4k {
    async fn query(&self, sql: &str) -> *mut Sqlx4kResult {
        let result = self.pool.fetch_optional(sql).await;
        let result = match result {
            Ok(_) => Sqlx4kResult::default(),
            Err(err) => sqlx4k_error_result_of(err),
        };
        result.leak()
    }

    async fn fetch_all(&self, sql: &str) -> *mut Sqlx4kResult {
        let result = self.pool.fetch_all(sql).await;
        sqlx4k_result_of(result).leak()
    }

    async fn tx_begin(&mut self) -> *mut Sqlx4kResult {
        let tx = self.pool.begin().await;
        let tx = match tx {
            Ok(tx) => tx,
            Err(err) => {
                return sqlx4k_error_result_of(err).leak();
            }
        };

        let tx = Box::new(tx);
        let tx = Box::leak(tx);
        let result = Sqlx4kResult {
            tx: tx as *mut _ as *mut c_void,
            ..Default::default()
        };
        result.leak()
    }

    async fn tx_commit(&mut self, tx: Ptr) -> *mut Sqlx4kResult {
        let tx = unsafe { &mut *(tx.ptr as *mut Transaction<'_, Sqlite>) };
        let tx = unsafe { *Box::from_raw(tx) };
        let result = match tx.commit().await {
            Ok(_) => Sqlx4kResult::default(),
            Err(err) => sqlx4k_error_result_of(err),
        };
        result.leak()
    }

    async fn tx_rollback(&mut self, tx: Ptr) -> *mut Sqlx4kResult {
        let tx = unsafe { &mut *(tx.ptr as *mut Transaction<'_, Sqlite>) };
        let tx = unsafe { *Box::from_raw(tx) };
        let result = match tx.rollback().await {
            Ok(_) => Sqlx4kResult::default(),
            Err(err) => sqlx4k_error_result_of(err),
        };
        result.leak()
    }

    async fn tx_query(&mut self, tx: Ptr, sql: &str) -> *mut Sqlx4kResult {
        let tx = unsafe { &mut *(tx.ptr as *mut Transaction<'_, Sqlite>) };
        let mut tx = unsafe { *Box::from_raw(tx) };
        let result = tx.fetch_optional(sql).await;
        let tx = Box::new(tx);
        let tx = Box::leak(tx);
        let result = match result {
            Ok(_) => Sqlx4kResult::default(),
            Err(err) => sqlx4k_error_result_of(err),
        };
        let result = Sqlx4kResult {
            tx: tx as *mut _ as *mut c_void,
            ..result
        };
        result.leak()
    }

    async fn tx_fetch_all(&mut self, tx: Ptr, sql: &str) -> *mut Sqlx4kResult {
        let tx = unsafe { &mut *(tx.ptr as *mut Transaction<'_, Sqlite>) };
        let mut tx = unsafe { *Box::from_raw(tx) };
        let result = tx.fetch_all(sql).await;
        let tx = Box::new(tx);
        let tx = Box::leak(tx);
        let result = sqlx4k_result_of(result);
        let result = Sqlx4kResult {
            tx: tx as *mut _ as *mut c_void,
            ..result
        };
        result.leak()
    }
}

#[no_mangle]
pub extern "C" fn sqlx4k_of(database: *const c_char, max_connections: c_int) -> *mut Sqlx4kResult {
    let database = unsafe { c_chars_to_str(database) };

    let url = format!("sqlite:{}", database);

    // Create the tokio runtime.
    let runtime = Runtime::new().unwrap();

    // Create the db pool options.
    let pool = SqlitePoolOptions::new()
        .max_connections(max_connections as u32)
        .connect(&url);

    // Create the pool here.
    let pool: SqlitePool = runtime.block_on(pool).unwrap();
    let sqlx4k = Sqlx4k { pool };

    RUNTIME.set(runtime).unwrap();
    unsafe { SQLX4K.set(sqlx4k).unwrap() };

    Sqlx4kResult::default().leak()
}

#[no_mangle]
pub extern "C" fn sqlx4k_pool_size() -> c_int {
    unsafe { SQLX4K.get().unwrap() }.pool.size() as c_int
}

#[no_mangle]
pub extern "C" fn sqlx4k_pool_idle_size() -> c_int {
    unsafe { SQLX4K.get().unwrap() }.pool.num_idle() as c_int
}

#[repr(C)]
pub struct Ptr {
    ptr: *mut c_void,
}
unsafe impl Send for Ptr {}
unsafe impl Sync for Ptr {}

#[no_mangle]
pub extern "C" fn sqlx4k_query(
    sql: *const c_char,
    callback: *mut c_void,
    fun: unsafe extern "C" fn(Ptr, *mut Sqlx4kResult),
) {
    let callback = Ptr { ptr: callback };
    let sql = unsafe { c_chars_to_str(sql).to_owned() };
    let runtime = RUNTIME.get().unwrap();
    let sqlx4k = unsafe { SQLX4K.get().unwrap() };
    runtime.spawn(async move {
        let result = sqlx4k.query(&sql).await;
        unsafe { fun(callback, result) }
    });
}

#[no_mangle]
pub extern "C" fn sqlx4k_fetch_all(
    sql: *const c_char,
    callback: *mut c_void,
    fun: unsafe extern "C" fn(Ptr, *mut Sqlx4kResult),
) {
    let callback = Ptr { ptr: callback };
    let sql = unsafe { c_chars_to_str(sql).to_owned() };
    let runtime = RUNTIME.get().unwrap();
    let sqlx4k = unsafe { SQLX4K.get().unwrap() };
    runtime.spawn(async move {
        let result = sqlx4k.fetch_all(&sql).await;
        unsafe { fun(callback, result) }
    });
}

#[no_mangle]
pub extern "C" fn sqlx4k_tx_begin(
    callback: *mut c_void,
    fun: unsafe extern "C" fn(Ptr, *mut Sqlx4kResult),
) {
    let callback = Ptr { ptr: callback };
    let runtime = RUNTIME.get().unwrap();
    let sqlx4k = unsafe { SQLX4K.get_mut().unwrap() };
    runtime.spawn(async move {
        let result = sqlx4k.tx_begin().await;
        unsafe { fun(callback, result) }
    });
}

#[no_mangle]
pub extern "C" fn sqlx4k_tx_commit(
    tx: *mut c_void,
    callback: *mut c_void,
    fun: unsafe extern "C" fn(Ptr, *mut Sqlx4kResult),
) {
    let tx = Ptr { ptr: tx };
    let callback = Ptr { ptr: callback };
    let runtime = RUNTIME.get().unwrap();
    let sqlx4k = unsafe { SQLX4K.get_mut().unwrap() };
    runtime.spawn(async move {
        let result = sqlx4k.tx_commit(tx).await;
        unsafe { fun(callback, result) }
    });
}

#[no_mangle]
pub extern "C" fn sqlx4k_tx_rollback(
    tx: *mut c_void,
    callback: *mut c_void,
    fun: unsafe extern "C" fn(Ptr, *mut Sqlx4kResult),
) {
    let tx = Ptr { ptr: tx };
    let callback = Ptr { ptr: callback };
    let runtime = RUNTIME.get().unwrap();
    let sqlx4k = unsafe { SQLX4K.get_mut().unwrap() };
    runtime.spawn(async move {
        let result = sqlx4k.tx_rollback(tx).await;
        unsafe { fun(callback, result) }
    });
}

#[no_mangle]
pub extern "C" fn sqlx4k_tx_query(
    tx: *mut c_void,
    sql: *const c_char,
    callback: *mut c_void,
    fun: unsafe extern "C" fn(Ptr, *mut Sqlx4kResult),
) {
    let tx = Ptr { ptr: tx };
    let callback = Ptr { ptr: callback };
    let sql = unsafe { c_chars_to_str(sql).to_owned() };
    let runtime = RUNTIME.get().unwrap();
    let sqlx4k = unsafe { SQLX4K.get_mut().unwrap() };
    runtime.spawn(async move {
        let result = sqlx4k.tx_query(tx, &sql).await;
        unsafe { fun(callback, result) }
    });
}

#[no_mangle]
pub extern "C" fn sqlx4k_tx_fetch_all(
    tx: *mut c_void,
    sql: *const c_char,
    callback: *mut c_void,
    fun: unsafe extern "C" fn(Ptr, *mut Sqlx4kResult),
) {
    let tx = Ptr { ptr: tx };
    let callback = Ptr { ptr: callback };
    let sql = unsafe { c_chars_to_str(sql).to_owned() };
    let runtime = RUNTIME.get().unwrap();
    let sqlx4k = unsafe { SQLX4K.get_mut().unwrap() };
    runtime.spawn(async move {
        let result = sqlx4k.tx_fetch_all(tx, &sql).await;
        unsafe { fun(callback, result) }
    });
}

#[no_mangle]
pub extern "C" fn sqlx4k_free_result(ptr: *mut Sqlx4kResult) {
    let ptr: Sqlx4kResult = unsafe { *Box::from_raw(ptr) };

    if ptr.error >= 0 {
        let error_message = unsafe { CString::from_raw(ptr.error_message) };
        std::mem::drop(error_message);
    }

    if ptr.rows == null_mut() {
        return;
    }

    let rows: Vec<Sqlx4kRow> =
        unsafe { Vec::from_raw_parts(ptr.rows, ptr.size as usize, ptr.size as usize) };
    for row in rows {
        let columns: Vec<Sqlx4kColumn> =
            unsafe { Vec::from_raw_parts(row.columns, row.size as usize, row.size as usize) };
        for col in columns {
            let name = unsafe { CString::from_raw(col.name) };
            std::mem::drop(name);
            let kind = unsafe { CString::from_raw(col.kind) };
            std::mem::drop(kind);
            let value = unsafe { CString::from_raw(col.value) };
            std::mem::drop(value);
        }
    }
}

fn sqlx4k_result_of(result: Result<Vec<SqliteRow>, sqlx::Error>) -> Sqlx4kResult {
    match result {
        Ok(rows) => {
            let mut rows: Vec<Sqlx4kRow> = rows.iter().map(|r| sqlx4k_row_of(r)).collect();

            // Make sure we're not wasting space.
            rows.shrink_to_fit();
            assert!(rows.len() == rows.capacity());

            let size = rows.len();
            let rows: Box<[Sqlx4kRow]> = rows.into_boxed_slice();
            let rows: &mut [Sqlx4kRow] = Box::leak(rows);
            let rows: *mut Sqlx4kRow = rows.as_mut_ptr();

            Sqlx4kResult {
                size: size as c_int,
                rows,
                ..Default::default()
            }
        }
        Err(err) => sqlx4k_error_result_of(err),
    }
}

fn sqlx4k_error_result_of(err: sqlx::Error) -> Sqlx4kResult {
    let (code, message) = match err {
        Error::Configuration(_) => panic!("Unexpected error occurred."),
        Error::Database(e) => match e.code() {
            Some(code) => (ERROR_DATABASE, format!("[{}] {}", code, e.to_string())),
            None => (ERROR_DATABASE, format!("{}", e.to_string())),
        },
        Error::Io(_) => panic!("Io :: Unexpected error occurred."),
        Error::Tls(_) => panic!("Tls :: Unexpected error occurred."),
        Error::Protocol(_) => panic!("Protocol :: Unexpected error occurred."),
        Error::RowNotFound => panic!("RowNotFound :: Unexpected error occurred."),
        Error::TypeNotFound { type_name: _ } => {
            panic!("TypeNotFound :: Unexpected error occurred.")
        }
        Error::ColumnIndexOutOfBounds { index: _, len: _ } => {
            panic!("ColumnIndexOutOfBounds :: Unexpected error occurred.")
        }
        Error::ColumnNotFound(_) => panic!("ColumnNotFound :: Unexpected error occurred."),
        Error::ColumnDecode {
            index: _,
            source: _,
        } => {
            panic!("ColumnDecode :: Unexpected error occurred.")
        }
        Error::Decode(_) => panic!("Decode :: Unexpected error occurred."),
        Error::AnyDriverError(_) => panic!("AnyDriverError :: Unexpected error occurred."),
        Error::PoolTimedOut => (ERROR_POOL_TIMED_OUT, "PoolTimedOut".to_string()),
        Error::PoolClosed => (ERROR_POOL_CLOSED, "PoolClosed".to_string()),
        Error::WorkerCrashed => (ERROR_WORKER_CRASHED, "WorkerCrashed".to_string()),
        Error::Migrate(_) => panic!("Migrate :: Unexpected error occurred."),
        _ => panic!("Unexpected error occurred."),
    };

    Sqlx4kResult {
        error: code,
        error_message: CString::new(message).unwrap().into_raw(),
        ..Default::default()
    }
}

fn sqlx4k_row_of(row: &SqliteRow) -> Sqlx4kRow {
    let columns = row.columns();
    if columns.is_empty() {
        Sqlx4kRow::default()
    } else {
        let mut columns: Vec<Sqlx4kColumn> = row
            .columns()
            .iter()
            .map(|c| {
                let name: &str = c.name();
                let value_ref: SqliteValueRef = row.try_get_raw(c.ordinal()).unwrap();
                let info: std::borrow::Cow<SqliteTypeInfo> = value_ref.type_info();
                let kind: &str = info.name();
                let value: &str = row.get_unchecked(c.ordinal());
                Sqlx4kColumn {
                    ordinal: c.ordinal() as c_int,
                    name: CString::new(name).unwrap().into_raw(),
                    kind: CString::new(kind).unwrap().into_raw(),
                    value: CString::new(value).unwrap().into_raw(),
                }
            })
            .collect();

        // Make sure we're not wasting space.
        columns.shrink_to_fit();
        assert!(columns.len() == columns.capacity());

        let size = columns.len();
        let columns: Box<[Sqlx4kColumn]> = columns.into_boxed_slice();
        let columns: &mut [Sqlx4kColumn] = Box::leak(columns);
        let columns: *mut Sqlx4kColumn = columns.as_mut_ptr();

        Sqlx4kRow {
            size: size as c_int,
            columns,
        }
    }
}

unsafe fn c_chars_to_str<'a>(c_chars: *const c_char) -> &'a str {
    CStr::from_ptr(c_chars).to_str().unwrap()
}
