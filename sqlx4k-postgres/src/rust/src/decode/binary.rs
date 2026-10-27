//! Decoding values of PostgreSQL's binary format (prepared statements' results).

use sqlx::postgres::{PgTypeInfo, PgTypeKind};
// Re-export sqlx's chrono/uuid feature shims so we don't need direct dependencies
// on those crates — sqlx already pulls them in when the matching features are on.
use sqlx::types::{chrono, uuid};
use sqlx::TypeInfo;

use crate::decode::text::{
    float_text, pg_array_element, pg_range_bound, pg_seconds, trim_pg_fraction,
};

/// Days from 0001-01-01 (chrono's `num_days_from_ce` day 1) to 2000-01-01, the epoch of the binary DATE format.
pub(crate) const PG_EPOCH_DAYS_FROM_CE: i32 = 730_120;

/// Microseconds from 1970-01-01 to 2000-01-01, the epoch of the binary TIMESTAMP format.
pub(crate) const PG_EPOCH_UNIX_MICROS: i64 = 946_684_800_000_000;

/// Binary DATE (days since 2000-01-01, `i32::MAX` / `i32::MIN` for `±infinity`).
pub(crate) fn decode_pg_date_binary(bytes: &[u8]) -> Option<String> {
    let days = i32::from_be_bytes(bytes.try_into().ok()?);
    match days {
        i32::MAX => Some("infinity".to_string()),
        i32::MIN => Some("-infinity".to_string()),
        _ => Some(chrono::NaiveDate::from_num_days_from_ce_opt(days.checked_add(PG_EPOCH_DAYS_FROM_CE)?)?.to_string()),
    }
}

/// Binary TIMESTAMP / TIMESTAMPTZ (microseconds since 2000-01-01 UTC, `i64::MAX` / `i64::MIN` for `±infinity`).
pub(crate) fn decode_pg_timestamp_binary(bytes: &[u8]) -> Option<String> {
    let micros = i64::from_be_bytes(bytes.try_into().ok()?);
    match micros {
        i64::MAX => Some("infinity".to_string()),
        i64::MIN => Some("-infinity".to_string()),
        _ => {
            let dt = chrono::DateTime::from_timestamp_micros(micros.checked_add(PG_EPOCH_UNIX_MICROS)?)?;
            Some(dt.format("%Y-%m-%d %H:%M:%S%.6f").to_string())
        }
    }
}

/// Binary TIME (microseconds since midnight, up to and including 24:00:00, which chrono cannot represent).
pub(crate) fn decode_pg_time_binary(bytes: &[u8]) -> Option<String> {
    let micros = i64::from_be_bytes(bytes.try_into().ok()?);
    if !(0..=86_400_000_000).contains(&micros) {
        return None;
    }
    let (secs, frac) = (micros / 1_000_000, micros % 1_000_000);
    let hms = format!("{:02}:{:02}:{:02}", secs / 3600, secs % 3600 / 60, secs % 60);
    Some(if frac == 0 { hms } else { format!("{hms}.{frac:06}") })
}

/// Decodes a binary NUMERIC[] (`array_send`):
/// `ndim: i32, has_nulls: i32, elemtype: u32, [len: i32, lbound: i32; ndim]`, followed by the
/// elements in row-major order, each as `len: i32` (-1 for NULL) and `len` bytes of `numeric_send`.
/// Renders the canonical text form (`{1.5,NULL}`, `{{1,2},{3,4}}`, `[0:1]={1,2}`).
pub(crate) fn decode_pg_numeric_array_binary(bytes: &[u8]) -> Option<String> {
    decode_pg_array_binary(bytes, &decode_pg_numeric_binary)
}

