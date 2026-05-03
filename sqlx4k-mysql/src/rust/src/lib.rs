use sqlx::mysql::{
    MySqlConnectOptions, MySqlPool, MySqlPoolOptions, MySqlRow, MySqlTypeInfo, MySqlValueRef,
};
use sqlx::pool::PoolConnection;
// Re-export sqlx's chrono feature shim so we don't need a direct dependency on
// the chrono crate — sqlx already pulls it in when the matching feature is on.
use sqlx::types::chrono;
use sqlx::{Acquire, Column, Error, Executor, MySql, Row, Transaction, TypeInfo, ValueRef};
use std::{
    ffi::{c_char, c_int, c_ulonglong, c_void, CStr, CString},
    ptr::null_mut,
    slice,
    sync::OnceLock,
    time::Duration,
};
use tokio::runtime::Runtime;

// ============================================================================
// Shared constants and types (inlined from sqlx4k)
// ============================================================================

pub const OK: c_int = -1;
pub const ERROR_DATABASE: c_int = 0;
pub const ERROR_POOL_TIMED_OUT: c_int = 1;
pub const ERROR_POOL_CLOSED: c_int = 2;
pub const ERROR_WORKER_CRASHED: c_int = 3;

#[repr(C)]
pub struct Sqlx4kMysqlPtr {
    pub ptr: *mut c_void,
}
unsafe impl Send for Sqlx4kMysqlPtr {}
unsafe impl Sync for Sqlx4kMysqlPtr {}

#[repr(C)]
pub struct Sqlx4kMysqlResult {
    pub error: c_int,
    pub error_message: *mut c_char,
    pub rows_affected: c_ulonglong,
    pub cn: *mut c_void,
    pub tx: *mut c_void,
    pub rt: *mut c_void,
    pub schema: *mut Sqlx4kMysqlSchema,
    pub size: c_int,
    pub rows: *mut Sqlx4kMysqlRow,
}

impl Sqlx4kMysqlResult {
    pub fn leak(self) -> *mut Sqlx4kMysqlResult {
        let result = Box::new(self);
        let result = Box::leak(result);
        result
    }
}

impl Default for Sqlx4kMysqlResult {
    fn default() -> Self {
        Self {
            error: OK,
            error_message: null_mut(),
            rows_affected: 0,
            cn: null_mut(),
            tx: null_mut(),
            rt: null_mut(),
            schema: null_mut(),
            size: 0,
            rows: null_mut(),
        }
    }
}

#[repr(C)]
pub struct Sqlx4kMysqlSchema {
    pub size: c_int,
    pub columns: *mut Sqlx4kMysqlSchemaColumn,
}

impl Default for Sqlx4kMysqlSchema {
    fn default() -> Self {
        Self {
            size: 0,
            columns: null_mut(),
        }
    }
}

#[repr(C)]
pub struct Sqlx4kMysqlSchemaColumn {
    pub ordinal: c_int,
    pub name: *mut c_char,
    pub kind: *mut c_char,
}

#[repr(C)]
pub struct Sqlx4kMysqlRow {
    pub size: c_int,
    pub columns: *mut Sqlx4kMysqlColumn,
}

impl Default for Sqlx4kMysqlRow {
    fn default() -> Self {
        Self {
            size: 0,
            columns: null_mut(),
        }
    }
}

#[repr(C)]
pub struct Sqlx4kMysqlColumn {
    pub ordinal: c_int,
    pub value: *mut c_char,
}

// ----------------------------------------------------------------------------
// Prepared statement parameter binding
// ----------------------------------------------------------------------------

pub const PARAM_NULL: c_int = 0;
pub const PARAM_INT: c_int = 1;
pub const PARAM_REAL: c_int = 2;
pub const PARAM_TEXT: c_int = 3;
pub const PARAM_BLOB: c_int = 4;

/// FFI representation of a single bound parameter. Only the field
/// matching `kind` is read; the rest are ignored.
#[repr(C)]
pub struct Sqlx4kMysqlParam {
    pub kind: c_int,
    pub i64_val: i64,
    pub f64_val: f64,
    pub text: *const c_char,
    pub blob: *const u8,
    pub blob_len: c_int,
}

enum OwnedParam {
    Null,
    Int(i64),
    Real(f64),
    Text(String),
    Blob(Vec<u8>),
}

fn read_params(params: *const Sqlx4kMysqlParam, count: c_int) -> Vec<OwnedParam> {
    if params.is_null() || count <= 0 {
        return Vec::new();
    }
    let slice = unsafe { slice::from_raw_parts(params, count as usize) };
    slice
        .iter()
        .map(|p| match p.kind {
            PARAM_NULL => OwnedParam::Null,
            PARAM_INT => OwnedParam::Int(p.i64_val),
            PARAM_REAL => OwnedParam::Real(p.f64_val),
            PARAM_TEXT => {
                let s = unsafe { CStr::from_ptr(p.text) }
                    .to_str()
                    .expect("Invalid UTF-8 in TEXT parameter")
                    .to_owned();
                OwnedParam::Text(s)
            }
            PARAM_BLOB => {
                let len = p.blob_len.max(0) as usize;
                let bytes = if len == 0 || p.blob.is_null() {
                    Vec::new()
                } else {
                    unsafe { slice::from_raw_parts(p.blob, len) }.to_vec()
                };
                OwnedParam::Blob(bytes)
            }
            other => panic!("Unknown Sqlx4kMysqlParam kind: {}", other),
        })
        .collect()
}

