# Async I/O & coroutines

Every query API in sqlx4k is a `suspend` function that returns a `Result`: `execute`, `fetchAll`, `begin`,
`transaction { }` and the generated repository methods suspend instead of blocking the calling thread, and report
failures through the `Result` instead of throwing. Database calls therefore compose with the rest of your coroutine
code, from `async` and `withTimeout` to structured concurrency, and the I/O underneath is non-blocking on every
platform.

This guide covers the core API, shared by every driver:

- [Connection pool](connection-pool.md): configuring the pool, and acquiring connections.
- [Running queries](queries.md): `execute`, `executeWithResult`, `fetchAll`, `fetch` and prepared statements.
- [Mapping rows](mapping.md): row mappers and custom value converters.
- [Transactions](transactions.md): transactions, savepoints and `TransactionContext`.
- [Migrations](migrations.md): running and validating database migrations.

For the generated repositories, see [Code generation](../codegen/index.md).
