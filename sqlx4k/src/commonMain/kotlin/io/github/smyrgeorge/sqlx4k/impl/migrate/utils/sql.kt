package io.github.smyrgeorge.sqlx4k.impl.migrate.utils

@Suppress("DuplicatedCode")
fun splitSqlStatements(sql: String): List<String> {
    val result = mutableListOf<String>()
    val sb = StringBuilder()
    var i = 0
    var inSingle = false
    var inDouble = false
    var inLineComment = false
    var inBlockComment = false
    var dollarTag: String? = null

    fun at(i: Int) = if (i < sql.length) sql[i] else '\u0000'

    while (i < sql.length) {
        val c = sql[i]
        val n = at(i + 1)

        if (inLineComment) {
            if (c == '\n') {
                inLineComment = false
                sb.append(c)
            } else {
                sb.append(c)
            }
            i++
            continue
        }
        if (inBlockComment) {
            if (c == '*' && n == '/') {
                sb.append("*/")
                i += 2
                inBlockComment = false
            } else {
                sb.append(c)
                i++
            }
            continue
        }
        if (dollarTag != null) {
            sb.append(c)
            // Look for closing tag
            if (c == '$' && sql.startsWith(dollarTag, i)) {
                // append the rest of tag
                for (k in 1 until dollarTag.length) sb.append(sql[i + k])
                i += dollarTag.length
                dollarTag = null
            } else {
                i++
            }
            continue
        }
        if (inSingle) {
            sb.append(c)
            if (c == '\'') {
                if (n == '\'') {
                    sb.append(n); i += 2
                } else {
                    i++; inSingle = false
                }
            } else {
                i++
            }
            continue
        }
        if (inDouble) {
            sb.append(c)
            if (c == '"') {
                if (n == '"') {
                    sb.append(n); i += 2
                } else {
                    i++; inDouble = false
                }
            } else {
                i++
            }
            continue
        }

        // Outside of strings/comments
        if (c == '-' && n == '-') {
            sb.append("--"); i += 2; inLineComment = true; continue
        }
        if (c == '/' && n == '*') {
            sb.append("/*"); i += 2; inBlockComment = true; continue
        }
        if (c == '\'') {
            sb.append(c); i++; inSingle = true; continue
        }
        if (c == '"') {
            sb.append(c); i++; inDouble = true; continue
        }

        if (c == '$') {
            // detect $tag$ ... $tag$
            val tag = readDollarTag(sql, i)
            if (tag != null) {
                dollarTag = tag
                sb.append(tag)
                i += tag.length
                continue
            }
        }

        if (c == ';') {
            // end of statement
            val stmt = sb.toString().trim()
            if (stmt.isNotEmpty()) result.add(stmt)
            sb.setLength(0)
            i++
            continue
        }

        sb.append(c)
        i++
    }
    val tail = sb.toString().trim()
    if (tail.isNotEmpty()) result.add(tail)
    return result
}

private fun readDollarTag(sql: String, start: Int): String? {
    // Expect $...$
    if (start >= sql.length || sql[start] != '$') return null
    var i = start + 1
    while (i < sql.length && sql[i].isLetterOrDigit()) i++
    if (i < sql.length && sql[i] == '$') {
        return sql.substring(start, i + 1) // includes both $ signs
    }
    return null
}