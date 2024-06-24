use sqlx::postgres::{PgPool, PgPoolOptions, PgRow, PgValueFormat, PgValueRef};
use sqlx::{Column, Executor, Postgres, Transaction};
use sqlx::{Row, TypeInfo, ValueRef};
use std::ffi::{c_long, c_void};
use std::ptr::null_mut;
use std::sync::RwLock;
use std::{
    ffi::{c_char, c_int, CStr, CString},
    sync::OnceLock,
};
use tokio::runtime::Runtime;

pub const OK: c_int = 0;
pub const INVALID_STRING_CONVERSION: c_int = 100;
pub type ResultCode<T> = Result<T, c_int>;

static mut SQLX4K: OnceLock<Sqlx4k> = OnceLock::new();
static RUNTIME: OnceLock<Runtime> = OnceLock::new();

#[derive(Debug)]
struct Sqlx4k<'a> {
    pool: PgPool,
    tx_id: RwLock<Vec<u8>>,
    tx: &'a mut [*mut Transaction<'a, Postgres>],
}

impl<'a> Sqlx4k<'a> {
    async fn query(&self, sql: &str) {
        let _result = self.pool.fetch_optional(sql).await.unwrap();
    }

    async fn fetch_all(&self, sql: &str) -> Sqlx4kQueryResult {
        let result: Result<Vec<PgRow>, sqlx::Error> = self.pool.fetch_all(sql).await;
        let result = sqlx4k_result_of(result);
        result
    }

    async fn tx_begin(&mut self) -> i64 {
        let tx = self.pool.begin().await.unwrap();
        let id = { self.tx_id.write().unwrap().pop().unwrap() } as usize;
        if self.tx[id] != null_mut() {
            panic!("Encountered dublicate tx, id={:?}.", id);
        }
        let tx = Box::new(tx);
        let tx = Box::leak(tx);
        self.tx[id] = tx;
        id as i64
    }

    async fn tx_commit(&mut self, tx: i64) {
        let id = tx as usize;
        let tx = self.tx[id];
        if tx == null_mut() {
            panic!("Attempted to commit null tx, id={}.", id);
        }
        let tx = unsafe { *Box::from_raw(tx) };
        self.tx[id] = null_mut();
        tx.commit().await.unwrap();
        self.tx_id.write().unwrap().push(id as u8)
    }

    async fn tx_rollback(&mut self, tx: i64) {
        let id = tx as usize;
        let tx = self.tx[id];
        if tx == null_mut() {
            panic!("Attempted to rollback null tx, id={}.", id);
        }
        let tx = unsafe { *Box::from_raw(tx) };
        self.tx[id] = null_mut();
        tx.rollback().await.unwrap();
        self.tx_id.write().unwrap().push(id as u8)
    }

    async fn tx_query(&mut self, tx: i64, sql: &str) {
        let id = tx as usize;
        let tx = self.tx[id];
        if tx == null_mut() {
            panic!("Attempted to query null tx, id={}.", id);
        }
        let mut tx = unsafe { *Box::from_raw(tx) };
        tx.fetch_optional(sql).await.unwrap();
        let tx = Box::new(tx);
        let tx = Box::leak(tx);
        self.tx[id] = tx;
    }

    async fn tx_fetch_all(&mut self, tx: i64, sql: &str) -> Sqlx4kQueryResult {
        let id = tx as usize;
        let tx = self.tx[id];
        if tx == null_mut() {
            panic!("Attempted to query null tx, id={}.", id);
        }
        let mut tx = unsafe { *Box::from_raw(tx) };
        let result = tx.fetch_all(sql).await;
        let tx = Box::new(tx);
        let tx = Box::leak(tx);
        self.tx[id] = tx;
        sqlx4k_result_of(result)
    }
}

#[repr(C)]
pub struct Sqlx4kResult {
    pub error: c_int,
    pub error_message: *mut c_char,
}

impl Default for Sqlx4kResult {
    fn default() -> Self {
        Self {
            error: OK,
            error_message: null_mut(),
        }
    }
}

#[repr(C)]
pub struct Sqlx4kQueryResult {
    pub error: c_int,
    pub error_message: *mut c_char,
    pub size: c_int,
    pub rows: *mut Sqlx4kRow,
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
    pub kind: c_int,
    pub size: c_int,
    pub value: *mut c_void,
}

#[no_mangle]
pub extern "C" fn sqlx4k_hello(msg: *mut c_char) -> *mut Sqlx4kResult {
    println!("Rust: {}.", c_chars_to_str(msg).unwrap());
    ok()
}

