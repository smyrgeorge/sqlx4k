package io.github.smyrgeorge.sqlx4k.processor

import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.Dependencies
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSAnnotation
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSDeclaration
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.google.devtools.ksp.symbol.KSPropertyDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.validate
import java.io.OutputStream
import net.sf.jsqlparser.expression.BinaryExpression
import net.sf.jsqlparser.expression.DoubleValue
import net.sf.jsqlparser.expression.Expression
import net.sf.jsqlparser.expression.JdbcNamedParameter
import net.sf.jsqlparser.expression.LongValue
import net.sf.jsqlparser.expression.NullValue
import net.sf.jsqlparser.expression.StringValue
import net.sf.jsqlparser.expression.operators.conditional.AndExpression
import net.sf.jsqlparser.expression.operators.conditional.OrExpression
import net.sf.jsqlparser.expression.operators.relational.EqualsTo
import net.sf.jsqlparser.expression.operators.relational.GreaterThan
import net.sf.jsqlparser.expression.operators.relational.GreaterThanEquals
import net.sf.jsqlparser.expression.operators.relational.IsNullExpression
import net.sf.jsqlparser.expression.operators.relational.MinorThan
import net.sf.jsqlparser.expression.operators.relational.MinorThanEquals
import net.sf.jsqlparser.expression.operators.relational.NotEqualsTo
import net.sf.jsqlparser.expression.operators.relational.ParenthesedExpressionList
import net.sf.jsqlparser.parser.CCJSqlParserUtil
import net.sf.jsqlparser.schema.Column
import net.sf.jsqlparser.statement.delete.Delete
import net.sf.jsqlparser.statement.select.PlainSelect
import net.sf.jsqlparser.statement.update.Update

