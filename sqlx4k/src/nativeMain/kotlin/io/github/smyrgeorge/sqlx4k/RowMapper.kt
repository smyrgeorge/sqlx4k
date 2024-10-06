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
    fun map(rs: ResultSet): List<T> = rs.use { rs.map { map(rs, it) } }

    /**
     * Maps a single database row to an instance of type T.
     *
     * @param row The database row to map.
     * @return An instance of type T that corresponds to the provided row.
     */
    fun map(rs: ResultSet, row: ResultSet.Row): T
}