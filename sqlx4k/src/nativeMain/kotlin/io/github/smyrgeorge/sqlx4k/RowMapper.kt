package io.github.smyrgeorge.sqlx4k

/**
 * Interface for mapping database rows to objects.
 *
 * @param T The type of the object to map the rows to.
 */
interface RowMapper<T> {
    /**
     * Maps all rows of the given ResultSet to a list of instances of type T.
     *
     * @param rs The ResultSet containing the rows to map.
     * @return A list of instances of type T mapped from the ResultSet rows.
     */
    fun map(rs: ResultSet): List<T> = rs.map { map(rs, it) }

    /**
     * Maps a single database row to an instance of type T.
     *
     * @param row The database row to map.
     * @return An instance of type T that corresponds to the provided row.
     */
    fun map(rs: ResultSet, row: ResultSet.Row): T
}