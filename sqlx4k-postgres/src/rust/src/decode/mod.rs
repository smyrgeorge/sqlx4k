//! Decoding a column value into the text Kotlin reads, by its type.

pub(crate) mod binary;
pub(crate) mod text;

use sqlx::postgres::{PgRow, PgValueFormat};
// Re-export sqlx's chrono/uuid feature shims so we don't need direct dependencies
// on those crates — sqlx already pulls them in when the matching features are on.
use sqlx::types::{chrono, uuid};
use sqlx::{Postgres, Row, Type, TypeInfo, ValueRef};
use std::ffi::{c_char, CString};
use std::ptr::null_mut;

use crate::decode::binary::{
    decode_pg_array_binary, decode_pg_binary, decode_pg_date_binary,
    decode_pg_numeric_array_binary, decode_pg_numeric_binary, decode_pg_time_binary,
    decode_pg_timestamp_binary, decode_unknown_binary,
};
use crate::decode::text::{
    decode_bytea_escape, float_text, format_pg_array_fmt, format_pg_nullable_array_fmt,
    pg_array_element,
};

/// Decodes a single Postgres column value into a heap-allocated C string.
///
/// `row.get_unchecked::<&str>` only works reliably when the result is in **text**
/// format (the simple-query protocol used by `pool.execute(sql)`). The `_with_params`
/// path goes through sqlx's prepared/extended protocol, which returns column values
/// in **binary** format — `&str` decode then either fails on non-UTF8 bytes or
/// silently produces strings full of control characters / embedded NULs that crash
/// `CString::new`. Decode by type so both protocols round-trip identically through
/// the existing Kotlin `as*` extensions, which expect text-shaped values.
pub(crate) fn decode_postgresql_column_value(row: &PgRow, ordinal: usize) -> *mut c_char {
    let type_name: String = {
        let value_ref = row.try_get_raw(ordinal).unwrap();
        if value_ref.is_null() {
            return null_mut();
        }
        value_ref.type_info().name().to_string()
    };

    let s: String = match type_name.as_str() {
        "INT2" => row.get_unchecked::<i16, _>(ordinal).to_string(),
        "INT4" => row.get_unchecked::<i32, _>(ordinal).to_string(),
        "INT8" => row.get_unchecked::<i64, _>(ordinal).to_string(),
        "FLOAT4" => float_text(row.get_unchecked::<f32, _>(ordinal)),
        "FLOAT8" => float_text(row.get_unchecked::<f64, _>(ordinal)),
        "BOOL" => row.get_unchecked::<bool, _>(ordinal).to_string(),
        // Match the bytea text-input format `\xHH..` so the existing
        // `asByteArray()` decoder (which strips `\x` and parses hex) round-trips.
        // Use `Vec<u8>` not `&[u8]` — sqlx-rs only supports `&[u8]` decode in
        // the prepared-statement protocol, while simple-query results require
        // an owned buffer. sqlx reads only the hex text form: with `bytea_output = 'escape'` (a session, role or
        // database setting) the value is decoded here instead of failing, which would abort the process.
        "BYTEA" => decode_or(
            row,
            ordinal,
            |bytes: Vec<u8>| format!("\\x{}", hex::encode(bytes)),
            || {
                let text = decode_text_or_binary(row, ordinal, |_| None);
                decode_bytea_escape(&text).map_or(text, |bytes| format!("\\x{}", hex::encode(bytes)))
            },
        ),
        "UUID" => row.get_unchecked::<uuid::Uuid, _>(ordinal).to_string(),
        // Dates, times and timestamps have values chrono cannot represent ('infinity', '24:00:00'): see decode_datetime.
        "DATE" => decode_datetime(row, ordinal, |d: chrono::NaiveDate| d.to_string(), decode_pg_date_binary),
        "TIME" => decode_datetime(row, ordinal, |t: chrono::NaiveTime| t.to_string(), decode_pg_time_binary),
        "TIMESTAMP" => decode_datetime(
            row,
            ordinal,
            |dt: chrono::NaiveDateTime| dt.format("%Y-%m-%d %H:%M:%S%.6f").to_string(),
            decode_pg_timestamp_binary,
        ),
        "TIMESTAMPTZ" => decode_datetime(
            row,
            ordinal,
            |dt: chrono::DateTime<chrono::Utc>| dt.format("%Y-%m-%d %H:%M:%S%.6f").to_string(),
            decode_pg_timestamp_binary,
        ),
        // Array types — render with postgres' `{a,b,c}` text form so the existing
        // `asString()` path round-trips. Element formatting mirrors `T::to_string()`,
        // so this only handles arrays of element types we can decode here. Elements may
        // be NULL (rendered `NULL`, as postgres does); an array sqlx cannot decode (a lower
        // bound other than 1, an infinite timestamp, ...) falls back as in decode_or.
        "BOOL[]" => decode_or(
            row,
            ordinal,
            // Postgres' canonical text encoding for bool arrays is `{t,f}` and
            // sqlx4k's `asBooleanArray()` parses that form (via `asBoolean`).
            |v: Vec<Option<bool>>| {
                format_pg_array_fmt(v.iter(), |b| match b {
                    Some(true) => "t".to_string(),
                    Some(false) => "f".to_string(),
                    None => "NULL".to_string(),
                })
            },
            || decode_any(row, ordinal),
        ),
        "INT2[]" => decode_array::<i16>(row, ordinal),
        "INT4[]" => decode_array::<i32>(row, ordinal),
        "INT8[]" => decode_array::<i64>(row, ordinal),
        "FLOAT4[]" => decode_float_array::<f32>(row, ordinal),
        "FLOAT8[]" => decode_float_array::<f64>(row, ordinal),
        // Text-like array types, quoted as PostgreSQL writes them (`{a,"b,c"}`).
        "TEXT[]" | "VARCHAR[]" | "BPCHAR[]" | "NAME[]" => decode_text_array(row, ordinal),
        "UUID[]" => decode_array::<uuid::Uuid>(row, ordinal),
        "TIMESTAMP[]" => decode_datetime(
            row,
            ordinal,
            |v: Vec<Option<chrono::NaiveDateTime>>| {
                format_pg_nullable_array_fmt(v.iter(), |dt| dt.format("%Y-%m-%d %H:%M:%S%.6f").to_string())
            },
            |bytes| decode_pg_array_binary(bytes, &decode_pg_timestamp_binary),
        ),
        "TIMESTAMPTZ[]" => decode_datetime(
            row,
            ordinal,
            |v: Vec<Option<chrono::DateTime<chrono::Utc>>>| {
                format_pg_nullable_array_fmt(v.iter(), |dt| dt.format("%Y-%m-%d %H:%M:%S%.6f").to_string())
            },
            |bytes| decode_pg_array_binary(bytes, &decode_pg_timestamp_binary),
        ),
        "BYTEA[]" => decode_or(
            row,
            ordinal,
            // Each element gets the bytea text form (`\xHH..`) so a downstream
            // `asStringArray().map { it.asByteArray() }` round-trips per element.
            |v: Vec<Option<Vec<u8>>>| format_pg_nullable_array_fmt(v.iter(), |b| format!("\\x{}", hex::encode(b))),
            || decode_any(row, ordinal),
        ),
        // JSONB's binary wire format is a 1-byte version header (0x01) followed by the
        // canonical JSON text. Decoding as `&str` (the fallthrough below) would keep that
        // header byte, corrupting JSON parsing on the Kotlin side (e.g. `?{...}`), so strip
        // it here. In the simple-query / text protocol there is no header, so the strip is a
        // no-op. (Plain `JSON` is stored/sent as text and needs no adjustment.)
        "JSONB" => {
            let s = row.get_unchecked::<&str, _>(ordinal);
            s.strip_prefix('\u{1}').unwrap_or(s).to_string()
        }
        // NUMERIC arrives in binary form through the extended/prepared protocol
        // (every `_with_params` call, and aggregates such as SUM(bigint)/AVG(..)
        // return NUMERIC). Decoding it as `&str` used to panic and abort the host
        // process; decode the wire format into its canonical decimal text instead.
        "NUMERIC" => decode_text_or_binary(row, ordinal, decode_pg_numeric_binary),
        // NUMERIC[] has the same problem: its binary form is an array of binary NUMERICs.
        // Render it as the `{1.5,NULL,NaN}` text PostgreSQL prints for the simple protocol.
        "NUMERIC[]" => decode_text_or_binary(row, ordinal, decode_pg_numeric_array_binary),
        // TEXT/VARCHAR/CHAR/NAME/JSON and every other type: in the simple-query / text protocol every value
        // arrives as text; in the extended protocol a binary value is decoded by its type (see decode_pg_binary).
        _ => decode_any(row, ordinal),
    };
    // A value may legally contain NUL (e.g. text in a non-UTF8-safe column after
    // lossy decoding); CString::new would fail on it. Strip rather than abort.
    let s = if s.as_bytes().contains(&0) { s.replace('\0', "") } else { s };
    CString::new(s).unwrap_or_default().into_raw()
}