fn bind_params<'q>(
    mut q: sqlx::query::Query<'q, MySql, sqlx::mysql::MySqlArguments>,
    params: Vec<OwnedParam>,
) -> sqlx::query::Query<'q, MySql, sqlx::mysql::MySqlArguments> {
    for p in params {
        q = match p {
            OwnedParam::Null => q.bind(None::<i64>),
            OwnedParam::Int(v) => q.bind(v),
            OwnedParam::Real(v) => q.bind(v),
            OwnedParam::Text(s) => q.bind(s),
            OwnedParam::Blob(b) => q.bind(b),
        };
    }
    q
}

#[no_mangle]
pub extern "C" fn auto_generated_for_struct_mysql_Sqlx4kMysqlPtr(_: Sqlx4kMysqlPtr) {}
#[no_mangle]
pub extern "C" fn auto_generated_for_struct_mysql_Sqlx4kMysqlResult(_: Sqlx4kMysqlResult) {}
#[no_mangle]
pub extern "C" fn auto_generated_for_struct_mysql_Sqlx4kMysqlParam(_: Sqlx4kMysqlParam) {}

#[no_mangle]
pub extern "C" fn sqlx4k_mysql_free_result(ptr: *mut Sqlx4kMysqlResult) {
    let ptr: Sqlx4kMysqlResult = unsafe { *Box::from_raw(ptr) };

    if ptr.error >= 0 {
        let error_message = unsafe { CString::from_raw(ptr.error_message) };
        std::mem::drop(error_message);
    }

    if ptr.schema == null_mut() {
        return;
    }

    let schema: Sqlx4kMysqlSchema = unsafe { *Box::from_raw(ptr.schema) };
    let columns: Vec<Sqlx4kMysqlSchemaColumn> =
        unsafe { Vec::from_raw_parts(schema.columns, schema.size as usize, schema.size as usize) };
    for col in columns {
        let name = unsafe { CString::from_raw(col.name) };
        std::mem::drop(name);
        let kind = unsafe { CString::from_raw(col.kind) };
        std::mem::drop(kind);
    }

    if ptr.rows == null_mut() {
        return;
    }

    let rows: Vec<Sqlx4kMysqlRow> =
        unsafe { Vec::from_raw_parts(ptr.rows, ptr.size as usize, ptr.size as usize) };
    for row in rows {
        let columns: Vec<Sqlx4kMysqlColumn> =
            unsafe { Vec::from_raw_parts(row.columns, row.size as usize, row.size as usize) };
        for col in columns {
            if col.value != null_mut() {
                let value = unsafe { CString::from_raw(col.value) };
                std::mem::drop(value);
            }
        }
    }
}

pub fn sqlx4k_mysql_error_result_of(err: sqlx::Error) -> Sqlx4kMysqlResult {
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
        Error::PoolClosed => (
            ERROR_POOL_CLOSED,
            "The connection pool is already closed".to_string(),
        ),
        Error::WorkerCrashed => (ERROR_WORKER_CRASHED, "WorkerCrashed".to_string()),
        Error::Migrate(_) => panic!("Migrate :: Unexpected error occurred."),
        _ => panic!("Unexpected error occurred."),
    };

    Sqlx4kMysqlResult {
        error: code,
        error_message: CString::new(message).unwrap().into_raw(),
        ..Default::default()
    }
}

pub fn c_chars_to_str_mysql<'a>(c_chars: *const c_char) -> &'a str {
    unsafe { CStr::from_ptr(c_chars).to_str().unwrap() }
}

// ============================================================================
// MySQL-specific implementation
// ============================================================================

static RUNTIME: OnceLock<Runtime> = OnceLock::new();

#[derive(Debug)]
struct Sqlx4kMySql {
    pool: MySqlPool,
}

impl Sqlx4kMySql {
    async fn query(&self, sql: &str) -> *mut Sqlx4kMysqlResult {
        let result = self.pool.execute(sql).await;
        let result = match result {
            Ok(res) => Sqlx4kMysqlResult {
                rows_affected: res.rows_affected(),
                ..Default::default()
            },
            Err(err) => sqlx4k_mysql_error_result_of(err),
        };
        result.leak()
    }

    async fn fetch_all(&self, sql: &str) -> *mut Sqlx4kMysqlResult {
        let result = self.pool.fetch_all(sql).await;
        sqlx4k_mysql_result_of(result).leak()
    }

    async fn cn_acquire(&self) -> *mut Sqlx4kMysqlResult {
        let cn = self.pool.acquire().await;
        let cn: PoolConnection<MySql> = match cn {
            Ok(cn) => cn,
            Err(err) => return sqlx4k_mysql_error_result_of(err).leak(),
        };

        let cn = Box::new(cn);
        let cn = Box::leak(cn);
        let result = Sqlx4kMysqlResult {
            cn: cn as *mut _ as *mut c_void,
            ..Default::default()
        };
        result.leak()
    }

    async fn cn_release(&self, cn: Sqlx4kMysqlPtr) -> *mut Sqlx4kMysqlResult {
        if cn.ptr.is_null() {
            return Sqlx4kMysqlResult::default().leak();
        }

        let cn_ptr = cn.ptr as *mut PoolConnection<MySql>;
        unsafe {
            // Recreate the Box and drop it, returning connection to pool
            let _boxed: Box<PoolConnection<MySql>> = Box::from_raw(cn_ptr);
        }

        Sqlx4kMysqlResult::default().leak()
    }

