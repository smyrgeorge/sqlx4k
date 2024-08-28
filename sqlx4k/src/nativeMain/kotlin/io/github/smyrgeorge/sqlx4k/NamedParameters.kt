package io.github.smyrgeorge.sqlx4k

@Suppress("unused")
object NamedParameters {
    private val nameParameterRegex = Regex("""(?<!:):(?!:)[a-zA-Z]\w+""")
    private fun extractNamedParamsIndexes(sql: String): Map<String, List<IntRange>> =
        nameParameterRegex.findAll(sql)
            .mapIndexed { index, group -> Pair(group, index) }
            .groupBy({ it.first.value.substring(1) }, { it.first.range })

    fun render(
        sql: String,
        params: Map<String, Any?>,
        paramsMapper: ((v: Any?) -> String?)?
    ): String {
        fun Any?.toValueString(): String {
            if (paramsMapper != null) paramsMapper(this)?.let { return it }
            return when (this) {
                null -> "null"
                is String -> this
                is Byte, is Boolean, is Int, is Long, is Short, is Double, is Float -> toString()
                else -> Sqlx4k.Error(
                    code = Sqlx4k.Error.Code.NamedParameterTypeNotSupported,
                    message = "Could not map named parameter of type ${this::class.qualifiedName}"
                ).ex()
            }
        }

        var res: String = sql
        extractNamedParamsIndexes(sql).entries.forEach { (name, ranges) ->
            if (!params.containsKey(name)) {
                Sqlx4k.Error(
                    code = Sqlx4k.Error.Code.NamedParameterValueNotSupplied,
                    message = "Value for named parameter '$name' was not supplied"
                ).ex()
            }
            ranges.forEach { res = res.replaceRange(it, params[name].toValueString()) }
        }
        return res
    }

}
