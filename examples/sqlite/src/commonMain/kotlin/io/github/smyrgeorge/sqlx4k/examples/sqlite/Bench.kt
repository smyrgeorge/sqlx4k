package io.github.smyrgeorge.sqlx4k.examples.sqlite

import io.github.smyrgeorge.sqlx4k.Driver
import kotlin.time.Duration
import kotlin.time.TimeSource

/**
 * A small, fair micro-benchmark used to compare the plain SQLite driver against the encrypted
 * SQLCipher driver. It takes the common [Driver] supertype, so the exact same code runs against
 * both. The workload is intentionally small and is just text queries + transactions:
 *
 *  - [TX_COUNT] transactions, each inserting [TX_INSERTS] rows (the write / encrypt path);
 *  - [SELECTS] single-row `SELECT`s (the read / decrypt path).
 *
 * The table setup runs outside the timed section (so the connection — and, for SQLCipher, the
 * `PRAGMA key` derivation — is already warm), isolating the per-query cost.
 */
object Bench {
    private const val TX_COUNT = 20
    private const val TX_INSERTS = 100
    private const val SELECTS = 2_000

    suspend fun run(db: Driver): Duration {
        db.execute("drop table if exists bench;").getOrThrow()
        db.execute("create table bench(id integer primary key, v text not null);").getOrThrow()

        val mark = TimeSource.Monotonic.markNow()

        // Write path: batched text inserts inside transactions.
        var id = 0
        repeat(TX_COUNT) {
            db.transaction {
                repeat(TX_INSERTS) {
                    id += 1
                    execute("insert into bench(id, v) values ($id, 'row-$id');").getOrThrow()
                }
            }
        }

        // Read path: single-row text selects spread across the inserted rows.
        repeat(SELECTS) { i ->
            db.fetchAll("select id, v from bench where id = ${(i % id) + 1};").getOrThrow()
        }

        return mark.elapsedNow()
    }
}
