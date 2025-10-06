package io.github.smyrgeorge.sqlx4k.processor

import io.github.smyrgeorge.sqlx4k.Statement
import net.sf.jsqlparser.parser.CCJSqlParserUtil
import net.sf.jsqlparser.statement.alter.Alter
import net.sf.jsqlparser.statement.alter.AlterOperation
import net.sf.jsqlparser.statement.create.table.CreateTable
import net.sf.jsqlparser.statement.drop.Drop
import org.apache.calcite.adapter.java.JavaTypeFactory
import org.apache.calcite.config.CalciteConnectionConfigImpl
import org.apache.calcite.config.Lex
import org.apache.calcite.jdbc.CalciteSchema
import org.apache.calcite.jdbc.JavaTypeFactoryImpl
import org.apache.calcite.prepare.CalciteCatalogReader
import org.apache.calcite.rel.type.RelDataType
import org.apache.calcite.rel.type.RelDataTypeFactory
import org.apache.calcite.schema.impl.AbstractTable
import org.apache.calcite.sql.*
import org.apache.calcite.sql.`fun`.SqlStdOperatorTable
import org.apache.calcite.sql.parser.SqlParser
import org.apache.calcite.sql.type.SqlTypeName
import org.apache.calcite.sql.util.SqlBasicVisitor
import org.apache.calcite.sql.validate.SqlConformanceEnum
import org.apache.calcite.sql.validate.SqlValidator
import org.apache.calcite.sql.validate.SqlValidatorCatalogReader
import org.apache.calcite.sql.validate.SqlValidatorUtil
import java.io.File
import java.util.*

/**
 * A utility object that provides functionality for SQL validation and schema handling.
 * This includes methods for checking SQL query syntax, validating against schemas,
 * managing schemas from definition files, and ensuring data type consistency during validation.
 */
object SqlValidator {
    private const val VALIDATE_LITERAL_TYPES = false
    private lateinit var tables: List<TableDef>
    private lateinit var validator: SqlValidator

    private val calciteParserConfig = SqlParser.Config.DEFAULT
        .withConformance(SqlConformanceEnum.STRICT_2003)
        .withLex(Lex.JAVA) // Avoid automatic UPPER-casing

    /**
     * Validates the syntax of an SQL query by attempting to parse it.
     * If the SQL syntax is invalid, an error is thrown with a message indicating the issue.
     *
     * @param fn A string representing the context or identifier of the function where the validation is being invoked.
     * This is used for naming the source of the error in the error message.
     * @param sql The SQL query string to be validated for correct syntax.
     */
    fun validateQuerySyntax(fn: String, sql: String) {
        try {
            CCJSqlParserUtil.parse(sql)
        } catch (e: Exception) {
            val cause = e.message?.removePrefix("net.sf.jsqlparser.parser.ParseException: ")
            error("Invalid SQL syntax ($fn): $cause")
        }
    }

    /**
     * Validates the provided SQL query schema against Calcite's SQL parser and validator.
     * Optionally applies custom literal type checks if the `VALIDATE_LITERAL_TYPES` flag is enabled.
     *
     * @param fn A string indicating the source or context of the SQL query (e.g., function name or identifier).
     * @param sql The SQL query string to be validated.
     */
    fun validateQuerySchema(fn: String, sql: String) {
        fun String.convertNamedParametersToPositional(): String {
            var s = this
            Statement.create(this).extractedNamedParameters.forEach { s = s.replace(":$it", "?") }
            return s
        }

        try {
            val sql = if (VALIDATE_LITERAL_TYPES) sql else sql.convertNamedParametersToPositional()
            require(sql.isNotBlank()) { "SQL query is blank" }

            val parser = SqlParser.create(sql, calciteParserConfig)
            val sqlNode = parser.parseStmt()

            // Standard Calcite validation.
            val validatedNode = validator.validate(sqlNode)

            // Custom literal type checking.
            if (VALIDATE_LITERAL_TYPES) validateLiteralTypes(validatedNode)
        } catch (e: Exception) {
            val cause = e.message ?: "Unknown error"
            error("Invalid SQL ($fn): $cause")
        }
    }