    async fn cn_query(&self, cn: Sqlx4kMysqlPtr, sql: &str) -> *mut Sqlx4kMysqlResult {
        let cn = unsafe { &mut *(cn.ptr as *mut PoolConnection<MySql>) };
        let result = cn.execute(sql).await;
        let result = match result {
            Ok(res) => Sqlx4kMysqlResult {
                rows_affected: res.rows_affected(),
                ..Default::default()
            },
            Err(err) => sqlx4k_mysql_error_result_of(err),
        };
        result.leak()
    }

    async fn cn_fetch_all(&self, cn: Sqlx4kMysqlPtr, sql: &str) -> *mut Sqlx4kMysqlResult {
        let cn = unsafe { &mut *(cn.ptr as *mut PoolConnection<MySql>) };
        let result = cn.fetch_all(sql).await;
        sqlx4k_mysql_result_of(result).leak()
    }

    async fn cn_tx_begin(&self, cn: Sqlx4kMysqlPtr) -> *mut Sqlx4kMysqlResult {
        let cn = unsafe { &mut *(cn.ptr as *mut PoolConnection<MySql>) };
        let tx = cn.begin().await;
        let tx = match tx {
            Ok(tx) => tx,
            Err(err) => {
                return sqlx4k_mysql_error_result_of(err).leak();
            }
        };

        let tx = Box::new(tx);
        let tx = Box::leak(tx);

        let result = Sqlx4kMysqlResult {
            tx: tx as *mut _ as *mut c_void,
            ..Default::default()
        };
        result.leak()
    }

    async fn tx_begin(&self) -> *mut Sqlx4kMysqlResult {
        let tx = self.pool.begin().await;
        let tx = match tx {
            Ok(tx) => tx,
            Err(err) => {
                return sqlx4k_mysql_error_result_of(err).leak();
            }
        };

        let tx = Box::new(tx);
        let tx = Box::leak(tx);
        let result = Sqlx4kMysqlResult {
            tx: tx as *mut _ as *mut c_void,
            ..Default::default()
        };
        result.leak()
    }

