package io.github.smyrgeorge.sqlx4k.annotation

/**
 * Annotation used to define a database query for repository methods.
 *
 * @property value The SQL query string to be executed.
 * @property checkSyntax Indicates whether the syntax of the query should be validated.
 * @property checkSchema Indicates whether the schema compliance of the query should be validated.
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.SOURCE)
annotation class Query(val value: String, val checkSyntax: Boolean = true, val checkSchema: Boolean = true) {
    @Target(AnnotationTarget.FUNCTION)
    @Retention(AnnotationRetention.SOURCE)
    annotation class Hook(
        val kind: Kind,
        val callFromDefaultCrudMethod: Boolean = true
    ) {
        enum class Kind { PRE_INSERT, PRE_UPDATE, PRE_DELETE }
    }
}
