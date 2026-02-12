package io.github.smyrgeorge.sqlx4k

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import io.github.smyrgeorge.sqlx4k.impl.extensions.scanSql
import io.github.smyrgeorge.sqlx4k.impl.migrate.utils.splitSqlStatements
import kotlin.test.Test

class ScanSqlTests {

    // ========================================================================================
    // scanSql – basic passthrough / identity
    // ========================================================================================

    @Test
    fun `scanSql with no callback handling returns input unchanged`() {
        val sql = "SELECT 1"
        val result = sql.scanSql { _, _, _ -> null }
        assertThat(result).isEqualTo(sql)
    }

    @Test
    fun `scanSql on empty string returns empty string`() {
        val result = "".scanSql { _, _, _ -> null }
        assertThat(result).isEqualTo("")
    }

    @Test
    fun `scanSql writeOutput false returns original string`() {
        val sql = "SELECT 1"
        val result = sql.scanSql(writeOutput = false) { _, _, _ -> null }
        assertThat(result).isEqualTo(sql)
    }

    @Test
    fun `scanSql callback can replace characters`() {
        val sql = "a?b?c"
        val result = sql.scanSql { i, c, sb ->
            if (c == '?') {
                sb.append("X"); i + 1
            } else null
        }
        assertThat(result).isEqualTo("aXbXc")
    }

    @Test
    fun `scanSql callback can consume multiple characters`() {
        val sql = "hello<<world>>end"
        val result = sql.scanSql { i, c, sb ->
            if (c == '<' && i + 1 < length && this[i + 1] == '<') {
                val close = indexOf(">>", i + 2)
                if (close >= 0) {
                    sb.append("[REPLACED]")
                    close + 2
                } else null
            } else null
        }
        assertThat(result).isEqualTo("hello[REPLACED]end")
    }

    // ========================================================================================
    // scanSql – single-quoted strings
    // ========================================================================================

    @Test
    fun `scanSql preserves single-quoted strings`() {
        val sql = "SELECT 'hello world' AS greeting"
        val result = sql.scanSql { _, _, _ -> null }
        assertThat(result).isEqualTo(sql)
    }

    @Test
    fun `scanSql does not invoke callback inside single-quoted strings`() {
        val sql = "SELECT 'a?b' AS val, ? AS id"
        var callbackCount = 0
        val result = sql.scanSql { i, c, sb ->
            if (c == '?') {
                callbackCount++; sb.append("X"); i + 1
            } else null
        }
        assertThat(callbackCount).isEqualTo(1)
        assertThat(result).isEqualTo("SELECT 'a?b' AS val, X AS id")
    }

    @Test
    fun `scanSql handles escaped single quotes`() {
        val sql = "SELECT 'it''s' AS val"
        val result = sql.scanSql { _, _, _ -> null }
        assertThat(result).isEqualTo(sql)
    }

    @Test
    fun `scanSql handles empty single-quoted string`() {
        val sql = "SELECT '' AS empty"
        val result = sql.scanSql { _, _, _ -> null }
        assertThat(result).isEqualTo(sql)
    }

    @Test
    fun `scanSql handles adjacent single-quoted strings`() {
        val sql = "SELECT 'a' || 'b'"
        val result = sql.scanSql { _, _, _ -> null }
        assertThat(result).isEqualTo(sql)
    }

    @Test
    fun `scanSql handles single-quoted string with special characters`() {
        val sql = "SELECT 'line1\nline2--not comment/*also not*/' AS val"
        val result = sql.scanSql { _, _, _ -> null }
        assertThat(result).isEqualTo(sql)
    }

    // ========================================================================================
    // scanSql – double-quoted identifiers
    // ========================================================================================

    @Test
    fun `scanSql preserves double-quoted identifiers`() {
        val sql = """SELECT "my column" FROM t"""
        val result = sql.scanSql { _, _, _ -> null }
        assertThat(result).isEqualTo(sql)
    }

    @Test
    fun `scanSql does not invoke callback inside double-quoted identifiers`() {
        val sql = """SELECT "col?" AS val, ? AS id"""
        var callbackCount = 0
        val result = sql.scanSql { i, c, sb ->
            if (c == '?') {
                callbackCount++; sb.append("X"); i + 1
            } else null
        }
        assertThat(callbackCount).isEqualTo(1)
        assertThat(result).isEqualTo("""SELECT "col?" AS val, X AS id""")
    }

