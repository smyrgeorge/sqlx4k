package io.github.smyrgeorge.sqlx4k.processor

import net.sf.jsqlparser.parser.CCJSqlParserUtil
import net.sf.jsqlparser.statement.alter.Alter
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
import org.apache.calcite.sql.`fun`.SqlStdOperatorTable
import org.apache.calcite.sql.parser.SqlParser
import org.apache.calcite.sql.type.SqlTypeName
import org.apache.calcite.sql.validate.SqlValidator
import org.apache.calcite.sql.validate.SqlValidatorCatalogReader
import org.apache.calcite.sql.validate.SqlValidatorUtil
import org.apache.calcite.sql.validate.SqlValidatorWithHints
import java.io.File
import java.util.*

object QueryValidator {
    private lateinit var tables: List<TableDef>
    private lateinit var validator: SqlValidator

    fun validateQuerySyntax(fn: String, sql: String) {
        try {
            CCJSqlParserUtil.parse(sql)
        } catch (e: Exception) {
            val cause = e.message?.removePrefix("net.sf.jsqlparser.parser.ParseException: ")
            error("Invalid SQL in function $fn: $cause")
        }
    }

    fun validateQuerySchema(sql: String) {
        // Use a lex that preserves/lowercases identifiers (avoid automatic UPPER-casing)
        val config = SqlParser.Config.DEFAULT.withLex(Lex.JAVA)
        validator.validate(SqlParser.create(sql, config).parseStmt())
    }

    fun load(path: String) {
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
                            when (expr.operation.name) {
                                // ADD COLUMN
                                "ADD" -> {
                                    expr.colDataTypeList?.forEach { colType ->
                                        val colName = expr.columnName
                                        table.columns.add(ColumnDef(colName, colType.colDataType.dataType))
                                    }
                                }

                                // DROP COLUMN
                                "DROP" -> {
                                    val colName = expr.columnName
                                    table.columns.removeIf { it.name.equals(colName, ignoreCase = true) }
                                }

                                else -> {
                                    println("⚠️ Unsupported ALTER operation: ${expr.operation}")
                                }
                            }
                        }
                    }

                    is Drop -> {
                        val tableName = stmt.name.name
                        val res = schema.remove(tableName)
                        if (res == null) println("⚠️ DROP TABLE on unknown table $tableName")
                    }

                    else -> {
                        println("⚠️ Skipping unsupported statement: ${stmt.javaClass.simpleName}")
                    }
                }
            }
        }

        this.tables = schema.values.toList()
        this.validator = createCalciteValidator()
    }

    private fun createCalciteValidator(): SqlValidatorWithHints {
        val rootSchema = CalciteSchema.createRootSchema(true).apply {
            tables.forEach { t ->
                this.add(t.name, MigrationTable(t.columns))
            }
        }

        val typeFactory: JavaTypeFactory = JavaTypeFactoryImpl()

        val catalogReader: SqlValidatorCatalogReader = CalciteCatalogReader(
            /* rootSchema = */ rootSchema,
            /* defaultSchema = */ listOf(), // search path (empty = root)
            /* typeFactory = */ typeFactory,
            /* config = */ CalciteConnectionConfigImpl(Properties())
        )

        val validator: SqlValidatorWithHints = SqlValidatorUtil.newValidator(
            /* opTab = */ SqlStdOperatorTable.instance(),
            /* catalogReader = */ catalogReader,
            /* typeFactory = */ typeFactory,
            /* config = */ SqlValidator.Config.DEFAULT
        )
        return validator
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
                        typeFactory.createSqlType(SqlTypeName.VARCHAR, Integer.MAX_VALUE)

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
                        typeFactory.createSqlType(SqlTypeName.VARBINARY, Integer.MAX_VALUE)

                    // JSON and similar complex types mapped to text
                    "JSON", "JSONB", "ENUM" -> typeFactory.createSqlType(SqlTypeName.VARCHAR, Integer.MAX_VALUE)

                    // Money and another numeric-ish
                    "MONEY" -> typeFactory.createSqlType(SqlTypeName.DECIMAL, 19, 4)
                    else -> {
                        println("⚠️ Unsupported column type (fallback to string): $t")
                        typeFactory.createSqlType(SqlTypeName.VARCHAR, 1_000)
                    }
                }
                builder.add(col.name, type)
            }
            return builder.build()
        }
    }
}