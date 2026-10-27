//! Column values as handed to Kotlin: text (SQLite's REAL as it writes it) or bytes.

use std::ffi::{c_char, c_int};

/// Hands a column value to the caller as a NUL-terminated buffer plus its length. TEXT may contain
/// NUL bytes, which `CString` cannot hold. Released with `free_column_value`.
pub(crate) fn column_value_into_raw(value: String) -> (*mut c_char, c_int) {
    let len = value.len();
    let mut bytes = value.into_bytes();
    bytes.push(0);
    (Box::into_raw(bytes.into_boxed_slice()) as *mut u8 as *mut c_char, len as c_int)
}

pub(crate) fn free_column_value(value: *mut c_char, len: c_int) {
    if !value.is_null() {
        let bytes = std::ptr::slice_from_raw_parts_mut(value as *mut u8, len as usize + 1);
        drop(unsafe { Box::from_raw(bytes) });
    }
}

/// Renders a REAL in the layout SQLite uses for `cast(x as text)` (`1.5`, `3.0`, `1.0e+300`,
/// `1.23e-05`, `Inf`), but with the shortest digits that round-trip to `value`.
///
/// SQLite's own text depends on its version (the bundled 3.46 keeps only 15 significant digits,
/// so e.g. `avg(..)` loses precision), and every platform ships a different one. Formatting the
/// double here keeps the drivers in agreement; the JVM/Android SQLite driver does the same in Kotlin.
pub(crate) fn format_sqlite_real(value: f64) -> String {
    if value.is_nan() {
        return "NaN".to_string();
    }
    if value.is_infinite() {
        return if value > 0.0 { "Inf" } else { "-Inf" }.to_string();
    }
    if value == 0.0 {
        return "0.0".to_string();
    }
    // `{:e}` prints the shortest round-trip digits, e.g. `2.3333333333333335e0` or `1e300`.
    let sci = format!("{:e}", value.abs());
    let (mantissa, exponent) = sci.split_once('e').unwrap_or((sci.as_str(), "0"));
    let exponent: i32 = exponent.parse().unwrap_or(0);
    let digits: String = mantissa.chars().filter(|c| *c != '.').collect();

    let mut out = String::new();
    if value < 0.0 {
        out.push('-');
    }
    if exponent < -4 || exponent >= 17 {
        out.push_str(&digits[..1]);
        out.push('.');
        out.push_str(if digits.len() > 1 { &digits[1..] } else { "0" });
        out.push_str(&format!("e{}{:02}", if exponent < 0 { '-' } else { '+' }, exponent.abs()));
    } else if exponent >= 0 {
        let int_len = exponent as usize + 1;
        if digits.len() > int_len {
            out.push_str(&digits[..int_len]);
            out.push('.');
            out.push_str(&digits[int_len..]);
        } else {
            out.push_str(&digits);
            out.push_str(&"0".repeat(int_len - digits.len()));
            out.push_str(".0");
        }
    } else {
        out.push_str("0.");
        out.push_str(&"0".repeat((-exponent - 1) as usize));
        out.push_str(&digits);
    }
    out
}

#[cfg(test)]
mod real_tests {
    use super::format_sqlite_real;

    #[test]
    fn formats_reals_like_sqlite_with_shortest_digits() {
        let cases: &[(f64, &str)] = &[
            (1.5, "1.5"),
            (3.0, "3.0"),
            (-2.5, "-2.5"),
            (0.0, "0.0"),
            (-0.0, "0.0"),
            (10000.0, "10000.0"),
            (-0.0042, "-0.0042"),
            (0.0001234, "0.0001234"),
            (0.00001234, "1.234e-05"),
            (1.23e-6, "1.23e-06"),
            (123456789.0001, "123456789.0001"),
            (7.0 / 3.0, "2.3333333333333335"),
            (1.0 / 3.0, "0.3333333333333333"),
            (0.1 + 0.2, "0.30000000000000004"),
            (0.1, "0.1"),
            (4.35, "4.35"),
            (1e16, "10000000000000000.0"),
            (9.99e16, "99900000000000000.0"),
            (1e17, "1.0e+17"),
            (123456789012345678.0, "1.2345678901234568e+17"),
            (1e300, "1.0e+300"),
            (-1e-300, "-1.0e-300"),
            (5e-324, "5.0e-324"),
            (f64::MAX, "1.7976931348623157e+308"),
            (f64::INFINITY, "Inf"),
            (f64::NEG_INFINITY, "-Inf"),
        ];
        for (value, expected) in cases {
            assert_eq!(format_sqlite_real(*value), *expected, "formatting {value:e}");
        }
    }
}
