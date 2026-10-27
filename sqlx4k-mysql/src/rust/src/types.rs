//! The C structs handed across the FFI boundary, and the result and error-kind codes they carry.

use std::ffi::{c_char, c_int, c_ulonglong, c_void};
use std::ptr::null_mut;

use crate::params::Sqlx4kMysqlParam;

pub const OK: c_int = -1;

pub const ERROR_DATABASE: c_int = 0;

pub const ERROR_POOL_TIMED_OUT: c_int = 1;

pub const ERROR_POOL_CLOSED: c_int = 2;

pub const ERROR_WORKER_CRASHED: c_int = 3;

/// Reported in `error_native_code` when the database has no numeric error code.
pub const NO_NATIVE_CODE: c_int = -1;

// `SQLError.Kind` ordinals (IMPORTANT: keep in sync with the Kotlin enum; do not reorder).
pub const KIND_UNIQUE_VIOLATION: c_int = 0;

pub const KIND_FOREIGN_KEY_VIOLATION: c_int = 1;

pub const KIND_NOT_NULL_VIOLATION: c_int = 2;

pub const KIND_CHECK_VIOLATION: c_int = 3;

pub const KIND_EXCLUSION_VIOLATION: c_int = 4;

pub const KIND_DEADLOCK: c_int = 5;

// Not reported by MySQL, kept so that the ordinals match the Kotlin enum.
#[allow(dead_code)]
pub const KIND_SERIALIZATION_FAILURE: c_int = 6;

pub const KIND_LOCK_TIMEOUT: c_int = 7;

pub const KIND_OTHER: c_int = 8;

#[repr(C)]
pub struct Sqlx4kMysqlPtr {
    pub ptr: *mut c_void,
}

unsafe impl Send for Sqlx4kMysqlPtr {}

unsafe impl Sync for Sqlx4kMysqlPtr {}

impl Sqlx4kMysqlPtr {
    /// The wrapped pointer. Going through a method (rather than the `ptr` field) inside a spawned
    /// task captures the whole `Send` wrapper, not the raw pointer field (edition-2021 captures).
    pub(crate) fn raw(&self) -> *mut c_void {
        self.ptr
    }
}

#[repr(C)]
pub struct Sqlx4kMysqlResult {
    pub error: c_int,
    pub error_message: *mut c_char,
    /// SQLSTATE reported by the database, or null (SQLite has none).
    pub error_sql_state: *mut c_char,
    /// The database's own numeric error code, or `NO_NATIVE_CODE` (PostgreSQL has none).
    pub error_native_code: c_int,
    /// `SQLError.Kind` ordinal (`KIND_*`).
    pub error_kind: c_int,
    pub rows_affected: c_ulonglong,
    /// `LAST_INSERT_ID()` of the statement: the id generated for an inserted row, or 0.
    pub last_insert_id: c_ulonglong,
    pub cn: *mut c_void,
    pub tx: *mut c_void,
    pub rt: *mut c_void,
    /// A row stream opened by `sqlx4k_mysql_*_stream_open` (see `Sqlx4kMysqlStream`), or null.
    pub stream: *mut c_void,
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
            error_sql_state: null_mut(),
            error_native_code: NO_NATIVE_CODE,
            error_kind: KIND_OTHER,
            rows_affected: 0,
            last_insert_id: 0,
            cn: null_mut(),
            tx: null_mut(),
            rt: null_mut(),
            stream: null_mut(),
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
    /// NUL-terminated, but read with `value_len`: a MySQL text value may hold NUL characters itself.
    pub value: *mut c_char,
    /// The length of `value` in bytes, without its terminating NUL.
    pub value_len: c_int,
}

#[no_mangle]
pub extern "C" fn auto_generated_for_struct_mysql_Sqlx4kMysqlPtr(_: Sqlx4kMysqlPtr) {}

#[no_mangle]
pub extern "C" fn auto_generated_for_struct_mysql_Sqlx4kMysqlResult(_: Sqlx4kMysqlResult) {}

