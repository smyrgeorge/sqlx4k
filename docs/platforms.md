# Supported targets

- jvm
- android (`sqlx4k-sqlite` and `sqlx4k-sqlite-cipher` only, minSdk 26)
- iosArm64
- iosSimulatorArm64
- androidNativeX64
- androidNativeArm64
- macosArm64
- linuxArm64
- linuxX64
- mingwX64
- wasmWasi (potential future candidate)

On native targets the drivers are backed by [sqlx](https://github.com/launchbadge/sqlx) (Rust), through Kotlin/Native
FFI. On the JVM they use R2DBC (PostgreSQL, MySQL) and JDBC (SQLite). See each [database](databases/postgresql.md) page
for the details.

## Windows

If you are building your project on Windows, for target mingwX64, and you encounter the following error:

```text
lld-link: error: -exclude-symbols:___chkstk_ms is not allowed in .drectve
```

Please look at this issue: [#18](https://github.com/smyrgeorge/sqlx4k/issues/18)
