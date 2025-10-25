package io.github.smyrgeorge.sqlx4k.impl.logging

/**
 * Interface for a logger utility, facilitating structured logging at various levels.
 *
 * Provides methods for logging messages at different severity levels,
 * such as debug, info, warning, and error. Supports optional argument
 * substitution in log messages and error handling for error logs.
 */
interface Logger {
    fun debug(msg: String)
    fun debug(msg: String, vararg args: Any)
    fun info(msg: String)
    fun info(msg: String, vararg args: Any)
    fun warn(msg: String)
    fun warn(msg: String, vararg args: Any)
    fun error(msg: String)
    fun error(msg: String, e: Throwable)
}