    @Test
    fun `scanSql handles escaped double quotes`() {
        val sql = """SELECT "col""name" FROM t"""
        val result = sql.scanSql { _, _, _ -> null }
        assertThat(result).isEqualTo(sql)
    }

    @Test
    fun `scanSql handles empty double-quoted identifier`() {
        val sql = """SELECT "" AS empty"""
        val result = sql.scanSql { _, _, _ -> null }
        assertThat(result).isEqualTo(sql)
    }

    // ========================================================================================
    // scanSql – backtick-quoted identifiers
    // ========================================================================================

    @Test
    fun `scanSql preserves backtick-quoted identifiers`() {
        val sql = "SELECT `my column` FROM t"
        val result = sql.scanSql { _, _, _ -> null }
        assertThat(result).isEqualTo(sql)
    }

    @Test
    fun `scanSql does not invoke callback inside backtick-quoted identifiers`() {
        val sql = "SELECT `col?` AS val, ? AS id"
        var callbackCount = 0
        val result = sql.scanSql { i, c, sb ->
            if (c == '?') {
                callbackCount++; sb.append("X"); i + 1
            } else null
        }
        assertThat(callbackCount).isEqualTo(1)
        assertThat(result).isEqualTo("SELECT `col?` AS val, X AS id")
    }

    @Test
    fun `scanSql handles empty backtick-quoted identifier`() {
        val sql = "SELECT `` AS empty"
        val result = sql.scanSql { _, _, _ -> null }
        assertThat(result).isEqualTo(sql)
    }

    @Test
    fun `scanSql handles backtick with special characters inside`() {
        val sql = $$"SELECT `col--comment/*block*/$tag$` FROM t"
        val result = sql.scanSql { _, _, _ -> null }
        assertThat(result).isEqualTo(sql)
    }

    // ========================================================================================
    // scanSql – line comments
    // ========================================================================================

    @Test
    fun `scanSql preserves line comments`() {
        val sql = "SELECT 1 -- this is a comment\n, 2"
        val result = sql.scanSql { _, _, _ -> null }
        assertThat(result).isEqualTo(sql)
    }

    @Test
    fun `scanSql does not invoke callback inside line comments`() {
        val sql = "SELECT 1 -- comment with ?\n, ? AS id"
        var callbackCount = 0
        val result = sql.scanSql { i, c, sb ->
            if (c == '?') {
                callbackCount++; sb.append("X"); i + 1
            } else null
        }
        assertThat(callbackCount).isEqualTo(1)
        assertThat(result).isEqualTo("SELECT 1 -- comment with ?\n, X AS id")
    }

    @Test
    fun `scanSql line comment at end of string without newline`() {
        val sql = "SELECT 1 -- trailing comment"
        val result = sql.scanSql { _, _, _ -> null }
        assertThat(result).isEqualTo(sql)
    }

    @Test
    fun `scanSql multiple line comments`() {
        val sql = "SELECT 1 -- first\n, 2 -- second\n, 3"
        val result = sql.scanSql { _, _, _ -> null }
        assertThat(result).isEqualTo(sql)
    }

    @Test
    fun `scanSql single dash is not a line comment`() {
        val sql = "SELECT 1 - 2"
        var saw = false
        sql.scanSql { _, c, _ ->
            if (c == '-') saw = true
            null
        }
        assertThat(saw).isEqualTo(true)
    }

    // ========================================================================================
    // scanSql – block comments
    // ========================================================================================

    @Test
    fun `scanSql preserves block comments`() {
        val sql = "SELECT /* comment */ 1"
        val result = sql.scanSql { _, _, _ -> null }
        assertThat(result).isEqualTo(sql)
    }

    @Test
    fun `scanSql does not invoke callback inside block comments`() {
        val sql = "SELECT /* ? */ ? AS id"
        var callbackCount = 0
        val result = sql.scanSql { i, c, sb ->
            if (c == '?') {
                callbackCount++; sb.append("X"); i + 1
            } else null
        }
        assertThat(callbackCount).isEqualTo(1)
        assertThat(result).isEqualTo("SELECT /* ? */ X AS id")
    }

