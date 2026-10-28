# Acknowledgements

sqlx4k stands on the shoulders of excellent open-source projects:

- Data access engines
    - Native targets (Kotlin/Native):
        - sqlx (Rust): <https://github.com/launchbadge/sqlx>
    - JVM targets:
        - PostgreSQL: r2dbc-postgresql, <https://github.com/pgjdbc/r2dbc-postgresql>
        - MySQL: r2dbc-mysql, <https://github.com/asyncer-io/r2dbc-mysql>
        - SQLite: sqlite-jdbc, <https://github.com/xerial/sqlite-jdbc>
    - Encrypted SQLite, `sqlx4k-sqlite-cipher` (the same sqlx (Rust) core on every target, via FFI on native and JNI on
      JVM/Android):
        - SQLCipher, encrypted SQLite: <https://www.zetetic.net/sqlcipher/>
- Build-time tooling
    - JSqlParser, used by the code generator to parse `@Query` SQL at build time for syntax validation:
      <https://github.com/JSQLParser/JSqlParser>
    - Apache Calcite, used by the code generator for compile-time SQL schema validation: <https://calcite.apache.org/>

Huge thanks to the maintainers and contributors of these projects.