    /**
     * Loads SQL schema definitions from the specified directory, processes each `.sql` file
     * encountered, and constructs a representation of the database schema consisting of tables
     * and their columns.
     *
     * Each file in the directory is expected to follow a specific naming convention:
     * `<version>_<name>.sql` (e.g., `001_create_users.sql`). Files are processed in order of
     * their version prefixes, starting from the lowest.
     *
     * @param path The file system path to the directory containing `.sql` schema files.
     *             It must be an existing directory, and all `.sql` files inside should adhere
     *             to the required naming convention and contain valid SQL statements.
     */
    fun loadSchema(path: String) {
        fun parseFileName(name: String): Long {
            val fileNamePattern = Regex("""^\s*(\d+)_([A-Za-z0-9._-]+)\.sql\s*$""")
            val name = name.trim()
            val match = fileNamePattern.matchEntire(name)
                ?: error("Migration filename must be <version>_<name>.sql, got $name")
            val (versionStr, _) = match.destructured
            val version = versionStr.toLongOrNull()
                ?: error("Invalid version prefix in migration filename: $name")
            return version
        }

        val dir = File(path)
        if (!dir.exists()) error("Schema directory does not exist: $path")
        if (!dir.isDirectory) error("Schema directory is not a directory: $path")

        val files = dir.listFiles()
            ?.filter { it.isFile && it.extension == "sql" }
            ?.map { parseFileName(it.name) to it }
            ?.sortedBy { it.first }
            ?.map { it.second }
            ?: error("Cound not list schema files in directory: $path")

        val schema = mutableMapOf<String, TableDef>()

        files.map { file ->
            val sql = file.readText()
            CCJSqlParserUtil.parseStatements(sql).forEach { stmt ->

                when (stmt) {
                    is CreateTable -> {
                        val cols = stmt.columnDefinitions.map { ColumnDef(it.columnName, it.colDataType.dataType) }
                        schema[stmt.table.name] = TableDef(stmt.table.name, cols.toMutableList())
                    }

                    is Alter -> {
                        val tableName = stmt.table.name
                        val table = schema[tableName] ?: error("ALTER TABLE on unknown table $tableName")

                        stmt.alterExpressions.forEach { expr ->
                            when (expr.operation) {
                                // ADD COLUMN
                                AlterOperation.ADD -> {
                                    expr.colDataTypeList?.forEach { colType ->
                                        val colName = expr.columnName
                                        table.columns.add(ColumnDef(colName, colType.colDataType.dataType))
                                    }
                                }

                                // DROP COLUMN
                                AlterOperation.DROP -> {
                                    val colName = expr.columnName
                                    table.columns.removeIf { it.name.equals(colName, ignoreCase = true) }
                                }

                                else -> println("⚠️ Unsupported ALTER operation: ${expr.operation}")
                            }
                        }
                    }

                    is Drop -> schema.remove(stmt.name.name)
                    else -> Unit
                }
            }
        }

        tables = schema.values.toList()
        validator = createCalciteValidator()
    }

    private fun createCalciteValidator(): SqlValidator {
        val rootSchema = CalciteSchema.createRootSchema(true).apply {
            tables.forEach { add(it.name, MigrationTable(it.columns)) }
        }

        val typeFactory: JavaTypeFactory = JavaTypeFactoryImpl()
        val catalogReader: SqlValidatorCatalogReader = CalciteCatalogReader(
            /* rootSchema = */ rootSchema,
            /* defaultSchema = */ listOf(), // search path (empty = root)
            /* typeFactory = */ typeFactory,
            /* config = */ CalciteConnectionConfigImpl(Properties())
        )

        val config = SqlValidator.Config.DEFAULT
            .withConformance(SqlConformanceEnum.STRICT_2003)
            .withTypeCoercionEnabled(false)

        return SqlValidatorUtil.newValidator(
            /* opTab = */ SqlStdOperatorTable.instance(),
            /* catalogReader = */ catalogReader,
            /* typeFactory = */ typeFactory,
            /* config = */ config
        )
    }

    private fun validateLiteralTypes(query: SqlNode) {
        fun checkLiteralAgainstType(literal: SqlNode, targetType: RelDataType, context: SqlCall) {
            if (literal !is SqlLiteral) return
            val literalTypeName = literal.typeName
            val targetTypeName = targetType.sqlTypeName

            // Check for incompatible types
            val isIncompatible = literalTypeName.family != targetTypeName.family
            if (isIncompatible) {
                val position = literal.parserPosition
                val contextSql = context.toSqlString(null, false)
                error("Type mismatch: cannot compare column of type $targetTypeName with literal of type $literalTypeName (sql: $contextSql, value: '${literal.toValue()}') at line ${position?.lineNum ?: "?"}, column ${position?.columnNum ?: "?"}")
            }
        }

        fun checkTypeCompatibility(left: SqlNode?, right: SqlNode?, validator: SqlValidator, call: SqlCall) {
            if (left == null || right == null) return
            val leftType = validator.getValidatedNodeType(left) ?: return
            val rightType = validator.getValidatedNodeType(right) ?: return

            // Check if one side is a literal and the other is a column/identifier
            when {
                right is SqlLiteral && left is SqlIdentifier -> checkLiteralAgainstType(right, leftType, call)
                left is SqlLiteral && right is SqlIdentifier -> checkLiteralAgainstType(left, rightType, call)
                // Both are literals, or both are columns - let Calcite handle it
                else -> {}
            }
        }

        query.accept(object : SqlBasicVisitor<Void?>() {
            override fun visit(call: SqlCall): Void? {
                when (call.operator.kind) {
                    SqlKind.EQUALS,
                    SqlKind.NOT_EQUALS,
                    SqlKind.LESS_THAN,
                    SqlKind.LESS_THAN_OR_EQUAL,
                    SqlKind.GREATER_THAN,
                    SqlKind.GREATER_THAN_OR_EQUAL -> {
                        val operands = call.operandList
                        if (operands.size == 2) {
                            checkTypeCompatibility(operands[0], operands[1], validator, call)
                        }
                    }

                    SqlKind.IN -> {
                        val operands = call.operandList
                        if (operands.size >= 2) {
                            val columnType = validator.getValidatedNodeType(operands[0])
                            if (columnType != null) {
                                for (i in 1 until operands.size) {
                                    checkLiteralAgainstType(operands[i], columnType, call)
                                }
                            }
                        }
                    }

                    else -> {}
                }

                return super.visit(call)
            }
        })
    }

