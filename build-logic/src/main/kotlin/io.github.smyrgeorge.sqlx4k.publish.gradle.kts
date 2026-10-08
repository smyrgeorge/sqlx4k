import com.vanniktech.maven.publish.GradlePlugin
import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.KotlinMultiplatform
import com.vanniktech.maven.publish.MavenPublishBaseExtension
import com.vanniktech.maven.publish.SourcesJar

plugins {
    id("com.vanniktech.maven.publish")
}

val descriptions: Map<String, String> = mapOf(
    "sqlx4k" to "A high-performance Kotlin Multiplatform database driver for PostgreSQL, MySQL/MariaDB, and SQLite.",
    "sqlx4k-arrow" to "A high-performance Kotlin Multiplatform database driver for PostgreSQL, MySQL, and SQLite.",
    "sqlx4k-codegen" to "A high-performance Kotlin Multiplatform database driver for PostgreSQL, MySQL/MariaDB, and SQLite.",
    "sqlx4k-codegen-test" to "A high-performance Kotlin Multiplatform database driver for PostgreSQL, MySQL/MariaDB, and SQLite.",
    "sqlx4k-gradle-plugin" to "Gradle plugin that wires the sqlx4k code generator (KSP) and driver into a Kotlin project from a single `sqlx4k { }` block.",
    "sqlx4k-mysql" to "A high-performance Kotlin Multiplatform database driver for MySQL/MariaDB.",
    "sqlx4k-postgres" to "A high-performance Kotlin Multiplatform database driver for PostgreSQL.",
    "sqlx4k-postgres-pgmq" to "A PGMQ client using PostgreSQL as a message queue.",
    "sqlx4k-sqlite" to "A high-performance Kotlin Multiplatform database driver for SQLite.",
    "sqlx4k-sqlite-cipher" to "A high-performance Kotlin Multiplatform database driver for SQLite with encryption.",
)

extensions.configure<MavenPublishBaseExtension> {
    // Gradle plugin modules publish the plugin jar plus its marker; everything else is Kotlin Multiplatform.
    if (pluginManager.hasPlugin("java-gradle-plugin")) {
        configure(
            GradlePlugin(
                javadocJar = JavadocJar.Empty(),
                sourcesJar = SourcesJar.Sources()
            )
        )
    } else {
        configure(
            KotlinMultiplatform(
                sourcesJar = SourcesJar.Sources()
            )
        )
    }
    coordinates(
        groupId = project.group as String,
        artifactId = project.name,
        version = project.version as String
    )

    pom {
        name.set(project.name)
        description.set(descriptions[project.name] ?: error("Missing description for $project.name"))
        url.set("https://github.com/smyrgeorge/sqlx4k")

        licenses {
            license {
                name.set("MIT License")
                url.set("https://github.com/smyrgeorge/sqlx4k/blob/main/LICENSE")
            }
        }

        developers {
            developer {
                id.set("smyrgeorge")
                name.set("Yorgos S.")
                email.set("smyrgeorge@gmail.com")
                url.set("https://smyrgeorge.github.io/")
            }
        }

        scm {
            url.set("https://github.com/smyrgeorge/sqlx4k")
            connection.set("scm:git:https://github.com/smyrgeorge/sqlx4k.git")
            developerConnection.set("scm:git:git@github.com:smyrgeorge/sqlx4k.git")
        }
    }

    // Configure publishing to Maven Central
    publishToMavenCentral()

    // Enable GPG signing for all publications. Disabled with -PRELEASE_SIGNING_ENABLED=false: mavenLocal
    // artifacts (see scripts/bootstrap.sh) need no signatures, and CI has no signing keys.
    if (providers.gradleProperty("RELEASE_SIGNING_ENABLED").getOrElse("true").toBoolean()) {
        signAllPublications()
    }
}
