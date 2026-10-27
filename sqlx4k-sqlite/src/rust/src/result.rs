//! Building results from rows (their schema and values), and freeing them.

use sqlx::sqlite::{SqliteRow, SqliteTypeInfo, SqliteValueRef};
use sqlx::{Column, Row, TypeInfo, ValueRef};
use std::ffi::{c_char, c_int, CStr, CString};
use std::ptr::null_mut;
use std::slice;

use crate::decode::{column_value_into_raw, format_sqlite_real, free_column_value};
use crate::errors::sqlx4k_sqlite_error_result_of;
use crate::types::{
    Sqlx4kSqliteColumn, Sqlx4kSqliteResult, Sqlx4kSqliteRow, Sqlx4kSqliteSchema,
    Sqlx4kSqliteSchemaColumn,
};

#[no_mangle]
pub extern "C" fn sqlx4k_sqlite_free_result(ptr: *mut Sqlx4kSqliteResult) {
    let ptr: Sqlx4kSqliteResult = unsafe { *Box::from_raw(ptr) };

    if ptr.error >= 0 {
        let error_message = unsafe { CString::from_raw(ptr.error_message) };
        std::mem::drop(error_message);
        if !ptr.error_sql_state.is_null() {
            let error_sql_state = unsafe { CString::from_raw(ptr.error_sql_state) };
            std::mem::drop(error_sql_state);
        }
    }

    if ptr.schema == null_mut() {
        return;
    }

    let schema: Sqlx4kSqliteSchema = unsafe { *Box::from_raw(ptr.schema) };
    let columns: Vec<Sqlx4kSqliteSchemaColumn> =
        unsafe { vec_of(schema.columns, schema.size as usize) };
    for col in columns {
        let name = unsafe { CString::from_raw(col.name) };
        std::mem::drop(name);
        let kind = unsafe { CString::from_raw(col.kind) };
        std::mem::drop(kind);
    }

    if ptr.rows == null_mut() {
        return;
    }

    let rows: Vec<Sqlx4kSqliteRow> =
        unsafe { vec_of(ptr.rows, ptr.size as usize) };
    for row in rows {
        let columns: Vec<Sqlx4kSqliteColumn> =
            unsafe { vec_of(row.columns, row.size as usize) };
        for col in columns {
            free_column_value(col.value, col.value_len);
        }
    }
}

pub fn c_chars_to_str_sqlite<'a>(c_chars: *const c_char) -> &'a str {
    unsafe { CStr::from_ptr(c_chars).to_str().unwrap() }
}

pub(crate) fn sqlx4k_sqlite_result_of(result: Result<Vec<SqliteRow>, sqlx::Error>) -> Sqlx4kSqliteResult {
    match result {
        Ok(rows) => {
            let schema: Sqlx4kSqliteSchema = if rows.len() > 0 {
                sqlx4k_sqlite_schema_of(rows.get(0).unwrap())
            } else {
                Sqlx4kSqliteSchema::default()
            };

            let schema = Box::new(schema);
            let schema = Box::leak(schema);

            let rows: Vec<Sqlx4kSqliteRow> = rows.iter().map(|r| sqlx4k_sqlite_row_of(r)).collect();
            let size = rows.len();
            let rows: Box<[Sqlx4kSqliteRow]> = rows.into_boxed_slice();
            let rows: &mut [Sqlx4kSqliteRow] = Box::leak(rows);
            let rows: *mut Sqlx4kSqliteRow = rows.as_mut_ptr();

            Sqlx4kSqliteResult {
                schema,
                size: size as c_int,
                rows,
                ..Default::default()
            }
        }
        Err(err) => sqlx4k_sqlite_error_result_of(err),
    }
}