    data class ColumnDef(val name: String, val type: String)
    data class TableDef(val name: String, val columns: MutableList<ColumnDef>)
    data class MigrationTable(private val columns: List<ColumnDef>) : AbstractTable() {
        override fun getRowType(typeFactory: RelDataTypeFactory): RelDataType {
            val builder = typeFactory.builder()
            for (col in columns) {
                val type = when (val t = col.type.trim().uppercase()) {
                    // Integer family
                    "INT", "INTEGER", "INT4" -> typeFactory.createSqlType(SqlTypeName.INTEGER)
                    "SMALLINT", "INT2" -> typeFactory.createSqlType(SqlTypeName.SMALLINT)
                    "BIGINT", "INT8" -> typeFactory.createSqlType(SqlTypeName.BIGINT)
                    "TINYINT" -> typeFactory.createSqlType(SqlTypeName.TINYINT)
                    "MEDIUMINT" -> typeFactory.createSqlType(SqlTypeName.INTEGER)
                    "SERIAL" -> typeFactory.createSqlType(SqlTypeName.INTEGER)
                    "BIGSERIAL" -> typeFactory.createSqlType(SqlTypeName.BIGINT)

                    // Boolean
                    "BOOLEAN", "BOOL" -> typeFactory.createSqlType(SqlTypeName.BOOLEAN)

                    // Text/char family
                    "CHAR", "CHARACTER", "NCHAR" -> typeFactory.createSqlType(SqlTypeName.CHAR, 1_000)
                    "VARCHAR", "CHARACTER VARYING", "NVARCHAR", "TEXT", "TINYTEXT", "MEDIUMTEXT", "LONGTEXT", "CLOB" ->
                        typeFactory.createSqlType(SqlTypeName.VARCHAR, Int.MAX_VALUE)

                    // UUID
                    "UUID" -> typeFactory.createSqlType(SqlTypeName.CHAR, 36)

                    // Decimal / numeric
                    "DECIMAL", "NUMERIC" -> typeFactory.createSqlType(SqlTypeName.DECIMAL, 38, 19)

                    // Floating point
                    "REAL", "FLOAT4" -> typeFactory.createSqlType(SqlTypeName.REAL)
                    "FLOAT" -> typeFactory.createSqlType(SqlTypeName.FLOAT)
                    "DOUBLE", "DOUBLE PRECISION", "FLOAT8" -> typeFactory.createSqlType(SqlTypeName.DOUBLE)

                    // Temporal
                    "DATE" -> typeFactory.createSqlType(SqlTypeName.DATE)
                    "TIME" -> typeFactory.createSqlType(SqlTypeName.TIME)
                    "TIMESTAMP" -> typeFactory.createSqlType(SqlTypeName.TIMESTAMP)
                    "TIMESTAMPTZ", "TIMESTAMP WITH TIME ZONE" -> typeFactory.createSqlType(SqlTypeName.TIMESTAMP_WITH_LOCAL_TIME_ZONE)

                    // Binary / blob
                    "BYTEA", "BLOB", "LONGBLOB", "MEDIUMBLOB", "TINYBLOB", "BINARY", "VARBINARY" ->
                        typeFactory.createSqlType(SqlTypeName.VARBINARY, Int.MAX_VALUE)

                    // JSON and similar complex types mapped to text
                    "JSON", "JSONB", "ENUM" -> typeFactory.createSqlType(SqlTypeName.VARCHAR, Int.MAX_VALUE)

                    // Money and another numeric-ish
                    "MONEY" -> typeFactory.createSqlType(SqlTypeName.DECIMAL, 19, 4)
                    else -> {
                        println("⚠️ Unsupported column type (fallback to string): $t")
                        typeFactory.createSqlType(SqlTypeName.VARCHAR, Int.MAX_VALUE)
                    }
                }
                builder.add(col.name, type)
            }
            return builder.build()
        }
    }
}