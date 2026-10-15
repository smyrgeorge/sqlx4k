package io.github.smyrgeorge.sqlx4k.postgres.pgmq

import io.github.smyrgeorge.sqlx4k.ConnectionPool
import io.github.smyrgeorge.sqlx4k.postgres.IPostgresSQL
import io.github.smyrgeorge.sqlx4k.postgres.pgmq.impl.PgmqDbAdapterImpl
import io.github.smyrgeorge.sqlx4k.postgres.postgreSQL

/** Connects to the PostgreSQL server from `docker-compose.yml`, which has the pgmq extension available. */
internal fun testPostgreSQL(): IPostgresSQL = postgreSQL(
    url = "postgresql://localhost:15432/test",
    username = "postgres",
    password = "postgres",
    options = ConnectionPool.Options.builder().maxConnections(2).build(),
)

/** A client that installs pgmq on first use. */
internal fun testPgmqClient(db: IPostgresSQL): PgmqClient = PgmqClient(
    pg = PgmqDbAdapterImpl(db),
    options = PgmqClient.Options(autoInstall = true, verifyInstallation = true),
)