/// Decodes a binary array (`array_send`, see decode_pg_numeric_array_binary) whose elements are decoded by
/// `decode_element`, into the canonical text form.
pub(crate) fn decode_pg_array_binary(bytes: &[u8], decode_element: &dyn Fn(&[u8]) -> Option<String>) -> Option<String> {
    let read_i32 = |i: usize| -> Option<i32> { Some(i32::from_be_bytes(bytes.get(i..i + 4)?.try_into().ok()?)) };
    let ndim = read_i32(0)?;
    if ndim < 0 {
        return None;
    }
    if ndim == 0 {
        return Some("{}".to_string());
    }
    let mut dims = Vec::with_capacity(ndim as usize);
    let mut bounds = String::new();
    let mut default_bounds = true;
    let mut pos = 12;
    for _ in 0..ndim {
        let (len, lbound) = (read_i32(pos)?, read_i32(pos + 4)?);
        if len < 0 {
            return None;
        }
        dims.push(len as usize);
        default_bounds &= lbound == 1;
        bounds.push_str(&format!("[{}:{}]", lbound, lbound as i64 + len as i64 - 1));
        pos += 8;
    }
    // Like array_out, spell the bounds out only when some dimension does not start at 1.
    let mut out = if default_bounds { String::new() } else { bounds + "=" };
    write_pg_array_level(bytes, &mut pos, &dims, decode_element, &mut out)?;
    Some(out)
}

pub(crate) fn write_pg_array_level(
    bytes: &[u8],
    pos: &mut usize,
    dims: &[usize],
    decode_element: &dyn Fn(&[u8]) -> Option<String>,
    out: &mut String,
) -> Option<()> {
    out.push('{');
    for i in 0..dims[0] {
        if i > 0 {
            out.push(',');
        }
        if dims.len() > 1 {
            write_pg_array_level(bytes, pos, &dims[1..], decode_element, out)?;
            continue;
        }
        let len = i32::from_be_bytes(bytes.get(*pos..*pos + 4)?.try_into().ok()?);
        *pos += 4;
        if len < 0 {
            out.push_str("NULL");
            continue;
        }
        let element = bytes.get(*pos..*pos + len as usize)?;
        *pos += len as usize;
        out.push_str(&decode_element(element)?);
    }
    out.push('}');
    Some(())
}

/// Decodes the PostgreSQL NUMERIC binary representation (`numeric_send`):
/// `ndigits: i16, weight: i16, sign: u16, dscale: u16, digits: [u16; ndigits]`,
/// where each digit is a base-10000 group and `weight` is the power of 10000 of
/// the first group. Returns `None` on malformed input.
pub(crate) fn decode_pg_numeric_binary(bytes: &[u8]) -> Option<String> {
    if bytes.len() < 8 {
        return None;
    }
    let read_u16 = |i: usize| u16::from_be_bytes([bytes[i], bytes[i + 1]]);
    let ndigits = read_u16(0) as i16;
    let weight = read_u16(2) as i16 as i32;
    let sign = read_u16(4);
    let dscale = read_u16(6) as usize;
    match sign {
        0xC000 => return Some("NaN".to_string()),
        0xD000 => return Some("Infinity".to_string()),
        0xF000 => return Some("-Infinity".to_string()),
        0x0000 | 0x4000 => {}
        _ => return None,
    }
    if ndigits < 0 || bytes.len() < 8 + ndigits as usize * 2 {
        return None;
    }
    let digits: Vec<u16> = (0..ndigits as usize).map(|i| read_u16(8 + i * 2)).collect();
    if digits.iter().any(|d| *d >= 10000) {
        return None;
    }
    // Integer part: groups with power >= 0 (weight, weight-1, ..., 0).
    let mut int_part = String::new();
    if weight >= 0 {
        for i in 0..=weight {
            let d = digits.get(i as usize).copied().unwrap_or(0);
            if int_part.is_empty() {
                if d != 0 {
                    int_part.push_str(&d.to_string());
                }
            } else {
                int_part.push_str(&format!("{d:04}"));
            }
        }
    }
    if int_part.is_empty() {
        int_part.push('0');
    }
    // Fraction part: groups with power < 0, padded/truncated to dscale digits.
    let mut frac = String::new();
    if dscale > 0 {
        let mut power = -1;
        while frac.len() < dscale {
            // digit i carries power (weight - i), so the group with this power is i = weight - power
            let group_index = weight - power;
            let d = if group_index >= 0 { digits.get(group_index as usize).copied().unwrap_or(0) } else { 0 };
            frac.push_str(&format!("{d:04}"));
            power -= 1;
        }
        frac.truncate(dscale);
    }
    let mut out = String::new();
    if sign == 0x4000 && (int_part != "0" || frac.chars().any(|c| c != '0')) {
        out.push('-');
    }
    out.push_str(&int_part);
    if dscale > 0 {
        out.push('.');
        out.push_str(&frac);
    }
    Some(out)
}