    @Test
    fun `scanSql nested block comments`() {
        val sql = "/* outer /* inner */ still comment */ SELECT 1"
        val result = sql.scanSql { _, _, _ -> null }
        assertThat(result).isEqualTo(sql)
    }

    @Test
    fun `scanSql deeply nested block comments`() {
        val sql = "/* l1 /* l2 /* l3 */ l2 */ l1 */ SELECT 1"
        val result = sql.scanSql { _, _, _ -> null }
        assertThat(result).isEqualTo(sql)
    }

    @Test
    fun `scanSql does not invoke callback inside nested block comments`() {
        val sql = "/* outer /* ? */ inner ? */ SELECT ? AS id"
        var callbackCount = 0
        val result = sql.scanSql { i, c, sb ->
            if (c == '?') {
                callbackCount++; sb.append("X"); i + 1
            } else null
        }
        assertThat(callbackCount).isEqualTo(1)
        assertThat(result).isEqualTo("/* outer /* ? */ inner ? */ SELECT X AS id")
    }

    @Test
    fun `scanSql block comment at end of string`() {
        val sql = "SELECT 1 /* trailing"
        val result = sql.scanSql { _, _, _ -> null }
        assertThat(result).isEqualTo(sql)
    }

    @Test
    fun `scanSql empty block comment`() {
        val sql = "SELECT /**/ 1"
        val result = sql.scanSql { _, _, _ -> null }
        assertThat(result).isEqualTo(sql)
    }

    @Test
    fun `scanSql slash not followed by star is normal`() {
        val sql = "SELECT 10 / 2"
        var sawSlash = false
        sql.scanSql { _, c, _ ->
            if (c == '/') sawSlash = true
            null
        }
        assertThat(sawSlash).isEqualTo(true)
    }

    // ========================================================================================
    // scanSql – dollar-quoted strings
    // ========================================================================================

    @Test
    fun `scanSql preserves empty dollar-quoted strings`() {
        val sql = "SELECT $$ body $$ AS val"
        val result = sql.scanSql { _, _, _ -> null }
        assertThat(result).isEqualTo(sql)
    }

    @Test
    fun `scanSql does not invoke callback inside dollar-quoted strings`() {
        val sql = "SELECT $$ ? $$ AS val, ? AS id"
        var callbackCount = 0
        val result = sql.scanSql { i, c, sb ->
            if (c == '?') {
                callbackCount++; sb.append("X"); i + 1
            } else null
        }
        assertThat(callbackCount).isEqualTo(1)
        assertThat(result).isEqualTo("SELECT $$ ? $$ AS val, X AS id")
    }

    @Test
    fun `scanSql named dollar-quoted strings`() {
        val sql = $$"$fn$ body with ? $fn$ outside ?"
        var callbackCount = 0
        val result = sql.scanSql { i, c, sb ->
            if (c == '?') {
                callbackCount++; sb.append("X"); i + 1
            } else null
        }
        assertThat(callbackCount).isEqualTo(1)
        assertThat(result).isEqualTo($$"$fn$ body with ? $fn$ outside X")
    }

    @Test
    fun `scanSql dollar-quoted string with underscore tag`() {
        val sql = $$"$my_tag$ content $my_tag$ after"
        val result = sql.scanSql { _, _, _ -> null }
        assertThat(result).isEqualTo(sql)
    }

    @Test
    fun `scanSql dollar sign followed by digit is not a dollar tag`() {
        val sql = $$"SELECT $1 AS val"
        var sawDollar = false
        sql.scanSql { _, c, _ ->
            if (c == '$') sawDollar = true
            null
        }
        assertThat(sawDollar).isEqualTo(true)
    }

    @Test
    fun `scanSql dollar-digit-dollar is not a dollar tag`() {
        // $1$ should NOT start a dollar-quoted string
        val sql = $$"$1$ ? $1$ ?"
        var callbackCount = 0
        sql.scanSql { i, c, _ ->
            if (c == '?') {
                callbackCount++; i + 1
            } else null
        }
        assertThat(callbackCount).isEqualTo(2)
    }

    @Test
    fun `scanSql lone dollar sign at end of string`() {
        val sql = "SELECT $"
        val result = sql.scanSql { _, _, _ -> null }
        assertThat(result).isEqualTo(sql)
    }