/// Decodes the value as `T` and renders it with `render`. A value `T` cannot represent ('infinity', '24:00:00', an
/// array whose lower bound is not 1, ...) is rendered by `fallback` instead: `get_unchecked` would unwrap the decode
/// error, and with `panic = "abort"` a single such value took the host process down.
pub(crate) fn decode_or<'r, T: sqlx::Decode<'r, Postgres>>(
    row: &'r PgRow,
    ordinal: usize,
    render: impl FnOnce(T) -> String,
    fallback: impl FnOnce() -> String,
) -> String {
    match row.try_get_unchecked::<T, _>(ordinal) {
        Ok(value) => render(value),
        Err(_) => fallback(),
    }
}

pub(crate) fn decode_text_array(row: &PgRow, ordinal: usize) -> String {
    // A text value (a plain query) is PostgreSQL's own output, already in this form; sqlx's parser of it reads the
    // string "NULL" (quoted) as a NULL element.
    if let Ok(value) = row.try_get_raw(ordinal) {
        if value.format() == PgValueFormat::Text {
            return value.as_str().map(str::to_string).unwrap_or_default();
        }
    }
    decode_or(
        row,
        ordinal,
        |v: Vec<Option<String>>| format_pg_nullable_array_fmt(v.iter(), |e| pg_array_element(e)),
        || decode_any(row, ordinal),
    )
}

