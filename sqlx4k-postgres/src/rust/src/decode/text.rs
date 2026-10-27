//! Writing values as PostgreSQL's text format does (floats, arrays, ranges, fractions).

/// A float as PostgreSQL and Kotlin write it: Rust writes the infinities as `inf` and `-inf`, which Kotlin cannot
/// parse (`NaN` is the same in all three).
pub(crate) fn float_text(value: impl ToString) -> String {
    match value.to_string().as_str() {
        "inf" => "Infinity".to_string(),
        "-inf" => "-Infinity".to_string(),
        text => text.to_string(),
    }
}

/// A text element as PostgreSQL writes it in an array: quoted when it is empty, reads `NULL`, or holds a delimiter,
/// quote, backslash or whitespace (so that the Kotlin decoders read `{"a,b"}` back as one element).
pub(crate) fn pg_array_element(value: &str) -> String {
    let quote = value.is_empty()
        || value.eq_ignore_ascii_case("NULL")
        || value
            .chars()
            .any(|c| matches!(c, '{' | '}' | ',' | '"' | '\\') || c.is_whitespace());
    if !quote {
        return value.to_string();
    }
    let mut s = String::with_capacity(value.len() + 2);
    s.push('"');
    for c in value.chars() {
        if c == '"' || c == '\\' {
            s.push('\\');
        }
        s.push(c);
    }
    s.push('"');
    s
}

/// Text format is passed through as is; binary format is rendered to the same text by `decode_binary`.
/// Decodes bytea's escape output format: `\\` is a backslash, `\ooo` an octal byte, anything else the byte itself.
pub(crate) fn decode_bytea_escape(text: &str) -> Option<Vec<u8>> {
    let bytes = text.as_bytes();
    let mut out = Vec::with_capacity(bytes.len());
    let mut i = 0;
    while i < bytes.len() {
        if bytes[i] != b'\\' {
            out.push(bytes[i]);
            i += 1;
        } else if bytes.get(i + 1) == Some(&b'\\') {
            out.push(b'\\');
            i += 2;
        } else {
            let octal = std::str::from_utf8(bytes.get(i + 1..i + 4)?).ok()?;
            out.push(u8::from_str_radix(octal, 8).ok()?);
            i += 4;
        }
    }
    Some(out)
}

/// A time or timestamp written with six fractional digits (`10:00:00.500000`) without its trailing zeros
/// (`10:00:00.5`, `10:00:00`).
pub(crate) fn trim_pg_fraction(value: String) -> String {
    match value.rfind('.') {
        Some(dot) => value[..dot + 1].to_string() + value[dot + 1..].trim_end_matches('0'),
        None => value,
    }
    .trim_end_matches('.')
    .to_string()
}

/// Seconds and fractional seconds as PostgreSQL writes them in a time: `SS`, or `SS.f` without trailing zeros.
pub(crate) fn pg_seconds(seconds: i64, micros: i64) -> String {
    if micros == 0 {
        format!("{seconds:02}")
    } else {
        format!("{seconds:02}.{micros:06}").trim_end_matches('0').to_string()
    }
}

/// A range bound as range_out writes it: quoted, with its quotes and backslashes doubled, when it is empty or holds
/// a delimiter, quote, backslash or whitespace.
pub(crate) fn pg_range_bound(value: &str) -> String {
    let quote = value.is_empty()
        || value.chars().any(|c| matches!(c, '"' | '\\' | '(' | ')' | '[' | ']' | ',') || c.is_whitespace());
    if !quote {
        return value.to_string();
    }
    let mut s = String::with_capacity(value.len() + 2);
    s.push('"');
    for c in value.chars() {
        if c == '"' || c == '\\' {
            s.push(c);
        }
        s.push(c);
    }
    s.push('"');
    s
}

/// Like format_pg_array_fmt, for elements that may be NULL (rendered `NULL`, as postgres does).
pub(crate) fn format_pg_nullable_array_fmt<'a, T: 'a, I: Iterator<Item = &'a Option<T>>, F: FnMut(&'a T) -> String>(
    iter: I,
    mut format_one: F,
) -> String {
    format_pg_array_fmt(iter, |v| v.as_ref().map_or_else(|| "NULL".to_string(), &mut format_one))
}

pub(crate) fn format_pg_array_fmt<'a, T: 'a, I: Iterator<Item = &'a T>, F: FnMut(&'a T) -> String>(
    iter: I,
    mut format_one: F,
) -> String {
    let mut s = String::from("{");
    let mut first = true;
    for v in iter {
        if !first {
            s.push(',');
        }
        first = false;
        s.push_str(&format_one(v));
    }
    s.push('}');
    s
}