#[no_mangle]
pub extern "C" fn sqlx4k_of(
    host: *const c_char,
    port: *const c_int,
    username: *const c_char,
    password: *const c_char,
    database: *const c_char,
    max_connections: *const c_int,
) -> *mut Sqlx4kResult {
    let host = c_chars_to_str(host).unwrap();
    let port = c_int_to_i32(port).unwrap();
    let username = c_chars_to_str(username).unwrap();
    let password = c_chars_to_str(password).unwrap();
    let database = c_chars_to_str(database).unwrap();
    let max_connections = c_int_to_i32(max_connections).unwrap();

    let url = format!(
        "postgres://{}:{}@{}:{:?}/{}",
        username, password, host, port, database
    );

    // Create the tokio runtime.
    let runtime = Runtime::new().unwrap();

    // Create the db pool options.
    let pool = PgPoolOptions::new()
        .max_connections(max_connections as u32)
        .connect(&url);

    // Create the pool here.
    let pool: PgPool = runtime.block_on(pool).unwrap();
    // Create the transaction holder here.
    let tx_id: RwLock<Vec<u8>> = RwLock::new((0..=max_connections as u8 - 1).collect());
    let mut tx: Vec<*mut Transaction<Postgres>> = (0..=max_connections as u8 - 1)
        .map(|_| null_mut())
        .collect();

    tx.shrink_to_fit();
    let tx = Box::leak(tx.into_boxed_slice());
    let sqlx4k = Sqlx4k { pool, tx_id, tx };

    RUNTIME.set(runtime).unwrap();
    unsafe { SQLX4K.set(sqlx4k).unwrap() };

    ok()
}

#[no_mangle]
pub extern "C" fn sqlx4k_query(sql: *const c_char) -> *mut Sqlx4kResult {
    let sql = c_chars_to_str(sql).unwrap();
    let runtime = RUNTIME.get().unwrap();
    let sqlx4k = unsafe { SQLX4K.get().unwrap() };
    runtime.block_on(sqlx4k.query(sql));
    ok()
}

#[no_mangle]
pub extern "C" fn sqlx4k_fetch_all(sql: *const c_char) -> *mut Sqlx4kQueryResult {
    let sql = c_chars_to_str(sql).unwrap();
    let runtime = RUNTIME.get().unwrap();
    let sqlx4k = unsafe { SQLX4K.get().unwrap() };
    let result = runtime.block_on(sqlx4k.fetch_all(sql));
    let result = Box::new(result);
    let result = Box::leak(result);
    result
}

#[no_mangle]
pub extern "C" fn sqlx4k_tx_begin() -> c_long {
    let runtime = RUNTIME.get().unwrap();
    let sqlx4k = unsafe { SQLX4K.get_mut().unwrap() };
    runtime.block_on(sqlx4k.tx_begin()).into()
}

#[no_mangle]
pub extern "C" fn sqlx4k_tx_commit(tx: c_long) {
    let runtime = RUNTIME.get().unwrap();
    let sqlx4k = unsafe { SQLX4K.get_mut().unwrap() };
    runtime.block_on(sqlx4k.tx_commit(tx));
}

#[no_mangle]
pub extern "C" fn sqlx4k_tx_rollback(tx: c_long) {
    let runtime = RUNTIME.get().unwrap();
    let sqlx4k = unsafe { SQLX4K.get_mut().unwrap() };
    runtime.block_on(sqlx4k.tx_rollback(tx));
}

#[no_mangle]
pub extern "C" fn sqlx4k_tx_query(tx: c_long, sql: *const c_char) -> *mut Sqlx4kResult {
    let sql = c_chars_to_str(sql).unwrap();
    let runtime = RUNTIME.get().unwrap();
    let sqlx4k = unsafe { SQLX4K.get_mut().unwrap() };
    runtime.block_on(sqlx4k.tx_query(tx, sql));
    ok()
}

#[no_mangle]
pub extern "C" fn sqlx4k_tx_fetch_all(tx: c_long, sql: *const c_char) -> *mut Sqlx4kQueryResult {
    let sql = c_chars_to_str(sql).unwrap();
    let runtime = RUNTIME.get().unwrap();
    let sqlx4k = unsafe { SQLX4K.get_mut().unwrap() };
    let result = runtime.block_on(sqlx4k.tx_fetch_all(tx, sql));
    let result = Box::new(result);
    let result = Box::leak(result);
    result
}

#[no_mangle]
pub extern "C" fn sqlx4k_free_result(ptr: *mut Sqlx4kResult) {
    let ptr: Sqlx4kResult = unsafe { *Box::from_raw(ptr) };

    if ptr.error > 0 {
        let error_message = unsafe { CString::from_raw(ptr.error_message) };
        std::mem::drop(error_message);
    }
}

