package io.github.smyrgeorge.sqlx4k.processor

import net.sf.jsqlparser.parser.CCJSqlParserUtil
import net.sf.jsqlparser.statement.alter.Alter
import net.sf.jsqlparser.statement.create.table.CreateTable
import org.apache.calcite.adapter.java.JavaTypeFactory
import org.apache.calcite.config.CalciteConnectionConfigImpl
import org.apache.calcite.config.CalciteConnectionProperty
import org.apache.calcite.jdbc.CalciteSchema
import org.apache.calcite.jdbc.JavaTypeFactoryImpl
import org.apache.calcite.prepare.CalciteCatalogReader
import org.apache.calcite.rel.type.RelDataType
import org.apache.calcite.rel.type.RelDataTypeFactory
import org.apache.calcite.schema.impl.AbstractTable
import org.apache.calcite.sql.`fun`.SqlStdOperatorTable
import org.apache.calcite.sql.parser.SqlParser
import org.apache.calcite.sql.validate.SqlConformanceEnum
import org.apache.calcite.sql.validate.SqlValidator
import org.apache.calcite.sql.validate.SqlValidatorCatalogReader
import org.apache.calcite.sql.validate.SqlValidatorUtil
import org.apache.calcite.sql.validate.SqlValidatorWithHints
import java.io.File
import java.math.BigDecimal
import java.util.*

object QueryValidator {
    private lateinit var schema: Map<String, TableDef>
    private lateinit var tables: List<TableDef>
    private lateinit var validator: SqlValidator

    fun load(path: String) {
        val dir = File(path)
        if (!dir.exists()) error("Schema directory does not exist: $path")
        if (!dir.isDirectory) error("Schema directory is not a directory: $path")

        val files = dir.listFiles()
            ?.filter { it.isFile && it.extension == "sql" }
            ?: error("Cound not list schema files in directory: $path")

        val schema = mutableMapOf<String, TableDef>()

        files.map { file ->
            val sql = file.readText()
            CCJSqlParserUtil.parseStatements(sql).forEach { stmt ->

                when (stmt) {
                    is CreateTable -> {
                        val cols = stmt.columnDefinitions.map { ColumnDef(it.columnName, it.colDataType.dataType) }
                        schema[stmt.table.name.lowercase()] = TableDef(stmt.table.name, cols.toMutableList())
                    }

                    is Alter -> {
                        val tableName = stmt.table.name.lowercase()
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

                    else -> {
                        println("⚠️ Skipping unsupported statement: ${stmt.javaClass.simpleName}")
                    }
                }
            }
        }

        this.schema = schema
        this.tables = schema.values.toList()
        this.validator = createCalciteValidator()
    }

    private fun createCalciteValidator(): SqlValidatorWithHints {
        val rootSchema = CalciteSchema.createRootSchema(true).apply {
            val root = this
            tables.forEach { t ->
                root.add(t.name, MigrationTable(t.columns))
            }
        }

        val typeFactory: JavaTypeFactory = JavaTypeFactoryImpl()

        val props = Properties().apply {
            this[CalciteConnectionProperty.CASE_SENSITIVE.camelName()] = "false"
        }

        val config = CalciteConnectionConfigImpl(props)

        val catalogReader: SqlValidatorCatalogReader = CalciteCatalogReader(
            /* rootSchema = */ rootSchema,
            /* defaultSchema = */ listOf(), // search path (empty = root)
            /* typeFactory = */ typeFactory,
            /* config = */ config
        )

        val validator: SqlValidatorWithHints = SqlValidatorUtil.newValidator(
            /* opTab = */ SqlStdOperatorTable.instance(),
            /* catalogReader = */ catalogReader,
            /* typeFactory = */ typeFactory,
            /* config = */ SqlValidator.Config.DEFAULT
                .withConformance(SqlConformanceEnum.STRICT_2003)
                .withTypeCoercionEnabled(false) // disable implicit casts
        )
        return validator
    }

    fun validateQuery(sql: String) {
        validator.validate(SqlParser.create(sql).parseStmt())
    }

    data class ColumnDef(val name: String, val type: String)
    data class TableDef(val name: String, val columns: MutableList<ColumnDef>)
    class MigrationTable(private val columns: List<ColumnDef>) : AbstractTable() {
        override fun getRowType(typeFactory: RelDataTypeFactory): RelDataType {
            val builder = typeFactory.builder()
            for (col in columns) {
                val type = when (col.type.uppercase()) {
                    "INT", "INTEGER" -> typeFactory.createJavaType(Int::class.java)
                    "VARCHAR", "TEXT" -> typeFactory.createJavaType(String::class.java)
                    "DECIMAL" -> typeFactory.createJavaType(BigDecimal::class.java)
                    else -> typeFactory.createJavaType(String::class.java) // fallback
                }
                builder.add(col.name, type)
            }
            return builder.build()
        }
    }
}