    @Test
    fun `scanSql different dollar tags are not confused`() {
        val sql = $$"$a$ body $b$ still in a $a$ outside"
        val result = sql.scanSql { _, _, _ -> null }
        // $a$ opens, $b$ inside the body is literal text, $a$ closes
        assertThat(result).isEqualTo(sql)
    }

    @Test
    fun `scanSql dollar-quoted string containing single and double quotes`() {
        val sql = "$$ it's a \"test\" $$ after"
        val result = sql.scanSql { _, _, _ -> null }
        assertThat(result).isEqualTo(sql)
    }

    @Test
    fun `scanSql dollar-quoted string containing comments`() {
        val sql = "$$ -- not a comment /* also not */ $$ after"
        val result = sql.scanSql { _, _, _ -> null }
        assertThat(result).isEqualTo(sql)
    }

    // ========================================================================================
    // scanSql – mixed contexts
    // ========================================================================================

    @Test
    fun `scanSql mixed quoting styles`() {
        val sql = """SELECT 'single' AS a, "double" AS b, `backtick` AS c, ? AS d"""
        var callbackCount = 0
        val result = sql.scanSql { i, c, sb ->
            if (c == '?') {
                callbackCount++; sb.append("X"); i + 1
            } else null
        }
        assertThat(callbackCount).isEqualTo(1)
        assertThat(result).isEqualTo("""SELECT 'single' AS a, "double" AS b, `backtick` AS c, X AS d""")
    }

    @Test
    fun `scanSql comment inside single-quoted string is not a comment`() {
        val sql = "SELECT '-- not a comment' AS val, ? AS id"
        var callbackCount = 0
        sql.scanSql { i, c, _ ->
            if (c == '?') {
                callbackCount++; i + 1
            } else null
        }
        assertThat(callbackCount).isEqualTo(1)
    }

    @Test
    fun `scanSql block comment inside single-quoted string is not a comment`() {
        val sql = "SELECT '/* not a comment */' AS val, ? AS id"
        var callbackCount = 0
        sql.scanSql { i, c, _ ->
            if (c == '?') {
                callbackCount++; i + 1
            } else null
        }
        assertThat(callbackCount).isEqualTo(1)
    }

    @Test
    fun `scanSql single quote inside line comment does not start string`() {
        val sql = "SELECT 1 -- it's a comment\n, ? AS id"
        var callbackCount = 0
        val result = sql.scanSql { i, c, sb ->
            if (c == '?') {
                callbackCount++; sb.append("X"); i + 1
            } else null
        }
        assertThat(callbackCount).isEqualTo(1)
        assertThat(result).isEqualTo("SELECT 1 -- it's a comment\n, X AS id")
    }

    @Test
    fun `scanSql single quote inside block comment does not start string`() {
        val sql = "SELECT /* it's a comment */ ? AS id"
        var callbackCount = 0
        val result = sql.scanSql { i, c, sb ->
            if (c == '?') {
                callbackCount++; sb.append("X"); i + 1
            } else null
        }
        assertThat(callbackCount).isEqualTo(1)
        assertThat(result).isEqualTo("SELECT /* it's a comment */ X AS id")
    }

    @Test
    fun `scanSql writeOutput false with callback side effects`() {
        val sql = "a?b?c"
        var count = 0
        val result = sql.scanSql(writeOutput = false) { i, c, _ ->
            if (c == '?') {
                count++; i + 1
            } else null
        }
        assertThat(count).isEqualTo(2)
        assertThat(result).isEqualTo(sql) // original string returned
    }

    // ========================================================================================
    // splitSqlStatements – basic splitting
    // ========================================================================================

    @Test
    fun `split single statement without semicolon`() {
        val result = splitSqlStatements("SELECT 1")
        assertThat(result).containsExactly("SELECT 1")
    }

    @Test
    fun `split single statement with semicolon`() {
        val result = splitSqlStatements("SELECT 1;")
        assertThat(result).containsExactly("SELECT 1")
    }

    @Test
    fun `split two statements`() {
        val result = splitSqlStatements("SELECT 1; SELECT 2")
        assertThat(result).containsExactly("SELECT 1", "SELECT 2")
    }

