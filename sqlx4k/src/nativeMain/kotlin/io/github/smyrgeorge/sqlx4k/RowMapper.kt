package io.github.smyrgeorge.sqlx4k

/**
 * Interface for mapping database rows to objects.
 *
 * @param T The type of the object to map the rows to.
 */
interface RowMapper<T> {
    /**
     * Maps the results from the given ResultSet to a list of objects of type T.
     *
     * @param rs The ResultSet containing database rows to be mapped.
     * @return A list of objects of type T mapped from the ResultSet.
     */
    fun map(rs: ResultSet): List<T> = rs.use { rs.map { map(it) } }

    /**
     * Maps the given database row to an object of type T.
     *
     * @param row The ResultSet.Row representing a single database row to be mapped.
     * @return An object of type T that represents the mapped data from the row.
     */
    fun map(row: ResultSet.Row): T
}