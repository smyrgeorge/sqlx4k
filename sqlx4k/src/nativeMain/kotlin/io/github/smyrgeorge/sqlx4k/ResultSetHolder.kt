package io.github.smyrgeorge.sqlx4k

/**
 * A utility class designed to wrap a `ResultSet` and provide a safe mechanism for resource management.
 *
 * This class ensures that the underlying `ResultSet` is closed automatically after being used,
 * reducing the risk of resource leaks. It provides the `use` function to apply operations to the
 * `ResultSet` within a safe context.
 */
class ResultSetHolder(
    private val res: ResultSet
) {
    /**
     * Executes the given lambda function on the `ResultSet` instance, ensuring that the `ResultSet`
     * is automatically closed after the operation is completed.
     *
     * @param f A lambda function to be executed with `ResultSet` as its receiver.
     * @return The result of the lambda function executed on the `ResultSet`.
     */
    fun <R> use(f: ResultSet.() -> R): R {
        return try {
            res.f()
        } finally {
            res.close()
        }
    }
}