    @Test
    fun `split two statements with trailing semicolon`() {
        val result = splitSqlStatements("SELECT 1; SELECT 2;")
        assertThat(result).containsExactly("SELECT 1", "SELECT 2")
    }

    @Test
    fun `split empty string`() {
        val result = splitSqlStatements("")
        assertThat(result).isEmpty()
    }

    @Test
    fun `split whitespace only`() {
        val result = splitSqlStatements("   \n\t  ")
        assertThat(result).isEmpty()
    }

    @Test
    fun `split semicolons only`() {
        val result = splitSqlStatements(";;;")
        assertThat(result).isEmpty()
    }

    @Test
    fun `split strips whitespace from statements`() {
        val result = splitSqlStatements("  SELECT 1  ;  SELECT 2  ")
        assertThat(result).containsExactly("SELECT 1", "SELECT 2")
    }

    @Test
    fun `split multiline statements`() {
        val sql = """
            CREATE TABLE t (
                id INT
            );
            INSERT INTO t VALUES (1);
        """.trimIndent()
        val result = splitSqlStatements(sql)
        assertThat(result).containsExactly(
            "CREATE TABLE t (\n    id INT\n)",
            "INSERT INTO t VALUES (1)"
        )
    }

    @Test
    fun `split many statements`() {
        val sql = "SELECT 1; SELECT 2; SELECT 3; SELECT 4; SELECT 5"
        val result = splitSqlStatements(sql)
        assertThat(result).containsExactly(
            "SELECT 1", "SELECT 2", "SELECT 3", "SELECT 4", "SELECT 5"
        )
    }

    // ========================================================================================
    // splitSqlStatements – semicolons inside single-quoted strings
    // ========================================================================================

    @Test
    fun `split does not split on semicolon inside single-quoted string`() {
        val sql = "SELECT 'a;b' AS val; SELECT 2"
        val result = splitSqlStatements(sql)
        assertThat(result).containsExactly("SELECT 'a;b' AS val", "SELECT 2")
    }

    @Test
    fun `split handles escaped single quotes with semicolons`() {
        val sql = "SELECT 'it''s;here' AS val; SELECT 2"
        val result = splitSqlStatements(sql)
        assertThat(result).containsExactly("SELECT 'it''s;here' AS val", "SELECT 2")
    }

    @Test
    fun `split handles empty single-quoted string before semicolon`() {
        val sql = "SELECT ''; SELECT 2"
        val result = splitSqlStatements(sql)
        assertThat(result).containsExactly("SELECT ''", "SELECT 2")
    }

    // ========================================================================================
    // splitSqlStatements – semicolons inside double-quoted identifiers
    // ========================================================================================

    @Test
    fun `split does not split on semicolon inside double-quoted identifier`() {
        val sql = """SELECT "col;name" FROM t; SELECT 2"""
        val result = splitSqlStatements(sql)
        assertThat(result).containsExactly("""SELECT "col;name" FROM t""", "SELECT 2")
    }

    @Test
    fun `split handles escaped double quotes with semicolons`() {
        val sql = """SELECT "col"";name" FROM t; SELECT 2"""
        val result = splitSqlStatements(sql)
        assertThat(result).containsExactly("""SELECT "col"";name" FROM t""", "SELECT 2")
    }

    // ========================================================================================
    // splitSqlStatements – semicolons inside backtick-quoted identifiers
    // ========================================================================================

    @Test
    fun `split does not split on semicolon inside backtick-quoted identifier`() {
        val sql = "SELECT `col;name` FROM t; SELECT 2"
        val result = splitSqlStatements(sql)
        assertThat(result).containsExactly("SELECT `col;name` FROM t", "SELECT 2")
    }

    // ========================================================================================
    // splitSqlStatements – semicolons inside line comments
    // ========================================================================================

    @Test
    fun `split does not split on semicolon inside line comment`() {
        val sql = "SELECT 1 -- comment with ;\n; SELECT 2"
        val result = splitSqlStatements(sql)
        assertThat(result).containsExactly("SELECT 1 -- comment with ;", "SELECT 2")
    }

    @Test
    fun `split line comment at end hides trailing semicolon`() {
        val sql = "SELECT 1; SELECT 2 -- trailing;"
        val result = splitSqlStatements(sql)
        assertThat(result).containsExactly("SELECT 1", "SELECT 2 -- trailing;")
    }

