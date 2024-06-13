use sqlx::postgres::{PgPool, PgPoolOptions, PgRow, PgValueFormat, PgValueRef};
use sqlx::{Column, Executor};
use sqlx::{Row, TypeInfo, ValueRef};
use std::ffi::c_void;
use std::ptr::null_mut;
use std::{
    ffi::{c_char, c_int, c_short, CStr, CString},
    sync::OnceLock,
};
use tokio::runtime::Runtime;

pub const OK: c_int = 0;
pub const INVALID_TYPE_CONVERSION: c_int = 1;
pub type ResultCode<T> = Result<T, c_int>;

static SQLX4K: OnceLock<Sqlx4k> = OnceLock::new();

#[derive(Debug)]
pub struct Sqlx4k {
    runtime: Runtime,
    pool: PgPool,
}

#[repr(C)]
pub struct Sqlx4kPgResult {
    pub error: c_short,
    pub error_message: *mut c_char,
    pub size: c_int,
    pub rows: *mut Sqlx4kPgRow,
}

#[repr(C)]
pub struct Sqlx4kPgRow {
    pub size: c_int,
    pub columns: *mut Sqlx4kPgColumn,
}

impl Default for Sqlx4kPgRow {
    fn default() -> Self {
        Self {
            size: 0,
            columns: null_mut(),
        }
    }
}

#[repr(C)]
pub struct Sqlx4kPgColumn {
    pub ordinal: c_int,
    pub name: *mut c_char,
    pub kind: c_int,
    pub size: c_int,
    pub value: *mut c_void,
}

#[no_mangle]
pub extern "C" fn sqlx4k_hello(msg: *mut c_char) -> c_int {
    println!("Rust: {}.", c_chars_to_str(msg).unwrap());
    OK
}

#[no_mangle]
pub extern "C" fn sqlx4k_of(
    host: *const c_char,
    port: *const c_int,
    username: *const c_char,
    password: *const c_char,
    database: *const c_char,
    max_connections: *const c_int,
) -> c_int {
    let host = c_chars_to_str(host).unwrap();
    let port = c_int_to_i32(port).unwrap();
    let username = c_chars_to_str(username).unwrap();
    let password = c_chars_to_str(password).unwrap();
    let database = c_chars_to_str(database).unwrap();
    let max_connections = c_int_to_i32(max_connections).unwrap();

    let url = format!(
        "postgres://{}:{}@{}:{}/{}",
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
    let sqlx4k = Sqlx4k {
        runtime,
        pool,
        // results,
    };
    SQLX4K.set(sqlx4k).unwrap();
    OK
}

#[no_mangle]
pub extern "C" fn sqlx4k_query(sql: *const c_char) -> c_int {
    let sqlx4k = SQLX4K.get().unwrap();
    let sql = c_chars_to_str(sql).unwrap();
    let query = sqlx::query(sql).fetch_optional(&sqlx4k.pool);
    let _result = sqlx4k.runtime.block_on(query).unwrap();
    OK
}

#[no_mangle]
pub extern "C" fn sqlx4k_free(ptr: *mut Sqlx4kPgResult) {
    let ptr: Sqlx4kPgResult = unsafe { *Box::from_raw(ptr) };

    if ptr.error == 1 {
        let error_message = unsafe { CString::from_raw(ptr.error_message) };
        std::mem::drop(error_message);
    }

    let rows: Vec<Sqlx4kPgRow> =
        unsafe { Vec::from_raw_parts(ptr.rows, ptr.size as usize, ptr.size as usize) };
    for row in rows {
        let columns: Vec<Sqlx4kPgColumn> =
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

#[no_mangle]
pub extern "C" fn sqlx4k_query_fetch_all(sql: *const c_char) -> *mut Sqlx4kPgResult {
    let sqlx4k = SQLX4K.get().unwrap();
    let sql = c_chars_to_str(sql).unwrap();
    let query = sqlx4k.pool.fetch_all(sql);
    let result: Result<Vec<sqlx::postgres::PgRow>, sqlx::Error> = sqlx4k.runtime.block_on(query);
    let result: Sqlx4kPgResult = match result {
        Ok(rows) => {
            let mut rows: Vec<Sqlx4kPgRow> = rows.iter().map(|r| pgrow_to_sqlx4krow(r)).collect();

            // Make sure we're not wasting space.
            rows.shrink_to_fit();
            assert!(rows.len() == rows.capacity());

            let size = rows.len();
            let rows: Box<[Sqlx4kPgRow]> = rows.into_boxed_slice();
            let rows: &mut [Sqlx4kPgRow] = Box::leak(rows);
            let rows: *mut Sqlx4kPgRow = rows.as_mut_ptr();

            // https://play.rust-lang.org/?version=stable&mode=debug&edition=2018&gist=d0e44ce1f765ce89523ef89ccd864e54
            // https://stackoverflow.com/questions/57616229/returning-array-from-rust-to-ffi
            // https://stackoverflow.com/questions/76706784/why-stdmemforget-cannot-be-used-for-creating-static-references
            Sqlx4kPgResult {
                error: 0,
                error_message: null_mut(),
                size: size as c_int,
                rows,
            }
        }
        Err(err) => Sqlx4kPgResult {
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
    };

    let result = Box::new(result);
    let result = Box::leak(result);
    result
}

fn pgrow_to_sqlx4krow(row: &PgRow) -> Sqlx4kPgRow {
    let columns = row.columns();
    if columns.is_empty() {
        Sqlx4kPgRow::default()
    } else {
        let mut columns: Vec<Sqlx4kPgColumn> = row
            .columns()
            .iter()
            .map(|c| {
                let v: &PgValueRef = &row.try_get_raw(c.ordinal()).unwrap();
                let (kind, size, value) = pgvalueref_to_sqlx4kvalue(v);
                Sqlx4kPgColumn {
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
        let columns: Box<[Sqlx4kPgColumn]> = columns.into_boxed_slice();
        let columns: &mut [Sqlx4kPgColumn] = Box::leak(columns);
        let columns: *mut Sqlx4kPgColumn = columns.as_mut_ptr();

        Sqlx4kPgRow {
            size: size as c_int,
            columns,
        }
    }
}

fn pgvalueref_to_sqlx4kvalue(value: &PgValueRef) -> (i32, usize, *mut c_void) {
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

fn c_chars_to_str<'a>(c_chars: *const c_char) -> ResultCode<&'a str> {
    let c_str = unsafe { CStr::from_ptr(c_chars) };
    let str = c_str.to_str().map_err(|_| INVALID_TYPE_CONVERSION)?;
    Ok(str)
}

fn c_int_to_i32(c_int: *const c_int) -> ResultCode<i32> {
    unsafe { Ok(*c_int) }
}