pub(crate) fn decode_float_array<T>(row: &PgRow, ordinal: usize) -> String
where
    T: ToString + for<'r> sqlx::Decode<'r, Postgres> + Type<Postgres>,
{
    decode_or(
        row,
        ordinal,
        |v: Vec<Option<T>>| format_pg_nullable_array_fmt(v.iter(), |e| float_text(e.to_string())),
        || decode_any(row, ordinal),
    )
}

/// Decodes an array of `T` (elements may be NULL) into postgres' `{a,NULL,c}` text form; see decode_or.
pub(crate) fn decode_array<T>(row: &PgRow, ordinal: usize) -> String
where
    T: ToString + for<'r> sqlx::Decode<'r, Postgres> + Type<Postgres>,
{
    decode_or(
        row,
        ordinal,
        |v: Vec<Option<T>>| format_pg_nullable_array_fmt(v.iter(), |e| e.to_string()),
        || decode_any(row, ordinal),
    )
}

/// Decodes a date/time value (or an array of them) without sqlx's binary decoders, which panic (overflowing chrono)
/// on values chrono cannot represent, such as 'infinity': a binary value is decoded by `binary`, with checked
/// arithmetic; a text value by sqlx, whose text parsing fails cleanly, and is passed through as is when it cannot be
/// represented ('infinity', '24:00:00').
pub(crate) fn decode_datetime<'r, T: sqlx::Decode<'r, Postgres>>(
    row: &'r PgRow,
    ordinal: usize,
    render: impl FnOnce(T) -> String,
    binary: fn(&[u8]) -> Option<String>,
) -> String {
    let Ok(value) = row.try_get_raw(ordinal) else { return String::new() };
    match value.format() {
        PgValueFormat::Binary => value.as_bytes().ok().and_then(binary).unwrap_or_default(),
        PgValueFormat::Text => decode_or(row, ordinal, render, || value.as_str().map(str::to_string).unwrap_or_default()),
    }
}

pub(crate) fn decode_text_or_binary(row: &PgRow, ordinal: usize, decode_binary: fn(&[u8]) -> Option<String>) -> String {
    let Ok(value) = row.try_get_raw(ordinal) else { return String::new() };
    match value.format() {
        PgValueFormat::Text => value.as_str().map(str::to_string).unwrap_or_default(),
        PgValueFormat::Binary => value.as_bytes().ok().and_then(decode_binary).unwrap_or_default(),
    }
}

/// Decodes a value of any type, never panicking: a text value as is (UTF-8 when valid, lossy otherwise), a binary
/// one by its type (see decode_pg_binary).
pub(crate) fn decode_any(row: &PgRow, ordinal: usize) -> String {
    let Ok(value) = row.try_get_raw(ordinal) else { return String::new() };
    let Ok(bytes) = value.as_bytes() else { return String::new() };
    match value.format() {
        PgValueFormat::Text => String::from_utf8_lossy(bytes).into_owned(),
        PgValueFormat::Binary => {
            decode_pg_binary(&value.type_info(), bytes).unwrap_or_else(|| decode_unknown_binary(bytes))
        }
    }
}