#[no_mangle]
pub extern "C" fn sqlx4k_free_query_result(ptr: *mut Sqlx4kQueryResult) {
    let ptr: Sqlx4kQueryResult = unsafe { *Box::from_raw(ptr) };

    if ptr.error > 0 {
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
            let value =
                unsafe { Vec::from_raw_parts(col.value, col.size as usize, col.size as usize) };
            std::mem::drop(value);
        }
    }
}

fn sqlx4k_result_of(result: Result<Vec<PgRow>, sqlx::Error>) -> Sqlx4kQueryResult {
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

            // https://play.rust-lang.org/?version=stable&mode=debug&edition=2018&gist=d0e44ce1f765ce89523ef89ccd864e54
            // https://stackoverflow.com/questions/57616229/returning-array-from-rust-to-ffi
            // https://stackoverflow.com/questions/76706784/why-stdmemforget-cannot-be-used-for-creating-static-references
            Sqlx4kQueryResult {
                error: 0,
                error_message: null_mut(),
                size: size as c_int,
                rows,
            }
        }
        Err(err) => Sqlx4kQueryResult {
            error: 1,
            error_message: {
                let message = match err {
                    sqlx::Error::PoolTimedOut => "PoolTimedOut".to_string(),
                    sqlx::Error::PoolClosed => "PoolClosed".to_string(),
                    sqlx::Error::WorkerCrashed => "WorkerCrashed".to_string(),
                    sqlx::Error::Database(e) => match e.code() {
                        Some(code) => format!("[{}] {}", code, e.to_string()),
                        None => format!("{}", e.to_string()),
                    },
                    _ => "Unknown error.".to_string(),
                };
                CString::new(message).unwrap().into_raw()
            },
            size: 0,
            rows: null_mut(),
        },
    }
}

fn sqlx4k_row_of(row: &PgRow) -> Sqlx4kRow {
    let columns = row.columns();
    if columns.is_empty() {
        Sqlx4kRow::default()
    } else {
        let mut columns: Vec<Sqlx4kColumn> = row
            .columns()
            .iter()
            .map(|c| {
                let v: &PgValueRef = &row.try_get_raw(c.ordinal()).unwrap();
                let (kind, size, value) = sqlx4k_value_of(v);
                Sqlx4kColumn {
                    ordinal: c.ordinal() as c_int,
                    name: CString::new(c.name()).unwrap().into_raw(),
                    kind,
                    size: size as c_int,
                    value,
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

fn sqlx4k_value_of(value: &PgValueRef) -> (i32, usize, *mut c_void) {
    let info: std::borrow::Cow<sqlx::postgres::PgTypeInfo> = value.type_info();
    let kind: i32 = match info.name() {
        "BOOL" => 1,
        "INT2" | "INT4" => 2,
        "INT8" => 3,
        "FLOAT4" => 4,
        "FLOAT8" => 5,
        "NUMERIC" => 6,
        "CHAR" | "VARCHAR" | "TEXT" => 7,
        "TIMESTAMP" => 8,
        "TIMESTAMPTZ" => 9,
        "DATE" => 10,
        "TIME" => 11,
        "BYTEA" => 12,
        "UUID" => 13,
        "JSON" | "JSONB" => 14,
        _ => panic!("Could not map value of type {}.", info.name()),
    };

    let bytes: &[u8] = match value.format() {
        PgValueFormat::Text => value.as_str().unwrap().as_bytes(),
        PgValueFormat::Binary => todo!("Is not implemented yet."),
        // PgValueFormat::Binary => value.as_bytes().unwrap(),
    };

    let size: usize = bytes.len();
    // TODO: clone under the hood here.
    // Find a way to keep to "leak" data from the sqlx lib.
    let bytes: Vec<u8> = bytes.iter().cloned().collect();
    let bytes: Box<[u8]> = bytes.into_boxed_slice();
    let bytes: &mut [u8] = Box::leak(bytes);
    let bytes: *mut u8 = bytes.as_mut_ptr();
    let value: *mut c_void = bytes as *mut c_void;
    (kind, size, value)
}

fn ok() -> *mut Sqlx4kResult {
    let ok = Sqlx4kResult::default();
    let ok = Box::new(ok);
    let ok = Box::leak(ok);
    ok
}

fn c_chars_to_str<'a>(c_chars: *const c_char) -> ResultCode<&'a str> {
    let c_str = unsafe { CStr::from_ptr(c_chars) };
    let str = c_str.to_str().map_err(|_| INVALID_STRING_CONVERSION)?;
    Ok(str)
}

fn c_int_to_i32(c_int: *const c_int) -> ResultCode<i32> {
    unsafe { Ok(*c_int) }
}