    // ========================================================================================
    // splitSqlStatements – semicolons inside block comments
    // ========================================================================================

    @Test
    fun `split does not split on semicolon inside block comment`() {
        val sql = "SELECT /* ; */ 1; SELECT 2"
        val result = splitSqlStatements(sql)
        assertThat(result).containsExactly("SELECT /* ; */ 1", "SELECT 2")
    }

    @Test
    fun `split does not split on semicolon inside nested block comment`() {
        val sql = "SELECT /* outer /* ; */ inner */ 1; SELECT 2"
        val result = splitSqlStatements(sql)
        assertThat(result).containsExactly("SELECT /* outer /* ; */ inner */ 1", "SELECT 2")
    }

    @Test
    fun `split deeply nested block comments with semicolons`() {
        val sql = "/* l1 /* l2 ; /* l3 ; */ ; */ ; */ SELECT 1; SELECT 2"
        val result = splitSqlStatements(sql)
        assertThat(result).containsExactly("/* l1 /* l2 ; /* l3 ; */ ; */ ; */ SELECT 1", "SELECT 2")
    }

    // ========================================================================================
    // splitSqlStatements – semicolons inside dollar-quoted strings
    // ========================================================================================

    @Test
    fun `split does not split on semicolon inside empty dollar-quoted string`() {
        val sql = "SELECT $$ body; with semicolons; $$ AS val; SELECT 2"
        val result = splitSqlStatements(sql)
        assertThat(result).containsExactly("SELECT $$ body; with semicolons; $$ AS val", "SELECT 2")
    }

    @Test
    fun `split does not split on semicolon inside named dollar-quoted string`() {
        val sql = $$"SELECT $fn$ body; here $fn$ AS val; SELECT 2"
        val result = splitSqlStatements(sql)
        assertThat(result).containsExactly($$"SELECT $fn$ body; here $fn$ AS val", "SELECT 2")
    }

    @Test
    fun `split dollar-quoted string with underscore tag and semicolons`() {
        val sql = $$"SELECT $my_tag$ has;semicolons; $my_tag$ AS val; SELECT 2"
        val result = splitSqlStatements(sql)
        assertThat(result).containsExactly(
            $$"SELECT $my_tag$ has;semicolons; $my_tag$ AS val",
            "SELECT 2"
        )
    }

    @Test
    fun `split dollar sign followed by digit does not start dollar-quoted string`() {
        // $1 is NOT a dollar tag, so semicolons after it are real
        val sql = $$"SELECT $1; SELECT 2"
        val result = splitSqlStatements(sql)
        assertThat(result).containsExactly($$"SELECT $1", "SELECT 2")
    }

    @Test
    fun `split dollar-digit-dollar does not start dollar-quoted string`() {
        // $1$ should NOT be treated as a dollar tag
        val sql = $$"SELECT $1$; SELECT 2"
        val result = splitSqlStatements(sql)
        assertThat(result).containsExactly($$"SELECT $1$", "SELECT 2")
    }

    // ========================================================================================
    // splitSqlStatements – PostgreSQL function bodies (realistic)
    // ========================================================================================

    @Test
    fun `split PostgreSQL function with dollar-quoted body`() {
        val sql = """
            CREATE FUNCTION test() RETURNS void AS $$
                BEGIN
                    INSERT INTO t VALUES (1);
                    INSERT INTO t VALUES (2);
                END;
            $$ LANGUAGE plpgsql;
            SELECT 1
        """.trimIndent()
        val result = splitSqlStatements(sql)
        assertThat(result.size).isEqualTo(2)
        assertThat(result[0]).isEqualTo(
            "CREATE FUNCTION test() RETURNS void AS $$\n" +
                    "    BEGIN\n" +
                    "        INSERT INTO t VALUES (1);\n" +
                    "        INSERT INTO t VALUES (2);\n" +
                    "    END;\n" +
                    "$$ LANGUAGE plpgsql"
        )
        assertThat(result[1]).isEqualTo("SELECT 1")
    }