    async fn tx_commit(&self, tx: Sqlx4kMysqlPtr) -> *mut Sqlx4kMysqlResult {
        let tx = unsafe { *Box::from_raw(tx.ptr as *mut Transaction<'_, MySql>) };
        let result = match tx.commit().await {
            Ok(_) => Sqlx4kMysqlResult::default(),
            Err(err) => sqlx4k_mysql_error_result_of(err),
        };
        result.leak()
    }

    async fn tx_rollback(&self, tx: Sqlx4kMysqlPtr) -> *mut Sqlx4kMysqlResult {
        let tx = unsafe { *Box::from_raw(tx.ptr as *mut Transaction<'_, MySql>) };
        let result = match tx.rollback().await {
            Ok(_) => Sqlx4kMysqlResult::default(),
            Err(err) => sqlx4k_mysql_error_result_of(err),
        };
        result.leak()
    }

    async fn tx_query(&self, tx: Sqlx4kMysqlPtr, sql: &str) -> *mut Sqlx4kMysqlResult {
        let mut tx = unsafe { *Box::from_raw(tx.ptr as *mut Transaction<'_, MySql>) };
        let result = tx.execute(sql).await;
        let tx = Box::new(tx);
        let tx = Box::into_raw(tx);
        let result = match result {
            Ok(res) => Sqlx4kMysqlResult {
                rows_affected: res.rows_affected(),
                ..Default::default()
            },
            Err(err) => sqlx4k_mysql_error_result_of(err),
        };
        let result = Sqlx4kMysqlResult {
            tx: tx as *mut c_void,
            ..result
        };
        result.leak()
    }

    async fn tx_fetch_all(&self, tx: Sqlx4kMysqlPtr, sql: &str) -> *mut Sqlx4kMysqlResult {
        let mut tx = unsafe { *Box::from_raw(tx.ptr as *mut Transaction<'_, MySql>) };
        let result = tx.fetch_all(sql).await;
        let tx = Box::new(tx);
        let tx = Box::into_raw(tx);
        let result = sqlx4k_mysql_result_of(result);
        let result = Sqlx4kMysqlResult {
            tx: tx as *mut c_void,
            ..result
        };
        result.leak()
    }

    async fn close(&self) -> *mut Sqlx4kMysqlResult {
        self.pool.close().await;
        Sqlx4kMysqlResult::default().leak()
    }

    async fn query_with_params(
        &self,
        sql: &str,
        params: Vec<OwnedParam>,
    ) -> *mut Sqlx4kMysqlResult {
        let q = bind_params(sqlx::query::<MySql>(sql), params);
        let result = q.execute(&self.pool).await;
        let result = match result {
            Ok(res) => Sqlx4kMysqlResult {
                rows_affected: res.rows_affected(),
                ..Default::default()
            },
            Err(err) => sqlx4k_mysql_error_result_of(err),
        };
        result.leak()
    }

    async fn fetch_all_with_params(
        &self,
        sql: &str,
        params: Vec<OwnedParam>,
    ) -> *mut Sqlx4kMysqlResult {
        let q = bind_params(sqlx::query::<MySql>(sql), params);
        let result = q.fetch_all(&self.pool).await;
        sqlx4k_mysql_result_of(result).leak()
    }

    async fn cn_query_with_params(
        &self,
        cn: Sqlx4kMysqlPtr,
        sql: &str,
        params: Vec<OwnedParam>,
    ) -> *mut Sqlx4kMysqlResult {
        let cn = unsafe { &mut *(cn.ptr as *mut PoolConnection<MySql>) };
        let q = bind_params(sqlx::query::<MySql>(sql), params);
        let result = q.execute(&mut **cn).await;
        let result = match result {
            Ok(res) => Sqlx4kMysqlResult {
                rows_affected: res.rows_affected(),
                ..Default::default()
            },
            Err(err) => sqlx4k_mysql_error_result_of(err),
        };
        result.leak()
    }

    async fn cn_fetch_all_with_params(
        &self,
        cn: Sqlx4kMysqlPtr,
        sql: &str,
        params: Vec<OwnedParam>,
    ) -> *mut Sqlx4kMysqlResult {
        let cn = unsafe { &mut *(cn.ptr as *mut PoolConnection<MySql>) };
        let q = bind_params(sqlx::query::<MySql>(sql), params);
        let result = q.fetch_all(&mut **cn).await;
        sqlx4k_mysql_result_of(result).leak()
    }

    async fn tx_query_with_params(
        &self,
        tx: Sqlx4kMysqlPtr,
        sql: &str,
        params: Vec<OwnedParam>,
    ) -> *mut Sqlx4kMysqlResult {
        let mut tx = unsafe { *Box::from_raw(tx.ptr as *mut Transaction<'_, MySql>) };
        let q = bind_params(sqlx::query::<MySql>(sql), params);
        let result = q.execute(&mut *tx).await;
        let tx = Box::new(tx);
        let tx = Box::into_raw(tx);
        let result = match result {
            Ok(res) => Sqlx4kMysqlResult {
                rows_affected: res.rows_affected(),
                ..Default::default()
            },
            Err(err) => sqlx4k_mysql_error_result_of(err),
        };
        let result = Sqlx4kMysqlResult {
            tx: tx as *mut c_void,
            ..result
        };
        result.leak()
    }

    async fn tx_fetch_all_with_params(
        &self,
        tx: Sqlx4kMysqlPtr,
        sql: &str,
        params: Vec<OwnedParam>,
    ) -> *mut Sqlx4kMysqlResult {
        let mut tx = unsafe { *Box::from_raw(tx.ptr as *mut Transaction<'_, MySql>) };
        let q = bind_params(sqlx::query::<MySql>(sql), params);
        let result = q.fetch_all(&mut *tx).await;
        let tx = Box::new(tx);
        let tx = Box::into_raw(tx);
        let result = sqlx4k_mysql_result_of(result);
        let result = Sqlx4kMysqlResult {
            tx: tx as *mut c_void,
            ..result
        };
        result.leak()
    }
}

#[no_mangle]
pub extern "C" fn sqlx4k_mysql_of(
    url: *const c_char,
    username: *const c_char,
    password: *const c_char,
    min_connections: c_int,
    max_connections: c_int,
    acquire_timeout_milis: c_int,
    idle_timeout_milis: c_int,
    max_lifetime_milis: c_int,
) -> *mut Sqlx4kMysqlResult {
    let url = c_chars_to_str_mysql(url);
    let username = c_chars_to_str_mysql(username);
    let password = c_chars_to_str_mysql(password);
    let options: MySqlConnectOptions = url.parse().unwrap();
    let options = options.username(username).password(password);

    // Create the tokio runtime.
    let runtime = if RUNTIME.get().is_some() {
        RUNTIME.get().unwrap()
    } else {
        let rt = Runtime::new().unwrap();
        RUNTIME.set(rt).unwrap();
        RUNTIME.get().unwrap()
    };

    // Create the db pool options.
    let pool = MySqlPoolOptions::new().max_connections(max_connections as u32);

    let pool = if min_connections > 0 {
        pool.min_connections(min_connections as u32)
    } else {
        pool
    };

    let pool = if acquire_timeout_milis > 0 {
        pool.acquire_timeout(Duration::from_millis(acquire_timeout_milis as u64))
    } else {
        pool
    };

    let pool = if idle_timeout_milis > 0 {
        pool.idle_timeout(Duration::from_millis(idle_timeout_milis as u64))
    } else {
        pool
    };

    let pool = if max_lifetime_milis > 0 {
        pool.max_lifetime(Duration::from_millis(max_lifetime_milis as u64))
    } else {
        pool
    };

    let pool = pool.connect_with(options);

    // Create the pool here.
    let pool = runtime.block_on(pool);
    let pool: MySqlPool = match pool {
        Ok(pool) => pool,
        Err(err) => return sqlx4k_mysql_error_result_of(err).leak(),
    };
    let sqlx4k = Sqlx4kMySql { pool };
    let sqlx4k = Box::new(sqlx4k);
    let sqlx4k = Box::leak(sqlx4k);

    Sqlx4kMysqlResult {
        rt: sqlx4k as *mut _ as *mut c_void,
        ..Default::default()
    }
    .leak()
}

#[no_mangle]
pub extern "C" fn sqlx4k_mysql_pool_size(rt: *mut c_void) -> c_int {
    let sqlx4k = unsafe { &*(rt as *mut Sqlx4kMySql) };
    sqlx4k.pool.size() as c_int
}

#[no_mangle]
pub extern "C" fn sqlx4k_mysql_pool_idle_size(rt: *mut c_void) -> c_int {
    let sqlx4k = unsafe { &*(rt as *mut Sqlx4kMySql) };
    sqlx4k.pool.num_idle() as c_int
}

#[no_mangle]
pub extern "C" fn sqlx4k_mysql_close(
    rt: *mut c_void,
    callback: *mut c_void,
    fun: extern "C" fn(Sqlx4kMysqlPtr, *mut Sqlx4kMysqlResult),
) {
    let callback = Sqlx4kMysqlPtr { ptr: callback };
    let runtime = RUNTIME.get().unwrap();
    let sqlx4k = unsafe { &*(rt as *mut Sqlx4kMySql) };
    runtime.spawn(async move {
        let result = sqlx4k.close().await;
        fun(callback, result)
    });
}

#[no_mangle]
pub extern "C" fn sqlx4k_mysql_query(
    rt: *mut c_void,
    sql: *const c_char,
    callback: *mut c_void,
    fun: extern "C" fn(Sqlx4kMysqlPtr, *mut Sqlx4kMysqlResult),
) {
    let callback = Sqlx4kMysqlPtr { ptr: callback };
    let sql = c_chars_to_str_mysql(sql).to_owned();
    let runtime = RUNTIME.get().unwrap();
    let sqlx4k = unsafe { &*(rt as *mut Sqlx4kMySql) };
    runtime.spawn(async move {
        let result = sqlx4k.query(&sql).await;
        fun(callback, result)
    });
}

#[no_mangle]
pub extern "C" fn sqlx4k_mysql_fetch_all(
    rt: *mut c_void,
    sql: *const c_char,
    callback: *mut c_void,
    fun: extern "C" fn(Sqlx4kMysqlPtr, *mut Sqlx4kMysqlResult),
) {
    let callback = Sqlx4kMysqlPtr { ptr: callback };
    let sql = c_chars_to_str_mysql(sql).to_owned();
    let runtime = RUNTIME.get().unwrap();
    let sqlx4k = unsafe { &*(rt as *mut Sqlx4kMySql) };
    runtime.spawn(async move {
        let result = sqlx4k.fetch_all(&sql).await;
        fun(callback, result)
    });
}

#[no_mangle]
pub extern "C" fn sqlx4k_mysql_cn_acquire(
    rt: *mut c_void,
    callback: *mut c_void,
    fun: extern "C" fn(Sqlx4kMysqlPtr, *mut Sqlx4kMysqlResult),
) {
    let callback = Sqlx4kMysqlPtr { ptr: callback };
    let runtime = RUNTIME.get().unwrap();
    let sqlx4k = unsafe { &*(rt as *mut Sqlx4kMySql) };
    runtime.spawn(async move {
        let result = sqlx4k.cn_acquire().await;
        fun(callback, result)
    });
}

#[no_mangle]
pub extern "C" fn sqlx4k_mysql_cn_release(
    rt: *mut c_void,
    cn: *mut c_void,
    callback: *mut c_void,
    fun: extern "C" fn(Sqlx4kMysqlPtr, *mut Sqlx4kMysqlResult),
) {
    let cn = Sqlx4kMysqlPtr { ptr: cn };
    let callback = Sqlx4kMysqlPtr { ptr: callback };
    let runtime = RUNTIME.get().unwrap();
    let sqlx4k = unsafe { &*(rt as *mut Sqlx4kMySql) };
    runtime.spawn(async move {
        let result = sqlx4k.cn_release(cn).await;
        fun(callback, result)
    });
}

#[no_mangle]
pub extern "C" fn sqlx4k_mysql_cn_query(
    rt: *mut c_void,
    cn: *mut c_void,
    sql: *const c_char,
    callback: *mut c_void,
    fun: extern "C" fn(Sqlx4kMysqlPtr, *mut Sqlx4kMysqlResult),
) {
    let cn = Sqlx4kMysqlPtr { ptr: cn };
    let callback = Sqlx4kMysqlPtr { ptr: callback };
    let sql = c_chars_to_str_mysql(sql).to_owned();
    let runtime = RUNTIME.get().unwrap();
    let sqlx4k = unsafe { &*(rt as *mut Sqlx4kMySql) };
    runtime.spawn(async move {
        let result = sqlx4k.cn_query(cn, &sql).await;
        fun(callback, result)
    });
}

#[no_mangle]
pub extern "C" fn sqlx4k_mysql_cn_fetch_all(
    rt: *mut c_void,
    cn: *mut c_void,
    sql: *const c_char,
    callback: *mut c_void,
    fun: extern "C" fn(Sqlx4kMysqlPtr, *mut Sqlx4kMysqlResult),
) {
    let cn = Sqlx4kMysqlPtr { ptr: cn };
    let callback = Sqlx4kMysqlPtr { ptr: callback };
    let sql = c_chars_to_str_mysql(sql).to_owned();
    let runtime = RUNTIME.get().unwrap();
    let sqlx4k = unsafe { &*(rt as *mut Sqlx4kMySql) };
    runtime.spawn(async move {
        let result = sqlx4k.cn_fetch_all(cn, &sql).await;
        fun(callback, result)
    });
}

#[no_mangle]
pub extern "C" fn sqlx4k_mysql_cn_tx_begin(
    rt: *mut c_void,
    cn: *mut c_void,
    callback: *mut c_void,
    fun: extern "C" fn(Sqlx4kMysqlPtr, *mut Sqlx4kMysqlResult),
) {
    let cn = Sqlx4kMysqlPtr { ptr: cn };
    let callback = Sqlx4kMysqlPtr { ptr: callback };
    let runtime = RUNTIME.get().unwrap();
    let sqlx4k = unsafe { &*(rt as *mut Sqlx4kMySql) };
    runtime.spawn(async move {
        let result = sqlx4k.cn_tx_begin(cn).await;
        fun(callback, result)
    });
}

#[no_mangle]
pub extern "C" fn sqlx4k_mysql_tx_begin(
    rt: *mut c_void,
    callback: *mut c_void,
    fun: extern "C" fn(Sqlx4kMysqlPtr, *mut Sqlx4kMysqlResult),
) {
    let callback = Sqlx4kMysqlPtr { ptr: callback };
    let runtime = RUNTIME.get().unwrap();
    let sqlx4k = unsafe { &*(rt as *mut Sqlx4kMySql) };
    runtime.spawn(async move {
        let result = sqlx4k.tx_begin().await;
        fun(callback, result)
    });
}

#[no_mangle]
pub extern "C" fn sqlx4k_mysql_tx_commit(
    rt: *mut c_void,
    tx: *mut c_void,
    callback: *mut c_void,
    fun: extern "C" fn(Sqlx4kMysqlPtr, *mut Sqlx4kMysqlResult),
) {
    let tx = Sqlx4kMysqlPtr { ptr: tx };
    let callback = Sqlx4kMysqlPtr { ptr: callback };
    let runtime = RUNTIME.get().unwrap();
    let sqlx4k = unsafe { &*(rt as *mut Sqlx4kMySql) };
    runtime.spawn(async move {
        let result = sqlx4k.tx_commit(tx).await;
        fun(callback, result)
    });
}

#[no_mangle]
pub extern "C" fn sqlx4k_mysql_tx_rollback(
    rt: *mut c_void,
    tx: *mut c_void,
    callback: *mut c_void,
    fun: extern "C" fn(Sqlx4kMysqlPtr, *mut Sqlx4kMysqlResult),
) {
    let tx = Sqlx4kMysqlPtr { ptr: tx };
    let callback = Sqlx4kMysqlPtr { ptr: callback };
    let runtime = RUNTIME.get().unwrap();
    let sqlx4k = unsafe { &*(rt as *mut Sqlx4kMySql) };
    runtime.spawn(async move {
        let result = sqlx4k.tx_rollback(tx).await;
        fun(callback, result)
    });
}

#[no_mangle]
pub extern "C" fn sqlx4k_mysql_tx_query(
    rt: *mut c_void,
    tx: *mut c_void,
    sql: *const c_char,
    callback: *mut c_void,
    fun: extern "C" fn(Sqlx4kMysqlPtr, *mut Sqlx4kMysqlResult),
) {
    let tx = Sqlx4kMysqlPtr { ptr: tx };
    let callback = Sqlx4kMysqlPtr { ptr: callback };
    let sql = c_chars_to_str_mysql(sql).to_owned();
    let runtime = RUNTIME.get().unwrap();
    let sqlx4k = unsafe { &*(rt as *mut Sqlx4kMySql) };
    runtime.spawn(async move {
        let result = sqlx4k.tx_query(tx, &sql).await;
        fun(callback, result)
    });
}

#[no_mangle]
pub extern "C" fn sqlx4k_mysql_tx_fetch_all(
    rt: *mut c_void,
    tx: *mut c_void,
    sql: *const c_char,
    callback: *mut c_void,
    fun: extern "C" fn(Sqlx4kMysqlPtr, *mut Sqlx4kMysqlResult),
) {
    let tx = Sqlx4kMysqlPtr { ptr: tx };
    let callback = Sqlx4kMysqlPtr { ptr: callback };
    let sql = c_chars_to_str_mysql(sql).to_owned();
    let runtime = RUNTIME.get().unwrap();
    let sqlx4k = unsafe { &*(rt as *mut Sqlx4kMySql) };
    runtime.spawn(async move {
        let result = sqlx4k.tx_fetch_all(tx, &sql).await;
        fun(callback, result)
    });
}

fn sqlx4k_mysql_result_of(result: Result<Vec<MySqlRow>, sqlx::Error>) -> Sqlx4kMysqlResult {
    match result {
        Ok(rows) => {
            let schema: Sqlx4kMysqlSchema = if rows.len() > 0 {
                sqlx4k_mysql_schema_of(rows.get(0).unwrap())
            } else {
                Sqlx4kMysqlSchema::default()
            };

            let schema = Box::new(schema);
            let schema = Box::leak(schema);

            let rows: Vec<Sqlx4kMysqlRow> = rows.iter().map(|r| sqlx4k_mysql_row_of(r)).collect();
            let size = rows.len();
            let rows: Box<[Sqlx4kMysqlRow]> = rows.into_boxed_slice();
            let rows: &mut [Sqlx4kMysqlRow] = Box::leak(rows);
            let rows: *mut Sqlx4kMysqlRow = rows.as_mut_ptr();

            Sqlx4kMysqlResult {
                schema,
                size: size as c_int,
                rows,
                ..Default::default()
            }
        }
        Err(err) => sqlx4k_mysql_error_result_of(err),
    }
}

fn sqlx4k_mysql_schema_of(row: &MySqlRow) -> Sqlx4kMysqlSchema {
    let columns = row.columns();
    if columns.is_empty() {
        Sqlx4kMysqlSchema::default()
    } else {
        let columns: Vec<Sqlx4kMysqlSchemaColumn> = row
            .columns()
            .iter()
            .map(|c| {
                let name: &str = c.name();
                let value_ref: MySqlValueRef = row.try_get_raw(c.ordinal()).unwrap();
                let info: std::borrow::Cow<MySqlTypeInfo> = value_ref.type_info();
                let kind: &str = info.name();
                Sqlx4kMysqlSchemaColumn {
                    ordinal: c.ordinal() as c_int,
                    name: CString::new(name).unwrap().into_raw(),
                    kind: CString::new(kind).unwrap().into_raw(),
                }
            })
            .collect();

        let size = columns.len();
        let columns: Box<[Sqlx4kMysqlSchemaColumn]> = columns.into_boxed_slice();
        let columns: &mut [Sqlx4kMysqlSchemaColumn] = Box::leak(columns);
        let columns: *mut Sqlx4kMysqlSchemaColumn = columns.as_mut_ptr();

        Sqlx4kMysqlSchema {
            size: size as c_int,
            columns,
        }
    }
}

/// Decodes a single MySQL column value into a heap-allocated C string.
///
/// `row.get_unchecked::<&str>` only works reliably when the result is in the
/// text protocol used by `pool.execute(sql)`. The `_with_params` path uses
/// MySQL's binary protocol, where INT/BIGINT/FLOAT/DOUBLE/temporal/binary
/// values come back as raw bytes — `from_utf8` happens to succeed on integer
/// payloads but the resulting string contains embedded NULs that crash
/// `CString::new`. Decode by type so both protocols round-trip identically.
fn decode_mysql_column_value(row: &MySqlRow, ordinal: usize) -> *mut c_char {
    let type_name: String = {
        let value_ref = row.try_get_raw(ordinal).unwrap();
        if value_ref.is_null() {
            return null_mut();
        }
        value_ref.type_info().name().to_string()
    };

    let s: String = match type_name.as_str() {
        // sqlx-mysql reports `TINYINT(1)` (what `BOOLEAN`/`BOOL` creates) as
        // "BOOLEAN" with `display = 1` rather than the bare "TINYINT" name.
        "BOOLEAN" => row.get_unchecked::<i8, _>(ordinal).to_string(),
        "TINYINT" => row.get_unchecked::<i8, _>(ordinal).to_string(),
        "SMALLINT" => row.get_unchecked::<i16, _>(ordinal).to_string(),
        "MEDIUMINT" | "INT" => row.get_unchecked::<i32, _>(ordinal).to_string(),
        "BIGINT" => row.get_unchecked::<i64, _>(ordinal).to_string(),
        "TINYINT UNSIGNED" => row.get_unchecked::<u8, _>(ordinal).to_string(),
        "SMALLINT UNSIGNED" => row.get_unchecked::<u16, _>(ordinal).to_string(),
        "MEDIUMINT UNSIGNED" | "INT UNSIGNED" => row.get_unchecked::<u32, _>(ordinal).to_string(),
        "BIGINT UNSIGNED" => row.get_unchecked::<u64, _>(ordinal).to_string(),
        "FLOAT" => row.get_unchecked::<f32, _>(ordinal).to_string(),
        "DOUBLE" => row.get_unchecked::<f64, _>(ordinal).to_string(),
        "BLOB" | "TINYBLOB" | "MEDIUMBLOB" | "LONGBLOB" | "VARBINARY" | "BINARY" => {
            let bytes: Vec<u8> = row.get_unchecked(ordinal);
            hex::encode(bytes)
        }
        // Temporal types arrive as packed binary in the prepared-statement
        // protocol; decode via chrono and format to `yyyy-MM-dd HH:mm:ss[.uuuuuu]`
        // so the existing asInstant / asLocalDate* decoders round-trip.
        "DATE" => row
            .get_unchecked::<chrono::NaiveDate, _>(ordinal)
            .to_string(),
        "TIME" => row
            .get_unchecked::<chrono::NaiveTime, _>(ordinal)
            .to_string(),
        "DATETIME" | "TIMESTAMP" => {
            let dt: chrono::NaiveDateTime = row.get_unchecked(ordinal);
            dt.format("%Y-%m-%d %H:%M:%S%.6f").to_string()
        }
        // VARCHAR / CHAR / TEXT (all varieties) / JSON / ENUM / SET / YEAR / BIT
        // and anything else fall through to `&str`. In the text protocol they
        // arrive as text bytes; for text-typed columns this also works in the
        // binary protocol.
        _ => row.get_unchecked::<&str, _>(ordinal).to_string(),
    };
    CString::new(s).unwrap().into_raw()
}

fn sqlx4k_mysql_row_of(row: &MySqlRow) -> Sqlx4kMysqlRow {
    let columns = row.columns();
    if columns.is_empty() {
        Sqlx4kMysqlRow::default()
    } else {
        let columns: Vec<Sqlx4kMysqlColumn> = row
            .columns()
            .iter()
            .map(|c| Sqlx4kMysqlColumn {
                ordinal: c.ordinal() as c_int,
                value: decode_mysql_column_value(row, c.ordinal()),
            })
            .collect();

        let size = columns.len();
        let columns: Box<[Sqlx4kMysqlColumn]> = columns.into_boxed_slice();
        let columns: &mut [Sqlx4kMysqlColumn] = Box::leak(columns);
        let columns: *mut Sqlx4kMysqlColumn = columns.as_mut_ptr();

        Sqlx4kMysqlRow {
            size: size as c_int,
            columns,
        }
    }
}

// ----------------------------------------------------------------------------
// Parameterized FFI exports (used by the Statement-based execute/fetchAll path)
// ----------------------------------------------------------------------------

#[no_mangle]
pub extern "C" fn sqlx4k_mysql_query_with_params(
    rt: *mut c_void,
    sql: *const c_char,
    params: *const Sqlx4kMysqlParam,
    params_len: c_int,
    callback: *mut c_void,
    fun: extern "C" fn(Sqlx4kMysqlPtr, *mut Sqlx4kMysqlResult),
) {
    let callback = Sqlx4kMysqlPtr { ptr: callback };
    let sql = c_chars_to_str_mysql(sql).to_owned();
    let owned = read_params(params, params_len);
    let runtime = RUNTIME.get().unwrap();
    let sqlx4k = unsafe { &*(rt as *mut Sqlx4kMySql) };
    runtime.spawn(async move {
        let result = sqlx4k.query_with_params(&sql, owned).await;
        fun(callback, result)
    });
}

#[no_mangle]
pub extern "C" fn sqlx4k_mysql_fetch_all_with_params(
    rt: *mut c_void,
    sql: *const c_char,
    params: *const Sqlx4kMysqlParam,
    params_len: c_int,
    callback: *mut c_void,
    fun: extern "C" fn(Sqlx4kMysqlPtr, *mut Sqlx4kMysqlResult),
) {
    let callback = Sqlx4kMysqlPtr { ptr: callback };
    let sql = c_chars_to_str_mysql(sql).to_owned();
    let owned = read_params(params, params_len);
    let runtime = RUNTIME.get().unwrap();
    let sqlx4k = unsafe { &*(rt as *mut Sqlx4kMySql) };
    runtime.spawn(async move {
        let result = sqlx4k.fetch_all_with_params(&sql, owned).await;
        fun(callback, result)
    });
}

#[no_mangle]
pub extern "C" fn sqlx4k_mysql_cn_query_with_params(
    rt: *mut c_void,
    cn: *mut c_void,
    sql: *const c_char,
    params: *const Sqlx4kMysqlParam,
    params_len: c_int,
    callback: *mut c_void,
    fun: extern "C" fn(Sqlx4kMysqlPtr, *mut Sqlx4kMysqlResult),
) {
    let cn = Sqlx4kMysqlPtr { ptr: cn };
    let callback = Sqlx4kMysqlPtr { ptr: callback };
    let sql = c_chars_to_str_mysql(sql).to_owned();
    let owned = read_params(params, params_len);
    let runtime = RUNTIME.get().unwrap();
    let sqlx4k = unsafe { &*(rt as *mut Sqlx4kMySql) };
    runtime.spawn(async move {
        let result = sqlx4k.cn_query_with_params(cn, &sql, owned).await;
        fun(callback, result)
    });
}

#[no_mangle]
pub extern "C" fn sqlx4k_mysql_cn_fetch_all_with_params(
    rt: *mut c_void,
    cn: *mut c_void,
    sql: *const c_char,
    params: *const Sqlx4kMysqlParam,
    params_len: c_int,
    callback: *mut c_void,
    fun: extern "C" fn(Sqlx4kMysqlPtr, *mut Sqlx4kMysqlResult),
) {
    let cn = Sqlx4kMysqlPtr { ptr: cn };
    let callback = Sqlx4kMysqlPtr { ptr: callback };
    let sql = c_chars_to_str_mysql(sql).to_owned();
    let owned = read_params(params, params_len);
    let runtime = RUNTIME.get().unwrap();
    let sqlx4k = unsafe { &*(rt as *mut Sqlx4kMySql) };
    runtime.spawn(async move {
        let result = sqlx4k.cn_fetch_all_with_params(cn, &sql, owned).await;
        fun(callback, result)
    });
}

#[no_mangle]
pub extern "C" fn sqlx4k_mysql_tx_query_with_params(
    rt: *mut c_void,
    tx: *mut c_void,
    sql: *const c_char,
    params: *const Sqlx4kMysqlParam,
    params_len: c_int,
    callback: *mut c_void,
    fun: extern "C" fn(Sqlx4kMysqlPtr, *mut Sqlx4kMysqlResult),
) {
    let tx = Sqlx4kMysqlPtr { ptr: tx };
    let callback = Sqlx4kMysqlPtr { ptr: callback };
    let sql = c_chars_to_str_mysql(sql).to_owned();
    let owned = read_params(params, params_len);
    let runtime = RUNTIME.get().unwrap();
    let sqlx4k = unsafe { &*(rt as *mut Sqlx4kMySql) };
    runtime.spawn(async move {
        let result = sqlx4k.tx_query_with_params(tx, &sql, owned).await;
        fun(callback, result)
    });
}

#[no_mangle]
pub extern "C" fn sqlx4k_mysql_tx_fetch_all_with_params(
    rt: *mut c_void,
    tx: *mut c_void,
    sql: *const c_char,
    params: *const Sqlx4kMysqlParam,
    params_len: c_int,
    callback: *mut c_void,
    fun: extern "C" fn(Sqlx4kMysqlPtr, *mut Sqlx4kMysqlResult),
) {
    let tx = Sqlx4kMysqlPtr { ptr: tx };
    let callback = Sqlx4kMysqlPtr { ptr: callback };
    let sql = c_chars_to_str_mysql(sql).to_owned();
    let owned = read_params(params, params_len);
    let runtime = RUNTIME.get().unwrap();
    let sqlx4k = unsafe { &*(rt as *mut Sqlx4kMySql) };
    runtime.spawn(async move {
        let result = sqlx4k.tx_fetch_all_with_params(tx, &sql, owned).await;
        fun(callback, result)
    });
}
