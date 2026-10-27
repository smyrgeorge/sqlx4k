package io.github.smyrgeorge.sqlx4k

/**
 * The outcome of a statement run with [QueryExecutor.executeWithResult].
 *
 * @property rowsAffected The number of rows inserted, updated or deleted.
 * @property lastInsertId The auto-generated id of the row inserted by the statement, when the database reports one:
 * MySQL/MariaDB (`LAST_INSERT_ID()`) and SQLite (the rowid). It is `null` when the statement generated no id, and
 * always on PostgreSQL, which has no such value: use `INSERT ... RETURNING` with `fetchAll` there. For a multi-row
 * insert, MySQL reports the id of the first inserted row and SQLite the id of the last. For a SQLite UPSERT
 * (`ON CONFLICT ... DO UPDATE`), it is `null` when the statement updated the row instead of inserting it, and also
 * when the row it inserted has the same rowid as the connection's previous insert (into another table): SQLite tells
 * the two apart by no other means. Use `RETURNING` with `fetchAll` to read the row's id in every case.
 */
data class ExecuteResult(
    val rowsAffected: Long,
    val lastInsertId: Long?,
)