    @Test
    fun `split PostgreSQL trigger with named dollar tag`() {
        val sql = $$"$body$\n  BEGIN\n    RETURN NEW;\n  END;\n$body$; SELECT 1"
        val result = splitSqlStatements(sql)
        assertThat(result).containsExactly(
            $$"$body$\n  BEGIN\n    RETURN NEW;\n  END;\n$body$",
            "SELECT 1"
        )
    }

    // ========================================================================================
    // splitSqlStatements – mixed contexts
    // ========================================================================================

    @Test
    fun `split with all context types`() {
        val sql = """
            SELECT 'semi;colon' -- line; comment
            , /* block; comment */ 1
            , $$ dollar; body $$ AS val;
            SELECT 2
        """.trimIndent()
        val result = splitSqlStatements(sql)
        assertThat(result.size).isEqualTo(2)
        assertThat(result[1]).isEqualTo("SELECT 2")
    }

    @Test
    fun `split complex migration-like SQL`() {
        val sql = """
            CREATE TABLE users (
                id SERIAL PRIMARY KEY,
                name TEXT NOT NULL,
                email TEXT UNIQUE
            );
            CREATE INDEX idx_users_email ON users(email);
            INSERT INTO users (name, email) VALUES ('Alice', 'alice@example.com');
        """.trimIndent()
        val result = splitSqlStatements(sql)
        assertThat(result.size).isEqualTo(3)
        assertThat(result[0]).isEqualTo(
            "CREATE TABLE users (\n    id SERIAL PRIMARY KEY,\n    name TEXT NOT NULL,\n    email TEXT UNIQUE\n)"
        )
        assertThat(result[1]).isEqualTo("CREATE INDEX idx_users_email ON users(email)")
        assertThat(result[2]).isEqualTo("INSERT INTO users (name, email) VALUES ('Alice', 'alice@example.com')")
    }

    @Test
    fun `split preserves backtick identifiers in statements`() {
        val sql = "SELECT `a;b` FROM t; SELECT `c;d` FROM t"
        val result = splitSqlStatements(sql)
        assertThat(result).containsExactly("SELECT `a;b` FROM t", "SELECT `c;d` FROM t")
    }

    @Test
    fun `split with comment between statements`() {
        val sql = "SELECT 1; -- separator\nSELECT 2"
        val result = splitSqlStatements(sql)
        assertThat(result).containsExactly("SELECT 1", "-- separator\nSELECT 2")
    }

    @Test
    fun `split with block comment between statements`() {
        val sql = "SELECT 1; /* gap */ SELECT 2"
        val result = splitSqlStatements(sql)
        assertThat(result).containsExactly("SELECT 1", "/* gap */ SELECT 2")
    }

    // ========================================================================================
    // splitSqlStatements – edge cases
    // ========================================================================================

    @Test
    fun `split single semicolon`() {
        val result = splitSqlStatements(";")
        assertThat(result).isEmpty()
    }

    @Test
    fun `split statement with only whitespace between semicolons`() {
        val result = splitSqlStatements("SELECT 1;   ;   ; SELECT 2")
        assertThat(result).containsExactly("SELECT 1", "SELECT 2")
    }

    @Test
    fun `split unclosed single quote carries to end`() {
        // Unclosed quote means everything after the quote is in-string, including semicolons
        val sql = "SELECT 'unclosed; string"
        val result = splitSqlStatements(sql)
        assertThat(result).containsExactly("SELECT 'unclosed; string")
    }

    @Test
    fun `split unclosed double quote carries to end`() {
        val sql = """SELECT "unclosed; ident"""
        val result = splitSqlStatements(sql)
        assertThat(result).containsExactly("""SELECT "unclosed; ident""")
    }

    @Test
    fun `split unclosed block comment carries to end`() {
        val sql = "SELECT /* unclosed; comment"
        val result = splitSqlStatements(sql)
        assertThat(result).containsExactly("SELECT /* unclosed; comment")
    }

    @Test
    fun `split unclosed dollar-quoted string carries to end`() {
        val sql = "SELECT $$ unclosed; body"
        val result = splitSqlStatements(sql)
        assertThat(result).containsExactly("SELECT $$ unclosed; body")
    }

    @Test
    fun `split unclosed backtick carries to end`() {
        val sql = "SELECT `unclosed; ident"
        val result = splitSqlStatements(sql)
        assertThat(result).containsExactly("SELECT `unclosed; ident")
    }
}
