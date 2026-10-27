//! Building results from rows (their schema and values), and freeing them.

use sqlx::sqlite::{SqliteRow, SqliteTypeInfo, SqliteValueRef};
use sqlx::{Column, Row, TypeInfo, ValueRef};
use std::ffi::{c_char, c_int, CStr, CString};
use std::ptr::null_mut;
use std::slice;

use crate::core::decode::{column_value_into_raw, format_sqlite_real, free_column_value};
use crate::core::errors::error_result_of;
use crate::core::types::{
    Sqlx4kSqliteCipherColumn, Sqlx4kSqliteCipherResult, Sqlx4kSqliteCipherRow,
    Sqlx4kSqliteCipherSchema, Sqlx4kSqliteCipherSchemaColumn,
};

/// Frees a leaked [`Sqlx4kSqliteCipherResult`] and every nested allocation it owns.
pub fn free_result(ptr: *mut Sqlx4kSqliteCipherResult) {
    // Like C's free(NULL): Box::from_raw requires a non-null pointer.
    if ptr.is_null() {
        return;
    }
    let ptr: Sqlx4kSqliteCipherResult = unsafe { *Box::from_raw(ptr) };

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

    let schema: Sqlx4kSqliteCipherSchema = unsafe { *Box::from_raw(ptr.schema) };
    let columns: Vec<Sqlx4kSqliteCipherSchemaColumn> =
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

    let rows: Vec<Sqlx4kSqliteCipherRow> =
        unsafe { vec_of(ptr.rows, ptr.size as usize) };
    for row in rows {
        let columns: Vec<Sqlx4kSqliteCipherColumn> =
            unsafe { vec_of(row.columns, row.size as usize) };
        for col in columns {
            free_column_value(col.value, col.value_len);
        }
    }
}

pub fn c_chars_to_str<'a>(c_chars: *const c_char) -> &'a str {
    unsafe { CStr::from_ptr(c_chars).to_str().unwrap() }
}

pub fn result_of(result: Result<Vec<SqliteRow>, sqlx::Error>) -> Sqlx4kSqliteCipherResult {
    match result {
        Ok(rows) => {
            let schema: Sqlx4kSqliteCipherSchema = if rows.len() > 0 {
                schema_of(rows.get(0).unwrap())
            } else {
                Sqlx4kSqliteCipherSchema::default()
            };

            let schema = Box::new(schema);
            let schema = Box::leak(schema);

            let rows: Vec<Sqlx4kSqliteCipherRow> = rows.iter().map(|r| row_of(r)).collect();
            let size = rows.len();
            let rows: Box<[Sqlx4kSqliteCipherRow]> = rows.into_boxed_slice();
            let rows: &mut [Sqlx4kSqliteCipherRow] = Box::leak(rows);
            let rows: *mut Sqlx4kSqliteCipherRow = rows.as_mut_ptr();

            Sqlx4kSqliteCipherResult {
                schema,
                size: size as c_int,
                rows,
                ..Default::default()
            }
        }
        Err(err) => error_result_of(err),
    }
}

pub(crate) fn schema_of(row: &SqliteRow) -> Sqlx4kSqliteCipherSchema {
    let columns = row.columns();
    if columns.is_empty() {
        Sqlx4kSqliteCipherSchema::default()
    } else {
        let columns: Vec<Sqlx4kSqliteCipherSchemaColumn> = row
            .columns()
            .iter()
            .map(|c| {
                let name: &str = c.name();
                let value_ref: SqliteValueRef = row.try_get_raw(c.ordinal()).unwrap();
                let info: std::borrow::Cow<SqliteTypeInfo> = value_ref.type_info();
                let kind: &str = info.name();
                Sqlx4kSqliteCipherSchemaColumn {
                    ordinal: c.ordinal() as c_int,
                    name: CString::new(name).unwrap().into_raw(),
                    kind: CString::new(kind).unwrap().into_raw(),
                }
            })
            .collect();

        let size = columns.len();
        let columns: Box<[Sqlx4kSqliteCipherSchemaColumn]> = columns.into_boxed_slice();
        let columns: &mut [Sqlx4kSqliteCipherSchemaColumn] = Box::leak(columns);
        let columns: *mut Sqlx4kSqliteCipherSchemaColumn = columns.as_mut_ptr();

        Sqlx4kSqliteCipherSchema {
            size: size as c_int,
            columns,
        }
    }
}

