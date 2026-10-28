# Roadmap

Next steps (contributions are welcome):

- Driver-level interceptor. A QueryListener on the driver gives query logging, slow-query warnings, and metrics.
- Pool lifecycle hooks and health. afterConnect for session setup such as search_path, timezone, or application_name,
  plus ping () and acquire-wait metrics.
- Postgres COPY. COPY FROM STDIN is an order of magnitude faster than multi-row INSERT for bulk loads.
- `kotlinx.serialization` module. JSON and JSONB columns to and from @Serializable classes, and a generic @Converter for
  any serializable type.
- Type coverage: a Kotlin decimal type for NUMERIC/decimal (values already decode as exact text), Duration or interval,
  enum or composite types.
- WASM support (?).

Found a bug or want to propose something? Open an [issue](https://github.com/smyrgeorge/sqlx4k/issues).
