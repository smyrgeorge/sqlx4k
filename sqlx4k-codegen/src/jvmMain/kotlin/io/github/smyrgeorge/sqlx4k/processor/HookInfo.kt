package io.github.smyrgeorge.sqlx4k.processor

import com.google.devtools.ksp.symbol.KSFunctionDeclaration

/**
 * Data class to hold information about a parsed hook.
 *
 * @property function The function declaration annotated with @Query.Hook
 * @property kind The hook kind (INSERT, UPDATE, DELETE, QUERY)
 * @property callFromDefaultCrudMethod Whether the hook should be called from the default CRUD methods
 */
data class HookInfo(
    val function: KSFunctionDeclaration,
    val kind: Kind,
    val callFromDefaultCrudMethod: Boolean
) {
    enum class Kind { PRE_INSERT, PRE_UPDATE, PRE_DELETE, QUERY }
}