#[no_mangle]
pub extern "C" fn auto_generated_for_struct_mysql_Sqlx4kMysqlParam(_: Sqlx4kMysqlParam) {}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::result::sqlx4k_mysql_free_result;

    /// The constants of the Kotlin enum `name` of `SQLError` (sqlx4k/.../SQLError.kt), in their order.
    fn kotlin_enum(name: &str) -> Vec<String> {
        let path = concat!(
            env!("CARGO_MANIFEST_DIR"),
            "/../../../sqlx4k/src/commonMain/kotlin/io/github/smyrgeorge/sqlx4k/SQLError.kt"
        );
        let source = std::fs::read_to_string(path).unwrap();
        let body = source.split(&format!("enum class {name} {{")).nth(1).unwrap();
        let body = &body[..body.find('}').unwrap()];
        body.lines()
            .map(str::trim)
            .filter(|l| !l.starts_with("//") && !l.starts_with("/*"))
            .map(|l| l.trim_end_matches(','))
            .filter(|l| l.chars().next().is_some_and(char::is_uppercase))
            .map(str::to_string)
            .collect()
    }

    #[test]
    fn error_codes_are_the_ordinals_of_the_kotlin_codes() {
        let codes = kotlin_enum("Code");
        for (code, name) in [
            (ERROR_DATABASE, "Database"),
            (ERROR_POOL_TIMED_OUT, "PoolTimedOut"),
            (ERROR_POOL_CLOSED, "PoolClosed"),
            (ERROR_WORKER_CRASHED, "WorkerCrashed"),
        ] {
            assert_eq!(codes[code as usize], name);
        }
    }

    #[test]
    fn kinds_are_the_ordinals_of_the_kotlin_kinds() {
        let kinds = kotlin_enum("Kind");
        let ours = [
            (KIND_UNIQUE_VIOLATION, "UniqueViolation"),
            (KIND_FOREIGN_KEY_VIOLATION, "ForeignKeyViolation"),
            (KIND_NOT_NULL_VIOLATION, "NotNullViolation"),
            (KIND_CHECK_VIOLATION, "CheckViolation"),
            (KIND_EXCLUSION_VIOLATION, "ExclusionViolation"),
            (KIND_DEADLOCK, "Deadlock"),
            (KIND_SERIALIZATION_FAILURE, "SerializationFailure"),
            (KIND_LOCK_TIMEOUT, "LockTimeout"),
            (KIND_OTHER, "Other"),
        ];
        assert_eq!(kinds.len(), ours.len());
        for (kind, name) in ours {
            assert_eq!(kinds[kind as usize], name);
        }
    }

    #[test]
    fn a_default_result_is_no_error_and_holds_nothing() {
        let r = Sqlx4kMysqlResult::default();
        assert_eq!(r.error, OK);
        assert!(r.error_message.is_null());
        assert!(r.error_sql_state.is_null());
        assert_eq!(r.error_native_code, NO_NATIVE_CODE);
        assert_eq!(r.error_kind, KIND_OTHER);
        assert_eq!(r.rows_affected, 0);
        assert!(r.cn.is_null() && r.tx.is_null() && r.rt.is_null() && r.stream.is_null());
        assert!(r.schema.is_null() && r.rows.is_null());
        assert_eq!(r.size, 0);
    }

    #[test]
    fn a_leaked_result_is_freed() {
        let leaked = Sqlx4kMysqlResult::default().leak();
        assert!(!leaked.is_null());
        sqlx4k_mysql_free_result(leaked);
    }

    #[test]
    fn a_default_schema_and_row_have_no_columns() {
        let schema = Sqlx4kMysqlSchema::default();
        assert_eq!(schema.size, 0);
        assert!(schema.columns.is_null());
        let row = Sqlx4kMysqlRow::default();
        assert_eq!(row.size, 0);
        assert!(row.columns.is_null());
    }

    #[test]
    fn a_ptr_hands_back_its_pointer() {
        let mut value = 7;
        let ptr = Sqlx4kMysqlPtr { ptr: &mut value as *mut i32 as *mut c_void };
        assert_eq!(ptr.raw(), &mut value as *mut i32 as *mut c_void);
    }
}
