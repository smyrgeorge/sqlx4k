package io.github.smyrgeorge.sqlx4k.processor

import com.google.devtools.ksp.symbol.KSPropertyDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.KSTypeAlias

/**
 * The type of the property with its type aliases expanded (`typealias Key = Long` gives `Long`), keeping its
 * nullability: the generated code picks its decoders, typed nulls and id checks by the underlying type.
 */
internal fun KSPropertyDeclaration.resolvedType(): KSType = type.resolve().expandTypeAliases()

/** This type with its type aliases expanded, keeping its nullability. */
internal fun KSType.expandTypeAliases(): KSType {
    val alias = declaration as? KSTypeAlias ?: return this
    val expanded = alias.type.resolve().expandTypeAliases()
    return if (isMarkedNullable) expanded.makeNullable() else expanded
}