/// Decodes a binary value into the text PostgreSQL writes for it. sqlx asks for binary results of every prepared
/// statement, so a type not decoded by decode_postgresql_column_value arrives in binary there: its raw bytes read as
/// text were control characters (`interval '1 day'` read `"\u{1}"`), and an array sqlx cannot decode (of more than
/// one dimension, or not starting at 1) read as an empty string. `None` for a type not known here, or a malformed
/// value.
pub(crate) fn decode_pg_binary(info: &PgTypeInfo, bytes: &[u8]) -> Option<String> {
    match info.kind() {
        // Each element quoted as array_out writes it.
        PgTypeKind::Array(element) => {
            decode_pg_array_binary(bytes, &|e| decode_pg_binary(element, e).map(|e| pg_array_element(&e)))
        }
        PgTypeKind::Range(element) => decode_pg_range_binary(bytes, element),
        PgTypeKind::Domain(base) => decode_pg_binary(base, bytes),
        // An enum is sent as its label.
        PgTypeKind::Enum(_) => Some(String::from_utf8_lossy(bytes).into_owned()),
        _ => decode_pg_scalar_binary(info.name(), bytes),
    }
}

/// A binary value of a type not known here: its bytes as text when they read as text (a type sent as text, such as
/// citext), else in bytea's hex form (`\xHH..`), rather than control characters.
pub(crate) fn decode_unknown_binary(bytes: &[u8]) -> String {
    match std::str::from_utf8(bytes) {
        Ok(text) if !text.chars().any(|c| c.is_control() && !matches!(c, '\t' | '\n' | '\r')) => text.to_string(),
        _ => format!("\\x{}", hex::encode(bytes)),
    }
}

/// Decodes a binary value of a scalar type by its name: sqlx's (`INT4`) for a type it knows, PostgreSQL's (`regclass`)
/// for one it looked up.
pub(crate) fn decode_pg_scalar_binary(name: &str, bytes: &[u8]) -> Option<String> {
    fn be<const N: usize>(bytes: &[u8]) -> Option<[u8; N]> {
        bytes.try_into().ok()
    }
    Some(match name.to_ascii_lowercase().as_str() {
        "int2" => i16::from_be_bytes(be(bytes)?).to_string(),
        "int4" => i32::from_be_bytes(be(bytes)?).to_string(),
        "int8" => i64::from_be_bytes(be(bytes)?).to_string(),
        // Sent as their OID number (their text form is the object's name, which only the server can look up).
        "oid" | "xid" | "cid" | "regclass" | "regproc" | "regprocedure" | "regoper" | "regoperator" | "regtype"
        | "regnamespace" | "regrole" | "regconfig" | "regdictionary" | "regcollation" => {
            u32::from_be_bytes(be(bytes)?).to_string()
        }
        "xid8" => u64::from_be_bytes(be(bytes)?).to_string(),
        "float4" => float_text(f32::from_be_bytes(be(bytes)?)),
        "float8" => float_text(f64::from_be_bytes(be(bytes)?)),
        "bool" => (if *bytes.first()? != 0 { "t" } else { "f" }).to_string(),
        "bytea" => format!("\\x{}", hex::encode(bytes)),
        "uuid" => uuid::Uuid::from_slice(bytes).ok()?.to_string(),
        "date" => return decode_pg_date_binary(bytes),
        // Their fractional seconds without trailing zeros, as PostgreSQL writes them.
        "time" => return decode_pg_time_binary(bytes).map(trim_pg_fraction),
        "timestamp" | "timestamptz" => return decode_pg_timestamp_binary(bytes).map(trim_pg_fraction),
        "timetz" => return decode_pg_timetz_binary(bytes),
        "interval" => return decode_pg_interval_binary(bytes),
        "numeric" => return decode_pg_numeric_binary(bytes),
        // Its text form depends on lc_monetary (`$1,234.56`): written as the plain amount, with two decimals.
        "money" => {
            let cents = i64::from_be_bytes(be(bytes)?) as i128;
            let sign = if cents < 0 { "-" } else { "" };
            format!("{sign}{}.{:02}", cents.abs() / 100, cents.abs() % 100)
        }
        "inet" | "cidr" => return decode_pg_inet_binary(bytes),
        "macaddr" | "macaddr8" => bytes.iter().map(|b| format!("{b:02x}")).collect::<Vec<_>>().join(":"),
        "bit" | "varbit" => return decode_pg_bit_binary(bytes),
        "point" => format!(
            "({},{})",
            float_text(f64::from_be_bytes(be(bytes.get(..8)?)?)),
            float_text(f64::from_be_bytes(be(bytes.get(8..)?)?))
        ),
        // A version byte, then the text.
        "jsonb" | "jsonpath" => String::from_utf8_lossy(bytes.strip_prefix(&[1]).unwrap_or(bytes)).into_owned(),
        "text" | "varchar" | "char" | "bpchar" | "\"char\"" | "name" | "json" | "xml" | "unknown" | "citext" => {
            String::from_utf8_lossy(bytes).into_owned()
        }
        _ => return None,
    })
}

