//! Building results from rows (their schema and values), and freeing them.

use futures_util::TryStreamExt;
use sqlx::mysql::{MySqlRow, MySqlTypeInfo, MySqlValueRef};
use sqlx::{Column, Execute, Executor, MySql, Row, ValueRef};
use std::ffi::{c_char, c_int, CStr, CString};
use std::ptr::null_mut;
use std::slice;

use crate::decode::{decode_mysql_column_value, mysql_type_name};
use crate::errors::sqlx4k_mysql_error_result_of;
use crate::types::{
    Sqlx4kMysqlColumn, Sqlx4kMysqlResult, Sqlx4kMysqlRow, Sqlx4kMysqlSchema,
    Sqlx4kMysqlSchemaColumn,
};

#[no_mangle]
pub extern "C" fn sqlx4k_mysql_free_result(ptr: *mut Sqlx4kMysqlResult) {
    let ptr: Sqlx4kMysqlResult = unsafe { *Box::from_raw(ptr) };

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

    let schema: Sqlx4kMysqlSchema = unsafe { *Box::from_raw(ptr.schema) };
    let columns: Vec<Sqlx4kMysqlSchemaColumn> =
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

    let rows: Vec<Sqlx4kMysqlRow> =
        unsafe { vec_of(ptr.rows, ptr.size as usize) };
    for row in rows {
        let columns: Vec<Sqlx4kMysqlColumn> =
            unsafe { vec_of(row.columns, row.size as usize) };
        for col in columns {
            if col.value != null_mut() {
                // Allocated by `column_value_of` with its length (the value may hold NUL characters).
                let len = col.value_len as usize + 1;
                drop(unsafe { Box::from_raw(slice::from_raw_parts_mut(col.value as *mut u8, len)) });
            }
        }
    }
}

pub fn c_chars_to_str_mysql<'a>(c_chars: *const c_char) -> &'a str {
    unsafe { CStr::from_ptr(c_chars).to_str().unwrap() }
}

/// Runs a query and returns the rows of the last of its result sets that has rows. A query can return several
/// result sets, from several statements or a procedure that runs several SELECTs, which fetch_all would return
/// one after the other: the result describes the columns of a single result set. A result set with no rows is
/// passed over, as the status a CALL ends with is one (sqlx does not tell it from an empty SELECT).
pub(crate) async fn fetch_last<'e, 'q: 'e, E, Q>(executor: E, query: Q) -> Result<Vec<MySqlRow>, sqlx::Error>
where
    E: Executor<'e, Database = MySql>,
    Q: 'q + Execute<'q, MySql>,
{
    let mut items = executor.fetch_many(query);
    let mut current = Vec::new();
    let mut last = Vec::new();
    while let Some(item) = items.try_next().await? {
        match item {
            // The end of a result set.
            sqlx::Either::Left(_) => {
                if !current.is_empty() {
                    last = std::mem::take(&mut current)
                }
            }
            sqlx::Either::Right(row) => current.push(row),
        }
    }
    Ok(if current.is_empty() { last } else { current })
}

pub(crate) fn sqlx4k_mysql_result_of(result: Result<Vec<MySqlRow>, sqlx::Error>) -> Sqlx4kMysqlResult {
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

pub(crate) fn sqlx4k_mysql_schema_of(row: &MySqlRow) -> Sqlx4kMysqlSchema {
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
                let kind: &str = mysql_type_name(&info);
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

pub(crate) fn sqlx4k_mysql_row_of(row: &MySqlRow) -> Sqlx4kMysqlRow {
    let columns = row.columns();
    if columns.is_empty() {
        Sqlx4kMysqlRow::default()
    } else {
        let columns: Vec<Sqlx4kMysqlColumn> = row
            .columns()
            .iter()
            .map(|c| {
                let (value, value_len) = decode_mysql_column_value(row, c.ordinal());
                Sqlx4kMysqlColumn {
                    ordinal: c.ordinal() as c_int,
                    value,
                    value_len,
                }
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
        sqlx4k_mysql_free_result(sqlx4k_mysql_result_of(Ok(vec![])).leak());
    }
}
