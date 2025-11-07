package io.github.smyrgeorge.sqlx4k.mysql

import io.github.smyrgeorge.sqlx4k.Driver
import io.github.smyrgeorge.sqlx4k.Transaction

interface IMySQL : Driver {
    companion object {
        /**
         * The default transaction isolation level used by the MySQL driver implementation.
         *
         * This value is set to [Transaction.IsolationLevel.RepeatableRead], which ensures that if a
         * transaction reads a row, the data in that row will remain consistent for the duration of
         * the transaction. This prevents non-repeatable reads and strikes a balance between
         * performance and data consistency in typical scenarios.
         *
         * The default isolation level may be overridden if specific transaction requirements demand
         * a different level of data consistency or concurrency control.
         */
        val DEFAULT_TRANSACTION_ISOLATION_LEVEL: Transaction.IsolationLevel = Transaction.IsolationLevel.RepeatableRead
    }
}