/// Binary INTERVAL (`time: i64` microseconds, `day: i32`, `month: i32`), written in the default IntervalStyle
/// (`postgres`): `1 year 2 mons -3 days +04:05:06.5`.
pub(crate) fn decode_pg_interval_binary(bytes: &[u8]) -> Option<String> {
    let time = i64::from_be_bytes(bytes.get(..8)?.try_into().ok()?);
    let day = i32::from_be_bytes(bytes.get(8..12)?.try_into().ok()?);
    let month = i32::from_be_bytes(bytes.get(12..16)?.try_into().ok()?);
    if bytes.len() != 16 {
        return None;
    }
    match (time, day, month) {
        (i64::MAX, i32::MAX, i32::MAX) => return Some("infinity".to_string()),
        (i64::MIN, i32::MIN, i32::MIN) => return Some("-infinity".to_string()),
        _ => {}
    }
    let mut out = String::new();
    // As EncodeInterval: each non-zero part makes the next one carry its sign (`+`) when it was negative.
    let mut is_before = false;
    for (value, unit) in [(month as i64 / 12, "year"), (month as i64 % 12, "mon"), (day as i64, "day")] {
        if value == 0 {
            continue;
        }
        let plus = if is_before && value > 0 { "+" } else { "" };
        let plural = if value != 1 { "s" } else { "" };
        let space = if out.is_empty() { "" } else { " " };
        out.push_str(&format!("{space}{plus}{value} {unit}{plural}"));
        is_before = value < 0;
    }
    if out.is_empty() || time != 0 {
        let sign = if time < 0 { "-" } else if is_before { "+" } else { "" };
        let space = if out.is_empty() { "" } else { " " };
        let micros = time.unsigned_abs();
        let (hours, minutes) = (micros / 3_600_000_000, micros / 60_000_000 % 60);
        let seconds = pg_seconds((micros / 1_000_000 % 60) as i64, (micros % 1_000_000) as i64);
        out.push_str(&format!("{space}{sign}{hours:02}:{minutes:02}:{seconds}"));
    }
    Some(out)
}

/// Binary TIMETZ (`time: i64` microseconds, `zone: i32` seconds west of UTC): `12:34:56.5+02`, `12:00:00-03:30`.
pub(crate) fn decode_pg_timetz_binary(bytes: &[u8]) -> Option<String> {
    let micros = i64::from_be_bytes(bytes.get(..8)?.try_into().ok()?);
    if !(0..=86_400_000_000).contains(&micros) {
        return None;
    }
    let (secs, frac) = (micros / 1_000_000, micros % 1_000_000);
    let time = format!("{:02}:{:02}:{}", secs / 3600, secs % 3600 / 60, pg_seconds(secs % 60, frac));
    let zone = i32::from_be_bytes(bytes.get(8..12)?.try_into().ok()?);
    let offset = -(zone as i64);
    let sign = if offset < 0 { '-' } else { '+' };
    let (hours, minutes, seconds) = (offset.abs() / 3600, offset.abs() / 60 % 60, offset.abs() % 60);
    let mut out = format!("{time}{sign}{hours:02}");
    if minutes != 0 || seconds != 0 {
        out.push_str(&format!(":{minutes:02}"));
    }
    if seconds != 0 {
        out.push_str(&format!(":{seconds:02}"));
    }
    Some(out)
}

