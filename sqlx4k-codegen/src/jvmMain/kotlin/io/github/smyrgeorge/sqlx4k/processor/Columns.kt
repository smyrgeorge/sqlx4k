package io.github.smyrgeorge.sqlx4k.processor

import com.google.devtools.ksp.symbol.KSAnnotation
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSPropertyDeclaration
import com.google.devtools.ksp.validate

/**
 * Entity ↔ column-name mapping.
 *
 * This is the single source of truth for how a `@Table` entity's properties map to SQL column names,
 * shared so that the columns validated against `@Query` SQL exactly match the columns emitted by the
 * CRUD generator. It mirrors the derivation used by [TableProcessor] (explicit `@Column(name)` or the
 * property name converted to snake_case), so the two must stay consistent.
 */
internal object Columns {
    private const val NAME_PROPERTY_NAME = "name"
    private val COLUMN_NAME_REGEX = "[A-Za-z_][A-Za-z0-9_]*".toRegex()

    /** The persisted properties of a `@Table` entity, in declaration order (excludes `@Transient`). */
    fun persistedProperties(clazz: KSClassDeclaration): List<KSPropertyDeclaration> =
        clazz.getAllProperties()
            .filter { it.validate() }
            .filterNot { p -> p.annotations.any { it.qualifiedName() == TypeNames.TRANSIENT_ANNOTATION } }
            .toList()

    /** The ordered column names of a `@Table` entity (non-transient properties). */
    fun columnNames(clazz: KSClassDeclaration): List<String> =
        persistedProperties(clazz).map { columnName(it) }

    /** The column name for a property: an explicit `@Column(name)` or the snake_case property name. */
    fun columnName(prop: KSPropertyDeclaration): String {
        val column = prop.annotations.find { it.qualifiedName() == TypeNames.COLUMN_ANNOTATION }
        val name = column?.arguments?.find { it.name?.asString() == NAME_PROPERTY_NAME }?.value as? String
        if (name.isNullOrEmpty()) return prop.simpleName.asString().toSnakeCase()
        if (!name.matches(COLUMN_NAME_REGEX)) {
            error(
                "Invalid @Column name '$name' for property '${prop.simpleName.asString()}' in " +
                        "${prop.parentDeclaration?.qualifiedName?.asString()}: column names must match " +
                        "[A-Za-z_][A-Za-z0-9_]* (leave it empty to derive the name from the property name)."
            )
        }
        return name
    }

    private fun KSAnnotation.qualifiedName(): String? = annotationType.resolve().declaration.qualifiedName?.asString()
    private fun String.toSnakeCase(): String = replace("(?<=.)[A-Z]".toRegex(), "_$0").lowercase()
}
