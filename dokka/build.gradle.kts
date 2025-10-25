plugins {
    id("io.github.smyrgeorge.sqlx4k.dokka")
}

dependencies {
    dokka(project(":sqlx4k"))
    dokka(project(":sqlx4k-codegen"))
    dokka(project(":sqlx4k-mysql"))
    dokka(project(":sqlx4k-postgres"))
    dokka(project(":sqlx4k-postgres-pgmq"))
    dokka(project(":sqlx4k-sqlite"))
}

dokka {
    moduleName.set(rootProject.name)
}
