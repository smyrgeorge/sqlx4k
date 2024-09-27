@file:Suppress("UnnecessaryVariable")

package io.github.smyrgeorge.sqlx4k.processor

import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.Dependencies
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSAnnotation
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSName
import com.google.devtools.ksp.symbol.KSPropertyDeclaration
import com.google.devtools.ksp.symbol.KSValueArgument
import com.google.devtools.ksp.symbol.KSVisitorVoid
import com.google.devtools.ksp.validate
import java.io.OutputStream

class TableProcessor(
    private val options: Map<String, String>,
    private val logger: KSPLogger,
    private val codeGenerator: CodeGenerator,
) : SymbolProcessor {

    override fun process(resolver: Resolver): List<KSAnnotated> {
        val symbols = resolver
            .getSymbolsWithAnnotation(TABLE_ANNOTATION)
            .filterIsInstance<KSClassDeclaration>()

        if (!symbols.iterator().hasNext()) return emptyList()

        val outputPackage = options[PACKAGE_OPTION] ?: error("Missing $PACKAGE_OPTION option")
        val outputFilename = options[FILENAME_OPTION] ?: "GeneratedQueries"

        val file: OutputStream = codeGenerator.createNewFile(
            // Make sure to associate the generated file with sources to keep/maintain it across incremental builds.
            // Learn more about incremental processing in KSP from the official docs:
            // https://kotlinlang.org/docs/ksp-incremental.html
            dependencies = Dependencies(false, *resolver.getAllFiles().toList().toTypedArray()),
            packageName = outputPackage,
            fileName = outputFilename
        )

        file += "package $outputPackage\n"
        file += "\nimport io.github.smyrgeorge.sqlx4k.Statement\n"

        // Processing each class declaration, annotated with @Function.
        symbols.forEach { it.accept(Visitor(file), Unit) }

        // Don't forget to close the out stream.
        file.close()

        val unableToProcess = symbols.filterNot { it.validate() }.toList()
        return unableToProcess
    }

    inner class Visitor(private val file: OutputStream) : KSVisitorVoid() {

        override fun visitClassDeclaration(classDeclaration: KSClassDeclaration, data: Unit) {
            // Getting the @Table annotation object.
            val table: KSAnnotation = classDeclaration.annotations.first {
                it.name() == TABLE_ANNOTATION_NAME
            }

            // Getting the 'name' argument object from the @Table.
            val nameArgument: KSValueArgument = table.arguments
                .first { arg -> arg.name?.asString() == "name" }

            // Getting the value of the 'name' argument.
            val tableName = nameArgument.value as String

            // Getting the list of member properties of the annotated class.
            val properties: Sequence<KSPropertyDeclaration> = classDeclaration
                .getAllProperties()
                .filter { it.validate() }

            // Make queries.
            insert(tableName, classDeclaration, properties)
            update(tableName, classDeclaration, properties)
            delete(tableName, classDeclaration, properties)
        }

        /**
         * Generates an SQL insert statement for the provided class declaration and properties.
         *
         * @param clazz The class declaration from which to generate the insert statement.
         * @param table The name of the table into which the data will be inserted.
         * @param props The sequence of properties from the class, which will be filtered
         *                    to include only those that should be inserted.
         */
        private fun insert(
            table: String,
            clazz: KSClassDeclaration,
            props: Sequence<KSPropertyDeclaration>
        ) {
            // Find insertable properties.
            @Suppress("NAME_SHADOWING")
            val props = props
                .filter {
                    val id = it.annotations.find { a -> a.name() == ID_ANNOTATION_NAME }
                        ?: return@filter true

                    val insert: KSValueArgument = id.arguments.first { a ->
                        a.name?.asString() == INSERT_PROPERTY_NAME
                    }

                    (insert.value as? Boolean) ?: false
                }
                .filter {
                    val id = it.annotations.find { a -> a.name() == COLUMN_ANNOTATION_NAME }
                        ?: return@filter true

                    val insert: KSValueArgument = id.arguments.first { a ->
                        a.name?.asString() == INSERT_PROPERTY_NAME
                    }

                    (insert.value as? Boolean) ?: true
                }
                .map { it.name() }

            file += "\n"
            file += "actual fun ${clazz.name()}.insert(): Statement {\n"
            file += "    val sql = \"insert into $table(${props.map { it.toSnakeCase() }.joinToString()})"
            file += " values (${props.joinToString { "?" }})"
            file += " returning $table.*;\"\n"
            file += "    val statement = Statement.create(sql)\n"
            props.forEachIndexed { index, property ->
                file += "    statement.bind($index, ${property})\n"
            }
            file += "    return statement\n"
            file += "}\n"
        }

        /**
         * Generates an SQL update statement for the given class declaration and properties.
         *
         * @param clazz The class declaration from which to generate the update statement.
         * @param table The name of the table to be updated.
         * @param props The sequence of properties from the class, which will be filtered to include
         *                   only those that should be updated.
         */
        private fun update(
            table: String,
            clazz: KSClassDeclaration,
            props: Sequence<KSPropertyDeclaration>
        ) {
            val id: KSPropertyDeclaration = props.find {
                it.annotations.any { a -> a.name() == ID_ANNOTATION_NAME }
            } ?: run {
                logger.warn("Skipping $table.update() because no property found annotated with @$ID_ANNOTATION_NAME.")
                return
            }

            // Find updatable properties.
            @Suppress("NAME_SHADOWING")
            val props = props
                // Exclude @Id from the update query,
                .filter { it.name() == id.name() }
                .filter {
                    val id = it.annotations.find { a ->
                        a.name() == COLUMN_ANNOTATION_NAME
                    } ?: return@filter true

                    val update: KSValueArgument = id.arguments.first { a ->
                        a.name?.asString() == UPDATE_PROPERTY_NAME
                    }

                    (update.value as? Boolean) ?: true
                }
                .map { it.name() }

            file += "\n"
            file += "actual fun ${clazz.name()}.update(): Statement {\n"
            file += "    val sql = \"update $table"
            file += " set ${props.joinToString { p -> "${p.toSnakeCase()} = ?" }}"
            file += " where ${id.name().toSnakeCase()} = ?"
            file += " returning $table.*;\"\n"

            file += "    val statement = Statement.create(sql)\n"
            props.forEachIndexed { index, property ->
                file += "    statement.bind($index, ${property})\n"
            }
            file += "    statement.bind(${props.toList().size}, ${id.name()})\n"
            file += "    return statement\n"
            file += "}\n"
        }

        /**
         * Generates an SQL delete statement for the provided class declaration based on its properties.
         *
         * @param clazz The class declaration from which to generate the delete statement.
         * @param table The name of the table to be deleted from.
         * @param props The sequence of properties from the class, used to identify the primary key.
         */
        private fun delete(
            table: String,
            clazz: KSClassDeclaration,
            props: Sequence<KSPropertyDeclaration>
        ) {
            val id: KSPropertyDeclaration = props.find {
                it.annotations.any { a -> a.name() == ID_ANNOTATION_NAME }
            } ?: run {
                logger.warn("Skipping $table.delete() because no property found annotated with @$ID_ANNOTATION_NAME.")
                return
            }

            file += "\n"
            file += "actual fun ${clazz.name()}.delete(): Statement {\n"
            file += "    val sql = \"delete from $table where ${id.name().toSnakeCase()} = ?;\"\n"
            file += "    val statement = Statement.create(sql)\n"
            file += "    statement.bind(0, ${id.name()})\n"
            file += "    return statement\n"
            file += "}\n"
        }
    }

    private fun KSClassDeclaration.name(): String = simpleName.name()
    private fun KSPropertyDeclaration.name(): String = simpleName.name()
    private fun KSAnnotation.name(): String = shortName.asString()
    private fun KSName.name(): String = getShortName()
    private fun String.toSnakeCase(): String {
        val pattern = "(?<=.)[A-Z]".toRegex()
        return replace(pattern, "_$0").lowercase()
    }

    operator fun OutputStream.plusAssign(str: String) {
        write(str.toByteArray())
    }

    companion object {
        /**
         * Key used to specify the package name for the generated output classes.
         */
        const val PACKAGE_OPTION = "output-package"

        /**
         * The option key used to specify the output filename for the generated SQL classes.
         */
        const val FILENAME_OPTION = "output-filename"

        const val INSERT_PROPERTY_NAME = "insert"
        const val UPDATE_PROPERTY_NAME = "update"
        const val COLUMN_ANNOTATION_NAME = "Column"
        const val ID_ANNOTATION_NAME = "Id"
        const val TABLE_ANNOTATION_NAME = "Table"
        const val TABLE_ANNOTATION = "io.github.smyrgeorge.sqlx4k.annotation.Table"
    }
}