/// Binary INET / CIDR (`family: u8` (2 for IPv4, 3 for IPv6), `bits: u8`, `is_cidr: u8`, `length: u8`, the address):
/// an inet with a full netmask is written without it, as PostgreSQL does.
pub(crate) fn decode_pg_inet_binary(bytes: &[u8]) -> Option<String> {
    let (family, bits, is_cidr) = (*bytes.first()?, *bytes.get(1)?, *bytes.get(2)? != 0);
    let address = bytes.get(4..)?;
    let (address, max_bits) = match family {
        2 => (std::net::Ipv4Addr::from(<[u8; 4]>::try_from(address).ok()?).to_string(), 32),
        3 => (std::net::Ipv6Addr::from(<[u8; 16]>::try_from(address).ok()?).to_string(), 128),
        _ => return None,
    };
    Some(if is_cidr || bits != max_bits { format!("{address}/{bits}") } else { address })
}

/// Binary BIT / VARBIT (`length: i32` in bits, then the bits, most significant first): `0101`.
pub(crate) fn decode_pg_bit_binary(bytes: &[u8]) -> Option<String> {
    let length = i32::from_be_bytes(bytes.get(..4)?.try_into().ok()?);
    let data = bytes.get(4..)?;
    (0..usize::try_from(length).ok()?)
        .map(|i| Some(if data.get(i / 8)? & (0x80 >> (i % 8)) != 0 { '1' } else { '0' }))
        .collect()
}

/// Binary range (`flags: u8`, then the lower and upper bound present, each as `length: i32` and the element), written
/// as range_out does: `[1,10)`, `(,5]`, `empty`, a bound quoted when it holds a delimiter or whitespace.
pub(crate) fn decode_pg_range_binary(bytes: &[u8], element: &PgTypeInfo) -> Option<String> {
    const EMPTY: u8 = 0x01;
    const LOWER_INCLUSIVE: u8 = 0x02;
    const UPPER_INCLUSIVE: u8 = 0x04;
    const LOWER_INFINITE: u8 = 0x08;
    const UPPER_INFINITE: u8 = 0x10;
    let flags = *bytes.first()?;
    if flags & EMPTY != 0 {
        return Some("empty".to_string());
    }
    let mut pos = 1;
    let mut bound = |present: bool| -> Option<String> {
        if !present {
            return Some(String::new());
        }
        let length = usize::try_from(i32::from_be_bytes(bytes.get(pos..pos + 4)?.try_into().ok()?)).ok()?;
        let value = bytes.get(pos + 4..pos + 4 + length)?;
        pos += 4 + length;
        decode_pg_binary(element, value).map(|value| pg_range_bound(&value))
    };
    let lower = bound(flags & LOWER_INFINITE == 0)?;
    let upper = bound(flags & UPPER_INFINITE == 0)?;
    let open = if flags & LOWER_INCLUSIVE != 0 { '[' } else { '(' };
    let close = if flags & UPPER_INCLUSIVE != 0 { ']' } else { ')' };
    Some(format!("{open}{lower},{upper}{close}"))
}

#[cfg(test)]
mod numeric_tests {
    use super::{decode_pg_numeric_array_binary, decode_pg_numeric_binary};

    fn enc(ndigits: i16, weight: i16, sign: u16, dscale: u16, digits: &[u16]) -> Vec<u8> {
        let mut v = Vec::new();
        for x in [ndigits as u16, weight as u16, sign, dscale] {
            v.extend_from_slice(&x.to_be_bytes());
        }
        for d in digits {
            v.extend_from_slice(&d.to_be_bytes());
        }
        v
    }

