# sqlx4k-sqlite

![Build](https://github.com/smyrgeorge/sqlx4k/actions/workflows/ci.yml/badge.svg)
![Maven Central](https://img.shields.io/maven-central/v/io.github.smyrgeorge/sqlx4k-sqlite)
![GitHub License](https://img.shields.io/github/license/smyrgeorge/sqlx4k)

SQLite driver implementation for sqlx4k, providing coroutine-first database access across Kotlin Multiplatform targets.

## Supported Platforms

- JVM (including Android)
- Native (macOS, iOS, Linux, Windows)

## Installation

Add the dependency to your `build.gradle.kts`:

```kotlin
dependencies {
    implementation("io.github.smyrgeorge:sqlx4k-sqlite:1.4.0")
}
```

### Platform-Specific Setup

#### JVM Targets

For JVM targets, this module uses the [xerial/sqlite-jdbc](https://github.com/xerial/sqlite-jdbc) driver, which is
automatically included as a transitive dependency.

#### Android Targets

For Android targets, you need to provide native SQLite libraries. The `sqlite-jdbc` library includes native libraries
for most platforms, but Android requires additional configuration.

##### Adding Native Libraries for Android

1. **Download the required native library** for your target architecture from
   the [xerial/sqlite-jdbc repository](https://github.com/xerial/sqlite-jdbc):
    - [aarch64 (ARM64)](https://github.com/xerial/sqlite-jdbc/blob/master/src/main/resources/org/sqlite/native/Linux-Android/aarch64/libsqlitejdbc.so)
    - [arm (ARMv7)](https://github.com/xerial/sqlite-jdbc/blob/master/src/main/resources/org/sqlite/native/Linux-Android/arm/libsqlitejdbc.so)
    - [x86](https://github.com/xerial/sqlite-jdbc/blob/master/src/main/resources/org/sqlite/native/Linux-Android/x86/libsqlitejdbc.so)
    - [x86_64](https://github.com/xerial/sqlite-jdbc/blob/master/src/main/resources/org/sqlite/native/Linux-Android/x86_64/libsqlitejdbc.so)

2. **Create the jniLibs directory structure** in your Android module:
   ```
   src/
   └── main/
       └── jniLibs/
           ├── arm64-v8a/
           │   └── libsqlitejdbc.so
           ├── armeabi-v7a/
           │   └── libsqlitejdbc.so
           ├── x86/
           │   └── libsqlitejdbc.so
           └── x86_64/
               └── libsqlitejdbc.so
   ```

3. **Map the downloaded libraries to Android ABIs**:
    - `Linux-Android/aarch64/libsqlitejdbc.so` → `jniLibs/arm64-v8a/libsqlitejdbc.so`
    - `Linux-Android/arm/libsqlitejdbc.so` → `jniLibs/armeabi-v7a/libsqlitejdbc.so`
    - `Linux-Android/x86/libsqlitejdbc.so` → `jniLibs/x86/libsqlitejdbc.so`
    - `Linux-Android/x86_64/libsqlitejdbc.so` → `jniLibs/x86_64/libsqlitejdbc.so`

4. **Example shell commands** to download and set up for ARM64:
   ```bash
   # Create directory structure
   mkdir -p src/main/jniLibs/arm64-v8a

   # Download the library
   curl -L https://github.com/xerial/sqlite-jdbc/raw/master/src/main/resources/org/sqlite/native/Linux-Android/aarch64/libsqlitejdbc.so \
     -o src/main/jniLibs/arm64-v8a/libsqlitejdbc.so
   ```

#### Native Targets

For Native targets (macOS, iOS, Linux, Windows), SQLite is accessed through the platform's native SQLite library. No
additional setup is required.

## Usage

### Basic Setup

```kotlin
import io.github.smyrgeorge.sqlx4k.sqlite.SQLite

// File-based database
val db = SQLite("database.db")

// In-memory database (single connection only)
val memDb = SQLite(
    ":memory:", ConnectionPool.Options(
        minConnections = 1,
        maxConnections = 1
    )
)

// Absolute path
val absDb = SQLite("/path/to/database.db")
```

### URL Format

The SQLite driver accepts URLs in the following formats:

- `:memory:` - Creates an in-memory database
- `database.db` - Creates/opens a database file in the current directory
- `/path/to/database.db` - Uses absolute path

On JVM, these are automatically converted to the JDBC format (`jdbc:sqlite:...`).

### Connection Pool Configuration

```kotlin
import io.github.smyrgeorge.sqlx4k.ConnectionPool

val db = SQLite(
    url = "myapp.db",
    options = ConnectionPool.Options(
        minConnections = 2,
        maxConnections = 10,
        maxIdleTime = 30_000,  // 30 seconds
        connectionTimeout = 5_000  // 5 seconds
    )
)
```

**Important for In-Memory Databases:**

- In-memory SQLite databases are isolated per connection
- You MUST use `minConnections = 1` and `maxConnections = 1` for in-memory databases
- Each connection creates a separate in-memory database instance

### WAL Mode

For file-based databases, the driver automatically enables [Write-Ahead Logging (WAL)](https://www.sqlite.org/wal.html)
mode to improve concurrency and performance:

```kotlin
// WAL mode is enabled automatically for file-based databases
val db = SQLite("myapp.db")
// PRAGMA journal_mode=WAL is executed automatically
```

In-memory databases do not support WAL mode.

## Examples

For complete working examples, see the [examples/sqlite](../examples/sqlite) directory.

## Performance Tips

1. **Use connection pooling** for better concurrency (file-based databases only)
2. **Enable WAL mode** (enabled by default for file-based databases)
3. **Use prepared statements** to prevent SQL injection and improve performance
4. **Batch operations** within transactions for better performance
5. **Configure pool size** based on your workload:
    - For read-heavy workloads: larger pool size
    - For write-heavy workloads: smaller pool size (SQLite has limited write concurrency)

## Limitations

- **In-memory databases**: Must use pool size of 1 (each connection creates a separate database)
- **Write concurrency**: SQLite uses database-level locking, so write operations are serialized
- **Android**: Requires manual setup of native libraries (see [Android Targets](#android-targets))

## Documentation

For more information about sqlx4k features and capabilities, see
the [main documentation](https://smyrgeorge.github.io/sqlx4k/).

## License

This project is licensed under the Apache License 2.0 - see the [LICENSE](../LICENSE) file for details.
