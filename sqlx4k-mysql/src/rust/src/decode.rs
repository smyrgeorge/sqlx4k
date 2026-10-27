//! Decoding a column value into the text Kotlin reads, by its type (text and binary protocols).

use sqlx::mysql::{MySqlRow, MySqlTypeInfo};
// Re-export sqlx's chrono feature shim so we don't need a direct dependency on
// the chrono crate — sqlx already pulls it in when the matching feature is on.
use sqlx::types::chrono;
use sqlx::{MySql, Row, Type, TypeInfo, ValueRef};
use std::ffi::{c_char, c_int};
use std::ptr::null_mut;

/// The column's type name, as reported in the result metadata.
///
/// MySQL also flags text with a binary collation (e.g. `utf8mb4_bin`, which `->>` / JSON_UNQUOTE returns) as
/// BINARY, so sqlx names it like a blob (`LONGBLOB`, `VARBINARY`, ...). Only the `binary` collation means raw
/// bytes: that is sqlx's own check for `&str`, so such text is reported under its text name instead (as the
/// JVM driver does).
pub(crate) fn mysql_type_name(info: &MySqlTypeInfo) -> &str {
    let name = info.name();
    if !<&str as Type<MySql>>::compatible(info) {
        return name;
    }
    match name {
        "BINARY" => "CHAR",
        "VARBINARY" => "VARCHAR",
        "TINYBLOB" => "TINYTEXT",
        "BLOB" => "TEXT",
        "MEDIUMBLOB" => "MEDIUMTEXT",
        "LONGBLOB" => "LONGTEXT",
        _ => name,
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
pub(crate) fn decode_mysql_column_value(row: &MySqlRow, ordinal: usize) -> (*mut c_char, c_int) {
    let (type_name, unsigned): (String, bool) = {
        let value_ref = row.try_get_raw(ordinal).unwrap();
        if value_ref.is_null() {
            return (null_mut(), 0);
        }
        let info = value_ref.type_info();
        // The UNSIGNED flag is private to sqlx; the unsigned integers' compatibility checks it.
        (mysql_type_name(&info).to_string(), <u8 as Type<MySql>>::compatible(&info))
    };

    let s: String = match type_name.as_str() {
        // sqlx-mysql reports `TINYINT(1)` (what `BOOLEAN`/`BOOL` creates) as
        // "BOOLEAN" with `display = 1` rather than the bare "TINYINT" name, also when it is
        // unsigned: its values then reach 255, which an i8 cannot hold (decoding it panicked, aborting
        // the process, or wrapped around to a negative number).
        "BOOLEAN" if unsigned => row.get_unchecked::<u8, _>(ordinal).to_string(),
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
        // Only the `binary` collation reaches here: `mysql_type_name` renames binary-collated text.
        "BLOB" | "TINYBLOB" | "MEDIUMBLOB" | "LONGBLOB" | "VARBINARY" | "BINARY" => {
            let bytes: Vec<u8> = row.get_unchecked(ordinal);
            hex::encode(bytes)
        }
        // Temporal types arrive as packed binary in the prepared-statement
        // protocol; format them to `yyyy-MM-dd HH:mm:ss[.uuuuuu]` so the existing
        // asInstant / asLocalDate* decoders round-trip. See decode_mysql_temporal.
        "DATE" => decode_mysql_temporal(row, ordinal, |d: chrono::NaiveDate| d.to_string(), decode_mysql_date_binary),
        "TIME" => decode_mysql_temporal(row, ordinal, |t: chrono::NaiveTime| t.to_string(), decode_mysql_time_binary),
        "DATETIME" | "TIMESTAMP" => decode_mysql_temporal(
            row,
            ordinal,
            |dt: chrono::NaiveDateTime| dt.format("%Y-%m-%d %H:%M:%S%.6f").to_string(),
            decode_mysql_datetime_binary,
        ),
        // YEAR arrives as text in the text protocol, as a 2-byte integer in the binary protocol (read as text, it
        // was raw bytes). BIT arrives as its bytes, most significant first, in either protocol: it is read as the
        // number it holds (`1` for b'1'), which the Kotlin decoders read (asBoolean, asLong), rather than as the
        // control characters its bytes were as text.
        "YEAR" | "BIT" => match row.try_get_unchecked::<u64, _>(ordinal) {
            Ok(value) => value.to_string(),
            Err(_) => String::new(),
        },
        // VARCHAR / CHAR / TEXT (all varieties) / JSON / ENUM / SET
        // and anything else fall through to `&str`. In the text protocol they
        // arrive as text bytes; for text-typed columns this also works in the
        // binary protocol. Decode leniently so that a binary-only type we don't
        // model can never abort the host process.
        _ => match row.try_get_unchecked::<Vec<u8>, _>(ordinal) {
            Ok(bytes) => String::from_utf8_lossy(&bytes).into_owned(),
            Err(_) => String::new(),
        },
    };
    column_value_of(s)
}

/// Hands a value over with its length: a MySQL text value may hold NUL characters, at which a C string would end
/// (they used to be dropped). It stays NUL-terminated as well; `sqlx4k_mysql_free_result` frees it.
pub(crate) fn column_value_of(value: String) -> (*mut c_char, c_int) {
    let mut bytes = value.into_bytes();
    let len = bytes.len() as c_int;
    bytes.push(0);
    (Box::into_raw(bytes.into_boxed_slice()) as *mut u8 as *mut c_char, len)
}

/// Decodes a DATE / TIME / DATETIME / TIMESTAMP value without panicking on the values chrono cannot represent:
/// zero dates ('0000-00-00'), TIME beyond 24 hours or negative ('838:59:59', '-01:30:00'). `get_unchecked`
/// unwrapped chrono's error, and with `panic = "abort"` a single such value took the host process down. Binary values
/// are rendered from the wire format by `binary`; text values are decoded by chrono and passed through as MySQL
/// prints them when chrono cannot represent them.
pub(crate) fn decode_mysql_temporal<'r, T: sqlx::Decode<'r, MySql>>(
    row: &'r MySqlRow,
    ordinal: usize,
    render: impl FnOnce(T) -> String,
    binary: fn(&[u8]) -> Option<String>,
) -> String {
    let Ok(bytes) = row.try_get_unchecked::<Vec<u8>, _>(ordinal) else { return String::new() };
    // The text protocol sends the value as MySQL prints it, the binary protocol (prepared statements) as a length
    // byte (0..=12) followed by packed fields. sqlx keeps the format private, but the first byte tells them apart:
    // text always starts with a digit or '-'.
    if !matches!(bytes.first(), Some(b'0'..=b'9' | b'-')) {
        return binary(&bytes).unwrap_or_default();
    }
    match row.try_get_unchecked::<T, _>(ordinal) {
        Ok(v) => render(v),
        Err(_) => String::from_utf8_lossy(&bytes).into_owned(),
    }
}

/// Binary DATE / DATETIME / TIMESTAMP: a length byte (0, 4, 7 or 11), then year (u16 LE), month, day, hour, minute,
/// second (u8) and microseconds (u32 LE); trailing fields are omitted when zero (all of them for a zero date).
pub(crate) fn mysql_datetime_fields(bytes: &[u8]) -> Option<(u16, u8, u8, u8, u8, u8, u32)> {
    let (&len, rest) = bytes.split_first()?;
    let rest = rest.get(..len as usize)?;
    let byte = |i: usize| rest.get(i).copied().unwrap_or(0);
    let year = rest.get(0..2).map_or(0, |b| u16::from_le_bytes([b[0], b[1]]));
    let micros = match rest.get(7..11) {
        Some(b) => u32::from_le_bytes(b.try_into().ok()?),
        None => 0,
    };
    Some((year, byte(2), byte(3), byte(4), byte(5), byte(6), micros))
}

pub(crate) fn decode_mysql_date_binary(bytes: &[u8]) -> Option<String> {
    let (year, month, day, ..) = mysql_datetime_fields(bytes)?;
    Some(format!("{year:04}-{month:02}-{day:02}"))
}

pub(crate) fn decode_mysql_datetime_binary(bytes: &[u8]) -> Option<String> {
    let (year, month, day, hour, minute, second, micros) = mysql_datetime_fields(bytes)?;
    Some(format!("{year:04}-{month:02}-{day:02} {hour:02}:{minute:02}:{second:02}.{micros:06}"))
}

/// Binary TIME: a length byte (0, 8 or 12), then sign (u8, 1 when negative), days (u32 LE), hours, minutes, seconds
/// (u8) and microseconds (u32 LE). Rendered like MySQL's text form, `[-]HH:MM:SS[.ffffff]` with hours past 24.
pub(crate) fn decode_mysql_time_binary(bytes: &[u8]) -> Option<String> {
    let (&len, rest) = bytes.split_first()?;
    if len == 0 {
        return Some("00:00:00".to_string());
    }
    let rest = rest.get(..len as usize)?;
    if rest.len() < 8 {
        return None;
    }
    let sign = if rest[0] == 1 { "-" } else { "" };
    let days = u32::from_le_bytes(rest[1..5].try_into().ok()?);
    let hours = days as u64 * 24 + rest[5] as u64;
    let micros = match rest.get(8..12) {
        Some(b) => u32::from_le_bytes(b.try_into().ok()?),
        None => 0,
    };
    let hms = format!("{sign}{hours:02}:{:02}:{:02}", rest[6], rest[7]);
    Some(if micros == 0 { hms } else { format!("{hms}.{micros:06}") })
}
