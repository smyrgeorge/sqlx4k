//! Building results from rows (their schema and values), and freeing them.

use futures_util::TryStreamExt;
use sqlx::postgres::{PgRow, PgTypeInfo, PgValueRef};
use sqlx::{AssertSqlSafe, Column, Executor, Postgres, Row, TypeInfo, ValueRef};
use std::ffi::{c_char, c_int, CStr, CString};
use std::ptr::null_mut;
use std::slice;

use crate::decode::decode_postgresql_column_value;
use crate::errors::sqlx4k_postgres_error_result_of;
use crate::types::{
    Sqlx4kPostgresColumn, Sqlx4kPostgresResult, Sqlx4kPostgresRow, Sqlx4kPostgresSchema,
    Sqlx4kPostgresSchemaColumn,
};

#[no_mangle]
pub extern "C" fn sqlx4k_postgres_free_result(ptr: *mut Sqlx4kPostgresResult) {
    let ptr: Sqlx4kPostgresResult = unsafe { *Box::from_raw(ptr) };

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

    let schema: Sqlx4kPostgresSchema = unsafe { *Box::from_raw(ptr.schema) };
    let columns: Vec<Sqlx4kPostgresSchemaColumn> =
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

    let rows: Vec<Sqlx4kPostgresRow> =
        unsafe { vec_of(ptr.rows, ptr.size as usize) };
    for row in rows {
        let columns: Vec<Sqlx4kPostgresColumn> =
            unsafe { vec_of(row.columns, row.size as usize) };
        for col in columns {
            if col.value != null_mut() {
                let value = unsafe { CString::from_raw(col.value) };
                std::mem::drop(value);
            }
        }
    }
}

pub fn c_chars_to_str_postgres<'a>(c_chars: *const c_char) -> &'a str {
    unsafe { CStr::from_ptr(c_chars).to_str().unwrap() }
}

/// The rows of the last statement of `sql`, as the JVM driver returns them. A simple query may hold several
/// statements, whose rows (with columns of their own) fetch_all would return one after the other: the result
/// describes the columns of a single row set.
pub(crate) async fn fetch_last<'e, E>(executor: E, sql: String) -> Result<Vec<PgRow>, sqlx::Error>
where
    E: Executor<'e, Database = Postgres>,
{
    let mut items = executor.fetch_many(AssertSqlSafe(sql));
    let mut current = Vec::new();
    let mut last = Vec::new();
    while let Some(item) = items.try_next().await? {
        match item {
            // The end of a statement.
            sqlx::Either::Left(_) => last = std::mem::take(&mut current),
            sqlx::Either::Right(row) => current.push(row),
        }
    }
    Ok(if current.is_empty() { last } else { current })
}

pub(crate) fn sqlx4k_postgresql_result_of(result: Result<Vec<PgRow>, sqlx::Error>) -> Sqlx4kPostgresResult {
    match result {
        Ok(rows) => {
            let schema: Sqlx4kPostgresSchema = if rows.len() > 0 {
                sqlx4k_postgresql_schema_of(rows.get(0).unwrap())
            } else {
                Sqlx4kPostgresSchema::default()
            };

            let schema = Box::new(schema);
            let schema = Box::leak(schema);

            let rows: Vec<Sqlx4kPostgresRow> =
                rows.iter().map(|r| sqlx4k_postgresql_row_of(r)).collect();
            let size = rows.len();
            let rows: Box<[Sqlx4kPostgresRow]> = rows.into_boxed_slice();
            let rows: &mut [Sqlx4kPostgresRow] = Box::leak(rows);
            let rows: *mut Sqlx4kPostgresRow = rows.as_mut_ptr();

            Sqlx4kPostgresResult {
                schema,
                size: size as c_int,
                rows,
                ..Default::default()
            }
        }
        Err(err) => sqlx4k_postgres_error_result_of(err),
    }
}

pub(crate) fn sqlx4k_postgresql_schema_of(row: &PgRow) -> Sqlx4kPostgresSchema {
    let columns = row.columns();
    if columns.is_empty() {
        Sqlx4kPostgresSchema::default()
    } else {
        let columns: Vec<Sqlx4kPostgresSchemaColumn> = row
            .columns()
            .iter()
            .map(|c| {
                let name: &str = c.name();
                let value_ref: PgValueRef = row.try_get_raw(c.ordinal()).unwrap();
                let info: std::borrow::Cow<PgTypeInfo> = value_ref.type_info();
                let kind: &str = info.name();
                Sqlx4kPostgresSchemaColumn {
                    ordinal: c.ordinal() as c_int,
                    name: CString::new(name).unwrap().into_raw(),
                    kind: CString::new(kind).unwrap().into_raw(),
                }
            })
            .collect();

        let size = columns.len();
        let columns: Box<[Sqlx4kPostgresSchemaColumn]> = columns.into_boxed_slice();
        let columns: &mut [Sqlx4kPostgresSchemaColumn] = Box::leak(columns);
        let columns: *mut Sqlx4kPostgresSchemaColumn = columns.as_mut_ptr();

        Sqlx4kPostgresSchema {
            size: size as c_int,
            columns,
        }
    }
}

pub(crate) fn sqlx4k_postgresql_row_of(row: &PgRow) -> Sqlx4kPostgresRow {
    let columns = row.columns();
    if columns.is_empty() {
        Sqlx4kPostgresRow::default()
    } else {
        let columns: Vec<Sqlx4kPostgresColumn> = row
            .columns()
            .iter()
            .map(|c| Sqlx4kPostgresColumn {
                ordinal: c.ordinal() as c_int,
                value: decode_postgresql_column_value(row, c.ordinal()),
            })
            .collect();

        let size = columns.len();
        let columns: Box<[Sqlx4kPostgresColumn]> = columns.into_boxed_slice();
        let columns: &mut [Sqlx4kPostgresColumn] = Box::leak(columns);
        let columns: *mut Sqlx4kPostgresColumn = columns.as_mut_ptr();

        Sqlx4kPostgresRow {
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
        sqlx4k_postgres_free_result(sqlx4k_postgresql_result_of(Ok(vec![])).leak());
    }
}
