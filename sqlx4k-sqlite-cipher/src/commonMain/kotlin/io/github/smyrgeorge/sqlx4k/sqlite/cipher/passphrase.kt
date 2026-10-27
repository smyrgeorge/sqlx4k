package io.github.smyrgeorge.sqlx4k.sqlite.cipher

/**
 * Rejects a passphrase that holds a NUL character (U+0000), with a message that does not quote it.
 *
 * The passphrase reaches SQLCipher as text: from Kotlin/Native as a C string, which ends at the first NUL, so the key
 * was silently cut short there (and an empty one, from a passphrase starting with NUL, left the database
 * unencrypted); and on the JVM in a `PRAGMA key` statement, which SQLite reads up to the NUL, so it failed with an
 * error quoting the start of the passphrase. A passphrase made of random bytes (e.g. decoded as ISO-8859-1) holds one
 * more often than not: encode the bytes as hex (or Base64) instead.
 */
internal fun requireValidPassphrase(password: String) {
    require('\u0000' !in password) {
        "The SQLCipher passphrase must not contain a NUL character (U+0000): encode a binary key as hex instead."
    }
}