pub(crate) fn row_of(row: &SqliteRow) -> Sqlx4kSqliteCipherRow {
    let columns = row.columns();
    if columns.is_empty() {
        Sqlx4kSqliteCipherRow::default()
    } else {
        let columns: Vec<Sqlx4kSqliteCipherColumn> = row
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

                Sqlx4kSqliteCipherColumn {
                    ordinal: c.ordinal() as c_int,
                    value,
                    value_len,
                }
            })
            .collect();

        let size = columns.len();
        let columns: Box<[Sqlx4kSqliteCipherColumn]> = columns.into_boxed_slice();
        let columns: &mut [Sqlx4kSqliteCipherColumn] = Box::leak(columns);
        let columns: *mut Sqlx4kSqliteCipherColumn = columns.as_mut_ptr();

        Sqlx4kSqliteCipherRow {
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
        free_result(result_of(Ok(vec![])).leak());
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::test_support::{block_on, c, memory, Res};
    use crate::core::types::{ERROR_POOL_CLOSED, OK};
    use sqlx::{Connection, Executor};
    use std::ptr::null;

    #[test]
    fn a_c_string_reads_as_str() {
        let s = c("héllo");
        assert_eq!(c_chars_to_str(s.as_ptr()), "héllo");
    }

    #[test]
    fn a_null_pointer_reads_as_an_empty_slice_or_vec() {
        assert!(unsafe { slice_of::<u8>(null(), 0) }.is_empty());
        assert!(unsafe { slice_of::<u8>(null(), 5) }.is_empty());
        assert!(unsafe { vec_of::<u8>(null_mut(), 0) }.is_empty());
        let bytes = [1u8, 2, 3];
        assert_eq!(unsafe { slice_of(bytes.as_ptr(), 2) }, &[1, 2]);
    }

    #[test]
    fn freeing_a_null_result_does_nothing() {
        free_result(null_mut());
    }

    #[test]
    fn no_rows_make_an_empty_result() {
        let r = Res(result_of(Ok(vec![])).leak());
        assert_eq!((r.get().error, r.get().size), (OK, 0));
        assert!(r.columns().is_empty());
    }

    #[test]
    fn an_error_makes_an_error_result() {
        let r = Res(result_of(Err(sqlx::Error::PoolClosed)).leak());
        assert_eq!((r.get().error, r.get().size), (ERROR_POOL_CLOSED, 0));
    }

    fn fetched(sql: &str) -> Res {
        block_on(async {
            let mut cn = memory().await;
            let rows = cn.fetch_all(sqlx::AssertSqlSafe(sql.to_string())).await;
            cn.close().await.unwrap();
            Res(result_of(rows).leak())
        })
    }

    #[test]
    fn rows_are_read_with_their_schema_and_values_by_length() {
        let r = fetched("select 1 as a, null as b, 'x' || char(0) || 'y' as c, 1.5 as d, x'01ff' as e union all select 2, 3, '', 1e300, x''").ok();
        let columns = r.columns();
        let columns: Vec<(&str, &str)> = columns.iter().map(|(n, k)| (n.as_str(), k.as_str())).collect();
        // The types of the first row's values.
        assert_eq!(columns, [("a", "INTEGER"), ("b", "NULL"), ("c", "TEXT"), ("d", "REAL"), ("e", "BLOB")]);
        // The NUL inside a value is kept, a REAL is written as SQLite writes it, a BLOB as hex.
        assert_eq!(
            r.rows(),
            [
                [Some("1".into()), None, Some("x\0y".into()), Some("1.5".into()), Some("01ff".into())],
                [Some("2".into()), Some("3".into()), Some("".into()), Some("1.0e+300".into()), Some("".into())],
            ]
        );
    }

    #[test]
    fn invalid_utf8_text_is_replaced() {
        let r = fetched("select cast(x'61ff62' as text)").ok();
        assert_eq!(r.value().as_deref(), Some("a\u{fffd}b"));
    }

    #[test]
    fn a_failed_query_makes_an_error_result() {
        let r = fetched("select * from missing_table");
        assert!(!r.is_ok());
        assert!(r.error_message().unwrap().contains("no such table"));
    }
}
