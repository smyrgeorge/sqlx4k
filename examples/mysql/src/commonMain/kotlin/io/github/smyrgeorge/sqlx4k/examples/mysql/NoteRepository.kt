package io.github.smyrgeorge.sqlx4k.examples.mysql

import io.github.smyrgeorge.sqlx4k.CrudRepository
import io.github.smyrgeorge.sqlx4k.annotation.Repository

@Repository
interface NoteRepository : CrudRepository<Note>