pub(crate) fn sqlx4k_sqlite_schema_of(row: &SqliteRow) -> Sqlx4kSqliteSchema {
    let columns = row.columns();
    if columns.is_empty() {
        Sqlx4kSqliteSchema::default()
    } else {
        let columns: Vec<Sqlx4kSqliteSchemaColumn> = row
            .columns()
            .iter()
            .map(|c| {
                let name: &str = c.name();
                let value_ref: SqliteValueRef = row.try_get_raw(c.ordinal()).unwrap();
                let info: std::borrow::Cow<SqliteTypeInfo> = value_ref.type_info();
                let kind: &str = info.name();
                Sqlx4kSqliteSchemaColumn {
                    ordinal: c.ordinal() as c_int,
                    name: CString::new(name).unwrap().into_raw(),
                    kind: CString::new(kind).unwrap().into_raw(),
                }
            })
            .collect();

        let size = columns.len();
        let columns: Box<[Sqlx4kSqliteSchemaColumn]> = columns.into_boxed_slice();
        let columns: &mut [Sqlx4kSqliteSchemaColumn] = Box::leak(columns);
        let columns: *mut Sqlx4kSqliteSchemaColumn = columns.as_mut_ptr();

        Sqlx4kSqliteSchema {
            size: size as c_int,
            columns,
        }
    }
}

pub(crate) fn sqlx4k_sqlite_row_of(row: &SqliteRow) -> Sqlx4kSqliteRow {
    let columns = row.columns();
    if columns.is_empty() {
        Sqlx4kSqliteRow::default()
    } else {
        let columns: Vec<Sqlx4kSqliteColumn> = row
            .columns()
            .iter()
            .map(|c| {
                let value_ref: SqliteValueRef = row.try_get_raw(c.ordinal()).unwrap();
                let info: std::borrow::Cow<SqliteTypeInfo> = value_ref.type_info();
                let type_info = info.name();
                let value: Option<String> = match type_info {
                    "BLOB" => row.get_unchecked::<Option<&[u8]>, _>(c.ordinal()).map(hex::encode),
                    "REAL" => row.get_unchecked::<Option<f64>, _>(c.ordinal()).map(format_sqlite_real),
                    // TEXT (and INTEGER, which SQLite renders as text). TEXT may hold invalid UTF-8,
                    // which must not abort the host process: replace it with U+FFFD, like the JVM driver.
                    _ => row
                        .get_unchecked::<Option<&[u8]>, _>(c.ordinal())
                        .map(|bytes| String::from_utf8_lossy(bytes).into_owned()),
                };
                let (value, value_len) = match value {
                    Some(v) => column_value_into_raw(v),
                    None => (null_mut(), 0),
                };

                Sqlx4kSqliteColumn {
                    ordinal: c.ordinal() as c_int,
                    value,
                    value_len,
                }
            })
            .collect();

        let size = columns.len();
        let columns: Box<[Sqlx4kSqliteColumn]> = columns.into_boxed_slice();
        let columns: &mut [Sqlx4kSqliteColumn] = Box::leak(columns);
        let columns: *mut Sqlx4kSqliteColumn = columns.as_mut_ptr();

        Sqlx4kSqliteRow {
            size: size as c_int,
            columns,
        }
    }
}

/// `slice::from_raw_parts`, for a pointer that may be null when the length is 0: Kotlin passes no array for no
/// parameters, and `from_raw_parts` requires a non-null pointer even then (undefined behaviour otherwise).
pub(crate) unsafe fn slice_of<'a, T>(ptr: *const T, len: usize) -> &'a [T] {
    if ptr.is_null() || len == 0 {
        &[]
    } else {
        unsafe { slice::from_raw_parts(ptr, len) }
    }
}

/// `Vec::from_raw_parts` of an array leaked as a boxed slice of `len` elements, for a pointer that is null when the
/// array is empty (an empty result's schema, a row of no columns): `from_raw_parts` requires a non-null pointer even
/// then (undefined behaviour otherwise; an abort where its precondition checks are compiled in).
pub(crate) unsafe fn vec_of<T>(ptr: *mut T, len: usize) -> Vec<T> {
    if ptr.is_null() {
        Vec::new()
    } else {
        unsafe { Vec::from_raw_parts(ptr, len, len) }
    }
}

#[cfg(test)]
mod free_result_tests {
    use super::*;

    // Debug builds check `from_raw_parts`'s preconditions: freeing an empty result rebuilt a vector from a null pointer.
    #[test]
    fn frees_an_empty_result() {
        sqlx4k_sqlite_free_result(sqlx4k_sqlite_result_of(Ok(vec![])).leak());
    }
}