    #[test]
    fn decodes_postgres_numeric_wire_format() {
        // 7 (SUM of bigint)
        assert_eq!(decode_pg_numeric_binary(&enc(1, 0, 0, 0, &[7])).unwrap(), "7");
        // 1.5 -> groups [1, 5000], weight 0, dscale 1
        assert_eq!(decode_pg_numeric_binary(&enc(2, 0, 0, 1, &[1, 5000])).unwrap(), "1.5");
        // 2.3333333333333333 (AVG) -> [2, 3333, 3333, 3333, 3333], dscale 16
        assert_eq!(decode_pg_numeric_binary(&enc(5, 0, 0, 16, &[2, 3333, 3333, 3333, 3333])).unwrap(), "2.3333333333333333");
        // 123456789.0001 -> [1, 2345, 6789, 1], weight 2, dscale 4
        assert_eq!(decode_pg_numeric_binary(&enc(4, 2, 0, 4, &[1, 2345, 6789, 1])).unwrap(), "123456789.0001");
        // -0.0042 -> [42], weight -1, dscale 4
        assert_eq!(decode_pg_numeric_binary(&enc(1, -1, 0x4000, 4, &[42])).unwrap(), "-0.0042");
        // 0.00000123 -> [123], weight -2, dscale 8
        assert_eq!(decode_pg_numeric_binary(&enc(1, -2, 0, 8, &[123])).unwrap(), "0.00000123");
        // 10000 -> [1], weight 1 (trailing zero groups are omitted)
        assert_eq!(decode_pg_numeric_binary(&enc(1, 1, 0, 0, &[1])).unwrap(), "10000");
        // 0 and 0.00
        assert_eq!(decode_pg_numeric_binary(&enc(0, 0, 0, 0, &[])).unwrap(), "0");
        assert_eq!(decode_pg_numeric_binary(&enc(0, 0, 0, 2, &[])).unwrap(), "0.00");
        // special values
        assert_eq!(decode_pg_numeric_binary(&enc(0, 0, 0xC000, 0, &[])).unwrap(), "NaN");
        assert_eq!(decode_pg_numeric_binary(&enc(0, 0, 0xD000, 0, &[])).unwrap(), "Infinity");
        assert_eq!(decode_pg_numeric_binary(&enc(0, 0, 0xF000, 0, &[])).unwrap(), "-Infinity");
        // malformed input is rejected instead of panicking
        assert!(decode_pg_numeric_binary(&[0, 1]).is_none());
        assert!(decode_pg_numeric_binary(&enc(2, 0, 0, 0, &[1])).is_none());
        assert!(decode_pg_numeric_binary(&enc(1, 0, 0, 0, &[10000])).is_none());
    }

    fn enc_array(dims: &[(i32, i32)], elements: &[Option<Vec<u8>>]) -> Vec<u8> {
        let mut v = Vec::new();
        v.extend_from_slice(&(dims.len() as i32).to_be_bytes());
        v.extend_from_slice(&(elements.iter().any(Option::is_none) as i32).to_be_bytes());
        v.extend_from_slice(&1700u32.to_be_bytes()); // NUMERICOID
        for (len, lbound) in dims {
            v.extend_from_slice(&len.to_be_bytes());
            v.extend_from_slice(&lbound.to_be_bytes());
        }
        for e in elements {
            match e {
                Some(bytes) => {
                    v.extend_from_slice(&(bytes.len() as i32).to_be_bytes());
                    v.extend_from_slice(bytes);
                }
                None => v.extend_from_slice(&(-1i32).to_be_bytes()),
            }
        }
        v
    }

    #[test]
    fn decodes_postgres_numeric_array_wire_format() {
        let one_five = Some(enc(2, 0, 0, 1, &[1, 5000]));
        let nan = Some(enc(0, 0, 0xC000, 0, &[]));
        let neg = Some(enc(2, 0, 0x4000, 2, &[2, 2500]));
        let zero = Some(enc(0, 0, 0, 3, &[]));
        // empty array
        assert_eq!(decode_pg_numeric_array_binary(&enc_array(&[], &[])).unwrap(), "{}");
        // one dimension, with NULL and NaN
        let a = enc_array(&[(5, 1)], &[one_five.clone(), neg.clone(), zero.clone(), None, nan.clone()]);
        assert_eq!(decode_pg_numeric_array_binary(&a).unwrap(), "{1.5,-2.25,0.000,NULL,NaN}");
        // two dimensions
        let a = enc_array(&[(2, 1), (2, 1)], &[one_five.clone(), neg.clone(), zero.clone(), nan.clone()]);
        assert_eq!(decode_pg_numeric_array_binary(&a).unwrap(), "{{1.5,-2.25},{0.000,NaN}}");
        // non-default lower bound
        let a = enc_array(&[(2, 0)], &[one_five.clone(), nan.clone()]);
        assert_eq!(decode_pg_numeric_array_binary(&a).unwrap(), "[0:1]={1.5,NaN}");
        // malformed input is rejected instead of panicking
        assert!(decode_pg_numeric_array_binary(&[0, 0]).is_none());
        let truncated = enc_array(&[(2, 1)], &[one_five.clone(), nan.clone()]);
        assert!(decode_pg_numeric_array_binary(&truncated[..truncated.len() - 1]).is_none());
        assert!(decode_pg_numeric_array_binary(&enc_array(&[(1, 1)], &[Some(vec![0, 1])])).is_none());
    }
}