class InMemoryRepositoryProcessor(
    private val options: Map<String, String>,
    private val logger: KSPLogger,
    private val codeGenerator: CodeGenerator,
) : SymbolProcessor {

    override fun process(resolver: Resolver): List<KSAnnotated> {
        val repoSymbols = resolver
            .getSymbolsWithAnnotation(TypeNames.REPOSITORY_ANNOTATION)
            .filterIsInstance<KSClassDeclaration>()
            .toList()
        if (repoSymbols.isEmpty()) return emptyList()

        val outputPackage = options[PACKAGE_OPTION]
            ?: error("Missing $PACKAGE_OPTION option")
        logger.info("[InMemoryRepositoryProcessor] Output package: $outputPackage")

        // Generate an in-memory double for every @Repository interface in the current compilation.
        // Symbols that are not yet fully resolvable are deferred to a later processing round.
        val deferred = mutableListOf<KSAnnotated>()
        repoSymbols.forEach { repo ->
            if (!repo.validate()) deferred.add(repo)
            else generate(repo, resolver, outputPackage)
        }
        return deferred
    }

    /**
     * Generates a single `InMemoryXxxRepository` class for the given repository interface.
     */
    private fun generate(repo: KSClassDeclaration, resolver: Resolver, outputPackage: String) {
        if (repo.classKind != ClassKind.INTERFACE)
            error("@Repository is only supported on interfaces (${repo.qualifiedName()}).")

        val info = parseRepositoryAnnotation(repo)
        val domainDecl = info.domain
        val domainQn = domainDecl.qualifiedName()
            ?: error("Cannot resolve domain type for ${repo.qualifiedName()}")
        val repoQn = repo.qualifiedName() ?: error("Cannot resolve repository type name")
        val useContext = info.useContextParameters
        val useArrow = info.useArrow

        // Resolve the id property. Prefer the @Id-annotated one; but when the entity is resolved from
        // the classpath (e.g. declared in `main` and seen by the test round as a compiled class), @Id
        // has SOURCE retention and is stripped, so fall back to a property conventionally named "id".
        val idAnn = TypeNames.ID_ANNOTATION
        val annotatedIdProp = domainDecl.getAllProperties().firstOrNull { p ->
            p.annotations.any { it.qualifiedName() == idAnn }
        }
        val idProp: KSPropertyDeclaration = annotatedIdProp
            ?: domainDecl.getAllProperties().firstOrNull { it.simpleName.asString() == "id" }
            ?: error(
                "Cannot determine the id property of '${domainDecl.qualifiedName()}' referenced by " +
                        "${repo.qualifiedName()}: no visible @Id-annotated property and no property named 'id'. " +
                        "Annotate the id with @Id (visible to this compilation) or name the property 'id'."
            )
        val idName = idProp.simpleName.asString()
        val idTypeQn = idProp.type.resolve().declaration.qualifiedName()
        val idType = idProp.type.toString()
        // @Id(insert = true) marks an application-provided id. When @Id is not visible we default to
        // false (i.e. DB/auto-generated), which is inferred below from the id type for Int/Long keys.
        val idInsert = annotatedIdProp?.annotations
            ?.firstOrNull { it.qualifiedName() == idAnn }
            ?.arguments?.firstOrNull { it.name?.asString() == "insert" }
            ?.value as? Boolean ?: false

        // Auto-generated ids: numeric (@Id(insert = false)) primary keys backed by an incrementing sequence.
        val autoGen = !idInsert && (idTypeQn == TypeNames.KOTLIN_INT || idTypeQn == TypeNames.KOTLIN_LONG)
        val zeroLiteral = if (idTypeQn == TypeNames.KOTLIN_LONG) "0L" else "0"
        val oneLiteral = if (idTypeQn == TypeNames.KOTLIN_LONG) "1L" else "1"

        // Maps each entity column name (lowercased) to its Kotlin property, so parsed SQL WHERE/SET
        // clauses can be translated into in-memory predicates and copies.
        val columns = buildColumnMap(domainDecl)

        // Methods annotated with @Query declared on the repository interface (mirrors the main codegen).
        val queryFns = repo.declarations.filterIsInstance<KSFunctionDeclaration>()
            .filter { fn -> fn.annotations.any { it.qualifiedName() == TypeNames.QUERY_ANNOTATION } }
            .toList()

        val implName = "InMemory${repo.simpleName()}"
        logger.info("[InMemoryRepositoryProcessor] Generating $implName for $repoQn")

        val paramImports = collectParameterTypeImports(queryFns, idProp, outputPackage)

        val file: OutputStream = codeGenerator.createNewFile(
            dependencies = Dependencies(false, *resolver.getAllFiles().toList().toTypedArray()),
            packageName = outputPackage,
            fileName = implName
        )

        val resultType = if (useArrow) TypeNames.DB_RESULT else "Result"
        val toResult = if (useArrow) ".toDbResult()" else ""

        file += "// Generated by sqlx4k-codegen (InMemoryRepositoryProcessor)\n"
        file += "//@formatter:off\n"
        file += "@file:Suppress(\"unused\", \"RedundantVisibilityModifier\", \"RemoveRedundantQualifierName\", \"UNUSED_PARAMETER\", \"UnusedImport\", \"RedundantSuppression\", \"SENSELESS_COMPARISON\")\n\n"
        file += "package $outputPackage\n\n"
        file += "import ${TypeNames.QUERY_EXECUTOR}\n"
        file += "import ${TypeNames.SQL_ERROR}\n"
        file += "import kotlinx.coroutines.sync.Mutex\n"
        file += "import kotlinx.coroutines.sync.withLock\n"
        if (useArrow) file += "import ${TypeNames.TO_DB_RESULT}\n"
        // Used when the aroundQuery hook is overridden (the double synthesizes a Statement per method).
        file += "import ${TypeNames.STATEMENT}\n"
        paramImports.sorted().forEach { file += "import $it\n" }
        file += "\n"

        // Class header + backing store.
        file += "/**\n"
        file += " * In-memory test double for [$repoQn].\n"
        file += " *\n"
        file += " * Backed by a [MutableMap] guarded by a [Mutex] for thread safety. Generated by sqlx4k-codegen.\n"
        file += " */\n"
        file += "public open class $implName : $repoQn {\n"
        file += "    private val mutex: Mutex = Mutex()\n"
        file += "    private val store: MutableMap<$idType, $domainQn> = mutableMapOf()\n"
        if (autoGen) file += "    private var idSequence: $idType = $zeroLiteral\n"
        file += "\n"

        // Public lock helper: runs [block] with the backing store as receiver, under the mutex.
        file += "    /**\n"
        file += "     * Runs [block] while holding the internal mutex, with the backing store as the receiver. Use\n"
        file += "     * this to provide your own implementation for a method that could not be derived automatically,\n"
        file += "     * for example:\n"
        file += "     *\n"
        file += "     * ```\n"
        file += "     * override suspend fun executeSomething(...) = withStore { /* this: MutableMap */ ... }\n"
        file += "     * ```\n"
        file += "     */\n"
        file += "    public suspend fun <T> withStore(block: MutableMap<$idType, $domainQn>.() -> T): T =\n"
        file += "        mutex.withLock { store.block() }\n\n"

        // Test helpers.
        file += "    /** Removes all stored entities" + (if (autoGen) " and resets the id sequence." else ".") + " */\n"
        file += "    public suspend fun clear(): Unit = withStore {\n"
        file += "        this.clear()\n"
        if (autoGen) file += "        idSequence = $zeroLiteral\n"
        file += "    }\n\n"
        file += "    /** Returns a snapshot of all currently stored entities. */\n"
        file += "    public suspend fun findAllStored(): List<$domainQn> = withStore { values.toList() }\n\n"

        // --- Private map-extension helpers, invoked while the store is locked by [withStore]. ---
        file += "    private fun MutableMap<$idType, $domainQn>.doInsert(entity: $domainQn): $domainQn {\n"
        if (autoGen) {
            file += "        val id: $idType = idSequence + $oneLiteral\n"
            file += "        idSequence = id\n"
            file += "        val stored: $domainQn = entity.copy($idName = id)\n"
            file += "        this[id] = stored\n"
            file += "        return stored\n"
        } else {
            file += "        this[entity.$idName] = entity\n"
            file += "        return entity\n"
        }
        file += "    }\n\n"

        file += "    private fun MutableMap<$idType, $domainQn>.doUpdate(entity: $domainQn): Result<$domainQn> =\n"
        file += "        if (containsKey(entity.$idName)) {\n"
        file += "            this[entity.$idName] = entity\n"
        file += "            Result.success(entity)\n"
        file += "        } else {\n"
        file += "            Result.failure(SQLError(SQLError.Code.EmptyResultSet, \"Update affected 0 rows - entity not found\"))\n"
        file += "        }\n\n"

        // --- CRUD ---
        emitCrudMethods(file, repo, domainQn, idName, autoGen, zeroLiteral, useContext, resultType, toResult)

        // --- Custom @Query methods ---
        queryFns.forEach { fn ->
            emitQueryMethod(file, repo, fn, domainQn, idName, columns, useContext, resultType, toResult, implName)
        }

        file += "}\n"
        file.close()
    }

    /**
     * Emits the CRUD operations (insert, update, delete, save, batchInsert, batchUpdate), applying the
     * overridden pre/after/aroundQuery hooks the same way the main codegen does.
     */
    private fun emitCrudMethods(
        file: OutputStream,
        repo: KSClassDeclaration,
        domainQn: String,
        idName: String,
        autoGen: Boolean,
        zeroLiteral: String,
        useContext: Boolean,
        resultType: String,
        toResult: String,
    ) {
        // Check if aroundQuery hook is overridden (used in all CRUD operations)
        val hasAroundQueryHook = isHookOverridden(repo, "aroundQuery")

        // insert
        val hasPreInsertHook = isHookOverridden(repo, "preInsertHook")
        val hasAfterInsertHook = isHookOverridden(repo, "afterInsertHook")
        emitSignature(file, useContext, "insert", "entity: $domainQn", "$resultType<$domainQn>")
        if (!hasPreInsertHook && !hasAfterInsertHook && !hasAroundQueryHook) {
            file += " =\n        withStore { runCatching { doInsert(entity) } }$toResult\n\n"
        } else {
            val v = if (hasPreInsertHook) "e" else "entity"
            val body = wrapAround(hasAroundQueryHook, "insert", "withStore { runCatching { doInsert($v) } }")
            file += " = run {\n"
            if (hasPreInsertHook) file += "        val e = preInsertHook(context, entity)\n"
            file += "        $body"
            if (hasAfterInsertHook) file += ".map { afterInsertHook(context, it) }"
            file += "\n    }$toResult\n\n"
        }

        // update (in-memory keys by @Id, so the real processor's returned-id check is always satisfied)
        val hasPreUpdateHook = isHookOverridden(repo, "preUpdateHook")
        val hasAfterUpdateHook = isHookOverridden(repo, "afterUpdateHook")
        emitSignature(file, useContext, "update", "entity: $domainQn", "$resultType<$domainQn>")
        if (!hasPreUpdateHook && !hasAfterUpdateHook && !hasAroundQueryHook) {
            file += " =\n        withStore { doUpdate(entity) }$toResult\n\n"
        } else {
            val v = if (hasPreUpdateHook) "e" else "entity"
            val body = wrapAround(hasAroundQueryHook, "update", "withStore { doUpdate($v) }")
            file += " = run {\n"
            if (hasPreUpdateHook) file += "        val e = preUpdateHook(context, entity)\n"
            file += "        $body"
            if (hasAfterUpdateHook) file += ".map { afterUpdateHook(context, it) }"
            file += "\n    }$toResult\n\n"
        }

        // delete
        val hasPreDeleteHook = isHookOverridden(repo, "preDeleteHook")
        val hasAfterDeleteHook = isHookOverridden(repo, "afterDeleteHook")
        emitSignature(file, useContext, "delete", "entity: $domainQn", "$resultType<Unit>")
        val deleteFail = "Result.failure(SQLError(SQLError.Code.EmptyResultSet, \"Delete affected 0 rows - entity not found\"))"
        if (!hasPreDeleteHook && !hasAfterDeleteHook && !hasAroundQueryHook) {
            file += " =\n        withStore {\n"
            file += "            if (remove(entity.$idName) != null) Result.success(Unit)\n"
            file += "            else $deleteFail\n"
            file += "        }$toResult\n\n"
        } else {
            val v = if (hasPreDeleteHook) "e" else "entity"
            val op = "withStore { if (remove($v.$idName) != null) Result.success(Unit) else $deleteFail }"
            val body = wrapAround(hasAroundQueryHook, "delete", op)
            file += " = run {\n"
            if (hasPreDeleteHook) file += "        val e = preDeleteHook(context, entity)\n"
            file += "        $body"
            if (hasAfterDeleteHook) file += ".map { afterDeleteHook(context, $v); Unit }"
            file += "\n    }$toResult\n\n"
        }

        // save (delegates to insert/update, which already apply their hooks)
        emitSignature(file, useContext, "save", "entity: $domainQn", "$resultType<$domainQn>")
        val insertCall = if (useContext) "insert(entity)" else "insert(context, entity)"
        val updateCall = if (useContext) "update(entity)" else "update(context, entity)"
        if (autoGen) {
            file += " =\n        if (entity.$idName == $zeroLiteral) $insertCall else $updateCall\n\n"
        } else {
            file += " = run {\n"
            file += "        val exists = withStore { containsKey(entity.$idName) }\n"
            file += "        if (exists) $updateCall else $insertCall\n"
            file += "    }\n\n"
        }

        // batchInsert
        emitSignature(file, useContext, "batchInsert", "entities: Iterable<$domainQn>", "$resultType<List<$domainQn>>")
        if (!hasPreInsertHook && !hasAfterInsertHook && !hasAroundQueryHook) {
            file += " =\n        withStore { runCatching { entities.map { doInsert(it) } } }$toResult\n\n"
        } else {
            val items = if (hasPreInsertHook) "processed" else "entities"
            val body = wrapAround(hasAroundQueryHook, "batchInsert", "withStore { runCatching { $items.map { doInsert(it) } } }")
            file += " = run {\n"
            if (hasPreInsertHook) file += "        val processed = entities.map { preInsertHook(context, it) }\n"
            file += "        $body"
            if (hasAfterInsertHook) file += ".map { list -> list.map { afterInsertHook(context, it) } }"
            file += "\n    }$toResult\n\n"
        }

        // batchUpdate
        emitSignature(file, useContext, "batchUpdate", "entities: Iterable<$domainQn>", "$resultType<List<$domainQn>>")
        if (!hasPreUpdateHook && !hasAfterUpdateHook && !hasAroundQueryHook) {
            file += " =\n        withStore { runCatching { entities.map { doUpdate(it).getOrThrow() } } }$toResult\n\n"
        } else {
            val items = if (hasPreUpdateHook) "processed" else "entities"
            val body = wrapAround(hasAroundQueryHook, "batchUpdate", "withStore { runCatching { $items.map { doUpdate(it).getOrThrow() } } }")
            file += " = run {\n"
            if (hasPreUpdateHook) file += "        val processed = entities.map { preUpdateHook(context, it) }\n"
            file += "        $body"
            if (hasAfterUpdateHook) file += ".map { list -> list.map { afterUpdateHook(context, it) } }"
            file += "\n    }$toResult\n\n"
        }
    }

    /**
     * Wraps a core operation string in an `aroundQuery(method, statement) { ... }` call when the
     * aroundQuery hook is overridden. A synthetic [io.github.smyrgeorge.sqlx4k.Statement] carrying the
     * method name is passed, since the in-memory double executes no SQL.
     */
    private fun wrapAround(around: Boolean, method: String, op: String): String =
        if (around) "aroundQuery(\"$method\", Statement.create(\"$method\")) { $op }" else op

    /**
     * Checks whether a hook method is overridden in the repository interface or one of its supertypes
     * (excluding the base `CrudRepositoryHooks`). Mirrors the main codegen processor.
     */
    private fun isHookOverridden(repo: KSClassDeclaration, hookMethodName: String): Boolean {
        fun declares(decl: KSClassDeclaration): Boolean = decl.declarations
            .filterIsInstance<KSFunctionDeclaration>()
            .any { it.simpleName.asString() == hookMethodName }

        if (declares(repo)) return true

        fun checkSupertypes(decl: KSClassDeclaration, visited: MutableSet<String>): Boolean {
            val qualifiedName = decl.qualifiedName() ?: return false
            if (qualifiedName == TypeNames.CRUD_REPOSITORY_HOOKS) return false
            if (qualifiedName in visited) return false
            visited.add(qualifiedName)
            if (declares(decl)) return true
            for (superType in decl.superTypes) {
                val superDecl = superType.resolve().declaration as? KSClassDeclaration ?: continue
                if (checkSupertypes(superDecl, visited)) return true
            }
            return false
        }

        return checkSupertypes(repo, mutableSetOf())
    }

    /**
     * Emits a single custom `@Query` method: `findAll`, `countAll`, `deleteAll`, or a `findOneBy*` /
     * `findAllBy*` / `countBy*` / `deleteBy*` / `executeXxx`. The in-memory behavior is derived from the
     * method's `@Query` SQL (the `WHERE` clause becomes a predicate over the stored entities). Anything
     * that cannot be translated becomes an overridable stub that throws [NotImplementedError].
     */
    private fun emitQueryMethod(
        file: OutputStream,
        repo: KSClassDeclaration,
        fn: KSFunctionDeclaration,
        domainQn: String,
        idName: String,
        columns: Map<String, PropRef>,
        useContext: Boolean,
        resultType: String,
        toResult: String,
        implName: String,
    ) {
        val name = fn.simpleName.asString()
        val prefix = parseMethodPrefix(name)
        if (prefix == null) {
            logger.warn("[InMemoryRepositoryProcessor] '$name' does not match a known repository prefix; emitting a stub.")
        }

        // Render the visible parameter list (for the non-context style this includes `context`).
        val paramSig = fn.parameters.joinToString { p ->
            "${p.name?.asString() ?: "p"}: ${p.type}"
        }
        // Names of the query value parameters (the leading `context` is excluded in non-context style).
        val valueParams: Set<String> = (if (useContext) fn.parameters else fn.parameters.drop(1))
            .mapNotNull { it.name?.asString() }.toSet()

        val returnType = when (prefix) {
            Prefix.FIND_ALL, Prefix.FIND_ALL_BY -> "$resultType<List<$domainQn>>"
            Prefix.FIND_ONE_BY -> "$resultType<$domainQn?>"
            Prefix.DELETE_ALL, Prefix.DELETE_BY, Prefix.COUNT_ALL, Prefix.COUNT_BY, Prefix.EXECUTE, null ->
                "$resultType<Long>"
        }

        // Emit signature.
        if (useContext) file += "    context(context: QueryExecutor)\n"
        file += "    override suspend fun $name($paramSig): $returnType ="

        val core: String? = deriveCore(fn, prefix, valueParams, columns, idName)
        if (core == null) {
            // Could not be derived from the SQL (unsupported WHERE, execute of a non-CRUD statement,
            // unknown prefix, ...): emit an overridable stub.
            file += " throw NotImplementedError(\n"
            file += "        \"$implName.$name cannot be derived automatically by sqlx4k-codegen. \" +\n"
            file += "        \"Override it in a subclass of $implName to provide the in-memory behavior.\"\n"
            file += "    )\n\n"
        } else {
            val hasAroundQueryHook = isHookOverridden(repo, "aroundQuery")
            file += "\n        ${wrapAround(hasAroundQueryHook, name, core)}$toResult\n\n"
        }
    }

    /**
     * Builds the in-memory `withStore { ... }` body for a query method by parsing its `@Query` SQL,
     * or returns null (→ stub) when the statement or its `WHERE`/`SET` clauses cannot be translated.
     */
    private fun deriveCore(
        fn: KSFunctionDeclaration,
        prefix: Prefix?,
        valueParams: Set<String>,
        columns: Map<String, PropRef>,
        idName: String,
    ): String? {
        val sql = fn.annotations.firstOrNull { it.qualifiedName() == TypeNames.QUERY_ANNOTATION }
            ?.arguments?.firstOrNull { it.name?.asString() == "value" }?.value as? String
            ?: return null
        val statement = try {
            CCJSqlParserUtil.parse(sql)
        } catch (_: Exception) {
            return null
        }

        val multipleRows =
            "Result.failure(SQLError(SQLError.Code.MultipleRowsReturned, \"findOneBy query returned more than one row\"))"

        return when (prefix) {
            Prefix.FIND_ALL, Prefix.FIND_ALL_BY -> {
                val ps = statement as? PlainSelect ?: return null
                when (val where = analyzeWhere(ps.where, valueParams, columns)) {
                    Where.All -> "withStore { runCatching { values.toList() } }"
                    is Where.By -> "withStore { runCatching { values.filter { ${where.predicate} } } }"
                    Where.Unsupported -> null
                }
            }

            Prefix.FIND_ONE_BY -> {
                val ps = statement as? PlainSelect ?: return null
                val pred = when (val where = analyzeWhere(ps.where, valueParams, columns)) {
                    Where.All -> "true"
                    is Where.By -> where.predicate
                    Where.Unsupported -> return null
                }
                "withStore { val matches = values.filter { $pred }; if (matches.size > 1) $multipleRows else Result.success(matches.firstOrNull()) }"
            }

            Prefix.COUNT_ALL, Prefix.COUNT_BY -> {
                val ps = statement as? PlainSelect ?: return null
                when (val where = analyzeWhere(ps.where, valueParams, columns)) {
                    Where.All -> "withStore { runCatching { size.toLong() } }"
                    is Where.By -> "withStore { runCatching { values.count { ${where.predicate} }.toLong() } }"
                    Where.Unsupported -> null
                }
            }

            Prefix.DELETE_ALL, Prefix.DELETE_BY -> {
                val del = statement as? Delete ?: return null
                deleteCore(analyzeWhere(del.where, valueParams, columns), idName)
            }

            Prefix.EXECUTE -> when (statement) {
                is Update -> updateCore(statement, valueParams, columns, idName)
                is Delete -> deleteCore(analyzeWhere(statement.where, valueParams, columns), idName)
                else -> null
            }

            null -> null
        }
    }

    /** Builds a DELETE body from an analyzed WHERE clause, or null when the clause is unsupported. */
    private fun deleteCore(where: Where, idName: String): String? = when (where) {
        Where.All -> "withStore { runCatching { val removed = size.toLong(); this.clear(); removed } }"
        is Where.By ->
            "withStore { runCatching { val matched = values.filter { ${where.predicate} }; matched.forEach { remove(it.$idName) }; matched.size.toLong() } }"
        Where.Unsupported -> null
    }

    /** Builds an UPDATE body (SET assignments applied via copy) or null when it cannot be translated. */
    private fun updateCore(
        update: Update,
        valueParams: Set<String>,
        columns: Map<String, PropRef>,
        idName: String,
    ): String? {
        val setArgs = translateUpdateSets(update, valueParams, columns) ?: return null
        val selection = when (val where = analyzeWhere(update.where, valueParams, columns)) {
            Where.All -> "values.toList()"
            is Where.By -> "values.filter { ${where.predicate} }"
            Where.Unsupported -> return null
        }
        return "withStore { runCatching { val matched = $selection; matched.forEach { this[it.$idName] = it.copy($setArgs) }; matched.size.toLong() } }"
    }

    /** Translates an UPDATE's `SET col = value, ...` into `prop = value, ...` copy arguments. */
    private fun translateUpdateSets(
        update: Update,
        valueParams: Set<String>,
        columns: Map<String, PropRef>,
    ): String? {
        val parts = mutableListOf<String>()
        for (set in update.updateSets) {
            for (i in set.columns.indices) {
                val prop = columns[normalizeColumn(set.getColumn(i))] ?: return null
                val value = valueLiteral(set.getValue(i), valueParams, prop.typeQn) ?: return null
                parts += "${prop.property} = $value"
            }
        }
        return parts.takeIf { it.isNotEmpty() }?.joinToString(", ")
    }

    /** The result of analyzing a SQL `WHERE` clause for in-memory translation. */
    private sealed class Where {
        /** No WHERE clause — the operation applies to every stored entity. */
        object All : Where()

        /** A translated predicate over `it` (a stored entity). */
        data class By(val predicate: String) : Where()

        /** A WHERE clause exists but could not be translated — the caller emits a stub. */
        object Unsupported : Where()
    }

    private fun analyzeWhere(where: Expression?, valueParams: Set<String>, columns: Map<String, PropRef>): Where =
        if (where == null) Where.All
        else translateExpr(where, valueParams, columns)?.let { Where.By(it) } ?: Where.Unsupported

    /** Recursively translates a JSqlParser WHERE [Expression] into a Kotlin predicate over `it`, or null. */
    private fun translateExpr(e: Expression, valueParams: Set<String>, columns: Map<String, PropRef>): String? =
        when (e) {
            is ParenthesedExpressionList<*> ->
                e.singleOrNull()?.let { translateExpr(it, valueParams, columns) }?.let { "($it)" }
            is AndExpression -> combine(e, "&&", valueParams, columns)
            is OrExpression -> combine(e, "||", valueParams, columns)
            is IsNullExpression -> columnProp(e.leftExpression, columns)?.let {
                if (e.isNot) "it.${it.property} != null" else "it.${it.property} == null"
            }
            is EqualsTo -> binary(e, "==", "==", valueParams, columns)
            is NotEqualsTo -> binary(e, "!=", "!=", valueParams, columns)
            is GreaterThan -> binary(e, ">", "<", valueParams, columns)
            is GreaterThanEquals -> binary(e, ">=", "<=", valueParams, columns)
            is MinorThan -> binary(e, "<", ">", valueParams, columns)
            is MinorThanEquals -> binary(e, "<=", ">=", valueParams, columns)
            else -> null
        }

    private fun combine(e: BinaryExpression, op: String, valueParams: Set<String>, columns: Map<String, PropRef>): String? {
        val left = translateExpr(e.leftExpression, valueParams, columns) ?: return null
        val right = translateExpr(e.rightExpression, valueParams, columns) ?: return null
        return "($left $op $right)"
    }

    /**
     * Translates `column <op> value` (or `value <op> column`, reversing the operator). One side must be
     * an entity column and the other a named parameter or a supported literal.
     */
    private fun binary(
        e: BinaryExpression,
        op: String,
        reversedOp: String,
        valueParams: Set<String>,
        columns: Map<String, PropRef>,
    ): String? {
        val left = e.leftExpression
        val right = e.rightExpression
        val leftIsColumn = left is Column && !isBooleanLiteral(left)
        val rightIsColumn = right is Column && !isBooleanLiteral(right)
        return when {
            leftIsColumn && !rightIsColumn -> {
                val prop = columns[normalizeColumn(left)] ?: return null
                val value = valueLiteral(right, valueParams, prop.typeQn) ?: return null
                "it.${prop.property} $op $value"
            }
            rightIsColumn && !leftIsColumn -> {
                val prop = columns[normalizeColumn(right)] ?: return null
                val value = valueLiteral(left, valueParams, prop.typeQn) ?: return null
                "it.${prop.property} $reversedOp $value"
            }
            else -> null
        }
    }

    private fun columnProp(e: Expression, columns: Map<String, PropRef>): PropRef? =
        (e as? Column)?.let { columns[normalizeColumn(it)] }

    private fun isBooleanLiteral(e: Expression): Boolean =
        e is Column && e.columnName.lowercase() in setOf("true", "false")

    /** Renders a JSqlParser value node (named parameter or literal) as Kotlin source, or null. */
    private fun valueLiteral(e: Expression, valueParams: Set<String>, typeQn: String?): String? = when (e) {
        is JdbcNamedParameter -> e.name.takeIf { it in valueParams }
        is StringValue -> "\"" + e.value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
        is LongValue -> if (typeQn == TypeNames.KOTLIN_LONG) "${e.stringValue}L" else e.stringValue
        is DoubleValue -> e.value.toString()
        is NullValue -> "null"
        is Column -> e.columnName.lowercase().takeIf { it == "true" || it == "false" }
        else -> null
    }

    /** The name and type of a domain property, keyed by its SQL column name in [buildColumnMap]. */
    private data class PropRef(val property: String, val typeQn: String?)

    /**
     * Maps each entity column name (lowercased) to its property. The column is the explicit
     * `@Column(name = ...)` when present, otherwise the property name converted to snake_case — matching
     * the naming used by the main codegen.
     */
    private fun buildColumnMap(domainDecl: KSClassDeclaration): Map<String, PropRef> {
        val map = LinkedHashMap<String, PropRef>()
        domainDecl.getAllProperties().forEach { p ->
            val propName = p.simpleName.asString()
            val explicit = p.annotations
                .firstOrNull { it.qualifiedName() == TypeNames.COLUMN_ANNOTATION }
                ?.arguments?.firstOrNull { it.name?.asString() == "name" }
                ?.value as? String
            val column = if (explicit.isNullOrEmpty()) propName.toSnakeCase() else explicit
            val typeQn = p.type.resolve().declaration.qualifiedName()
            map[column.lowercase()] = PropRef(propName, typeQn)
        }
        return map
    }

    private fun normalizeColumn(column: Column): String =
        column.columnName.trim('"', '`', '[', ']').lowercase()

    private fun String.toSnakeCase(): String =
        replace("(?<=.)[A-Z]".toRegex(), "_$0").lowercase()

    /**
     * Emits the header of a CRUD method signature (context receiver + `override suspend fun ...`),
     * stopping right before the `=` so the caller can append the body.
     */
    private fun emitSignature(
        file: OutputStream,
        useContext: Boolean,
        name: String,
        params: String,
        returnType: String,
    ) {
        if (useContext) {
            file += "    context(context: QueryExecutor)\n"
            file += "    override suspend fun $name($params): $returnType"
        } else {
            val allParams = if (params.isEmpty()) "context: QueryExecutor" else "context: QueryExecutor, $params"
            file += "    override suspend fun $name($allParams): $returnType"
        }
    }

    /**
     * Holds the repository shape derived from its supertype: the domain type and whether the
     * repository uses context parameters and/or Arrow's `DbResult`.
     */
    private data class RepoInfo(
        val domain: KSClassDeclaration,
        val useContextParameters: Boolean,
        val useArrow: Boolean,
    )

    /**
     * Parses the repository interface to determine its base repository type (context/arrow flags) and
     * its domain type argument.
     */
    private fun parseRepositoryAnnotation(repo: KSClassDeclaration): RepoInfo {
        val foundRepoType = findCrudRepositorySuperType(repo)
            ?: error("${repo.qualifiedName()} must extend (directly or indirectly) one of ${TypeNames.REPOSITORY_TYPE_NAMES.joinToString { "$it<T>" }}")

        val useContext = foundRepoType.declaration.qualifiedName() in TypeNames.CONTEXT_REPOSITORY_TYPE_NAMES
        val useArrow = foundRepoType.declaration.qualifiedName() in TypeNames.ARROW_REPOSITORY_TYPE_NAMES

        val directSuperType = repo.superTypes.firstOrNull()?.resolve()
            ?: error("Repository interface ${repo.qualifiedName()} has no supertypes")
        val typeArg = directSuperType.arguments.firstOrNull()?.type?.resolve()
            ?: error("${repo.qualifiedName()} implements ${directSuperType.declaration.qualifiedName()} without a domain type argument")
        val domainDecl = typeArg.declaration as? KSClassDeclaration
            ?: error("Domain type argument must be a class on ${repo.qualifiedName()}")
        return RepoInfo(domainDecl, useContext, useArrow)
    }

    /**
     * Recursively searches the supertype hierarchy for one of the supported repository base types.
     */
    private fun findCrudRepositorySuperType(
        decl: KSClassDeclaration,
        visited: MutableSet<String> = mutableSetOf(),
    ): KSType? {
        val qualifiedName = decl.qualifiedName() ?: return null
        if (qualifiedName in visited) return null
        visited.add(qualifiedName)

        for (superType in decl.superTypes) {
            val resolved = superType.resolve()
            if (resolved.declaration.qualifiedName() in TypeNames.REPOSITORY_TYPE_NAMES) return resolved
        }
        for (superType in decl.superTypes) {
            val superDecl = superType.resolve().declaration as? KSClassDeclaration ?: continue
            val found = findCrudRepositorySuperType(superDecl, visited)
            if (found != null) return found
        }
        return null
    }

    /**
     * Collects fully qualified names of types referenced by query-method parameters and the entity's
     * `@Id` type that require an explicit import in the generated file.
     */
    private fun collectParameterTypeImports(
        functions: List<KSFunctionDeclaration>,
        idProp: KSPropertyDeclaration,
        outputPackage: String,
    ): Set<String> {
        val imports = mutableSetOf<String>()
        // Types already emitted as explicit imports in the generated file header.
        val alreadyImported = setOf(TypeNames.QUERY_EXECUTOR, TypeNames.SQL_ERROR)

        fun collectFromType(type: KSType) {
            val declaration = type.declaration as? KSClassDeclaration ?: return
            val qualifiedName = declaration.qualifiedName?.asString() ?: return
            val isBuiltinKotlinType = qualifiedName.startsWith("kotlin.") &&
                !qualifiedName.startsWith("kotlin.uuid.") &&
                !qualifiedName.startsWith("kotlin.time.")
            if (isBuiltinKotlinType || qualifiedName in alreadyImported || declaration.packageName.asString() == outputPackage) {
                type.arguments.forEach { arg -> arg.type?.resolve()?.let { collectFromType(it) } }
                return
            }
            imports.add(qualifiedName)
            type.arguments.forEach { arg -> arg.type?.resolve()?.let { collectFromType(it) } }
        }

        functions.forEach { fn -> fn.parameters.forEach { collectFromType(it.type.resolve()) } }
        collectFromType(idProp.type.resolve())
        return imports
    }

    /**
     * Valid repository method prefixes (mirrors the naming conventions of the main codegen processor).
     */
    private enum class Prefix {
        FIND_ONE_BY,
        FIND_ALL_BY,
        FIND_ALL,
        DELETE_BY,
        DELETE_ALL,
        COUNT_BY,
        COUNT_ALL,
        EXECUTE,
    }

    private fun parseMethodPrefix(name: String): Prefix? = when {
        name == "findAll" -> Prefix.FIND_ALL
        name == "deleteAll" -> Prefix.DELETE_ALL
        name == "countAll" -> Prefix.COUNT_ALL
        name.startsWith("findAllBy") -> Prefix.FIND_ALL_BY
        name.startsWith("findOneBy") -> Prefix.FIND_ONE_BY
        name.startsWith("deleteBy") -> Prefix.DELETE_BY
        name.startsWith("countBy") -> Prefix.COUNT_BY
        name.startsWith("execute") -> Prefix.EXECUTE
        else -> null
    }

    private operator fun OutputStream.plusAssign(str: String): Unit = write(str.toByteArray())
    private fun KSDeclaration.qualifiedName(): String? = qualifiedName?.asString()
    private fun KSClassDeclaration.simpleName(): String = simpleName.asString()
    private fun KSAnnotation.qualifiedName(): String? = annotationType.resolve().declaration.qualifiedName?.asString()

    companion object {
        /** KSP option: package the generated in-memory doubles are emitted into. */
        private const val PACKAGE_OPTION = "output-package"
    }
}
