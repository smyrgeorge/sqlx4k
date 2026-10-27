//! Telling whether an UPSERT inserted its row, for the rowid it reports.

use sqlx::sqlite::SqliteConnection;

/// Whether the SQL is an UPSERT (`INSERT ... ON CONFLICT ... DO UPDATE`), judged by its words outside quotes and
/// comments.
pub(crate) fn is_upsert(sql: &str) -> bool {
    let words = sql_words(sql);
    let has = |a: &str, b: &str| words.windows(2).any(|w| w[0] == a && w[1] == b);
    has("on", "conflict") && has("do", "update")
}

/// The words of the SQL outside quotes and comments, lowercased.
pub(crate) fn sql_words(sql: &str) -> Vec<String> {
    let chars: Vec<char> = sql.chars().collect();
    let mut words = Vec::new();
    let mut i = 0;
    while i < chars.len() {
        let c = chars[i];
        if c == '\'' || c == '"' || c == '`' || c == '[' {
            let close = if c == '[' { ']' } else { c };
            i += 1;
            while i < chars.len() {
                if chars[i] == close {
                    // A doubled quote is one quote character of the string.
                    if close != ']' && chars.get(i + 1) == Some(&close) {
                        i += 2;
                        continue;
                    }
                    break;
                }
                i += 1;
            }
            i += 1;
        } else if c == '-' && chars.get(i + 1) == Some(&'-') {
            while i < chars.len() && chars[i] != '\n' {
                i += 1;
            }
        } else if c == '/' && chars.get(i + 1) == Some(&'*') {
            i += 2;
            while i < chars.len() && !(chars[i] == '*' && chars.get(i + 1) == Some(&'/')) {
                i += 1;
            }
            i += 2;
        } else if c.is_alphanumeric() || c == '_' {
            let start = i;
            while i < chars.len() && (chars[i].is_alphanumeric() || chars[i] == '_') {
                i += 1;
            }
            words.push(chars[start..i].iter().collect::<String>().to_lowercase());
        } else {
            i += 1;
        }
    }
    words
}

/// The connection's last inserted rowid, or `None` when it cannot be read.
pub(crate) async fn last_insert_rowid(cn: &mut SqliteConnection) -> Option<i64> {
    sqlx::query_scalar::<_, i64>("select last_insert_rowid()")
        .fetch_one(cn)
        .await
        .ok()
}

/// The rowid the statement inserted, as far as it is known. SQLite keeps the last inserted rowid per connection, and an
/// UPSERT that updates the row instead of inserting it leaves it as it was, with the row counted as changed: the
/// rowid of an earlier insert (into another table, maybe by an earlier borrower of the connection) was reported as the
/// UPSERT's. `before` is the connection's last inserted rowid before an UPSERT; when it is unchanged, the rowid is
/// reported as 0, none (a rowid is 0 only when inserted as such).
pub(crate) fn inserted_rowid(res: &sqlx::sqlite::SqliteQueryResult, before: Option<i64>) -> i64 {
    let rowid = res.last_insert_rowid();
    if before == Some(rowid) {
        0
    } else {
        rowid
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::test_support::{block_on, memory};
    use sqlx::{Connection, Executor};

    #[test]
    fn the_words_outside_quotes_and_comments_are_read() {
        let sql = "Insert INTO \"on conflict\" values ('do update', [do update], `x`) -- on conflict\n/* do update */ on_x";
        assert_eq!(sql_words(sql), ["insert", "into", "values", "on_x"]);
        // A doubled quote stays in its string.
        assert_eq!(
            sql_words("select 'it''s on conflict' as a"),
            ["select", "as", "a"]
        );
        // An unterminated string or comment ends the SQL.
        assert_eq!(sql_words("select 'on conflict"), ["select"]);
        assert_eq!(sql_words("select /* on conflict"), ["select"]);
    }

    #[test]
    fn an_upsert_is_told_from_other_statements() {
        assert!(is_upsert(
            "insert into t values (1) on conflict (id) do update set v = 2"
        ));
        assert!(is_upsert(
            "INSERT INTO t VALUES (1)\nON  CONFLICT(id)\tDO UPDATE SET v = excluded.v"
        ));
        assert!(!is_upsert(
            "insert into t values (1) on conflict do nothing"
        ));
        assert!(!is_upsert("insert into t values ('on conflict do update')"));
        assert!(!is_upsert(
            "insert into t values (1) -- on conflict do update"
        ));
        assert!(!is_upsert("update t set v = 1"));
    }

    #[test]
    fn an_upsert_that_updates_reports_no_rowid() {
        block_on(async {
            let mut cn = memory().await;
            cn.execute("create table t(id integer primary key, k text unique, v int)")
                .await
                .unwrap();
            assert_eq!(last_insert_rowid(&mut cn).await, Some(0));
            let res = cn
                .execute("insert into t(k, v) values ('a', 1)")
                .await
                .unwrap();
            assert_eq!(inserted_rowid(&res, None), 1);

            let upsert =
                "insert into t(k, v) values ('a', 2) on conflict(k) do update set v = excluded.v";
            let before = last_insert_rowid(&mut cn).await;
            assert_eq!(before, Some(1));
            let res = cn.execute(upsert).await.unwrap();
            // The row was updated: the connection's rowid is the earlier insert's.
            assert_eq!((res.rows_affected(), res.last_insert_rowid()), (1, 1));
            assert_eq!(inserted_rowid(&res, before), 0);

            let upsert =
                "insert into t(k, v) values ('b', 3) on conflict(k) do update set v = excluded.v";
            let before = last_insert_rowid(&mut cn).await;
            let res = cn.execute(upsert).await.unwrap();
            assert_eq!(inserted_rowid(&res, before), 2);
            cn.close().await.unwrap();
        });
    }
}
