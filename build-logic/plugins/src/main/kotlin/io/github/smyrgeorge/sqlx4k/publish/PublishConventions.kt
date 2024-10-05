package io.github.smyrgeorge.sqlx4k.publish

import com.vanniktech.maven.publish.MavenPublishBaseExtension
import com.vanniktech.maven.publish.SonatypeHost
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

class PublishConventions : Plugin<Project> {

    private val descriptions: Map<String, String> = mapOf(
        "sqlx4k" to "A high-performance Kotlin Native database driver for PostgreSQL, MySQL, and SQLite.",
        "sqlx4k-codegen" to "A high-performance Kotlin Native database driver for PostgreSQL, MySQL, and SQLite.",
        "sqlx4k-mysql" to "A high-performance Kotlin Native database driver for MySQL.",
        "sqlx4k-postgres" to "A high-performance Kotlin Native database driver for PostgreSQL.",
        "sqlx4k-sqlite" to "A high-performance Kotlin Native database driver for SQLite.",
        "sqlx4k-sqldelight" to "Sqldelight support for sqlx4k.",
        "sqlx4k-sqldelight-dialect-mysql" to "Sqldelight support for sqlx4k (MySQL dialect).",
        "sqlx4k-sqldelight-dialect-postgres" to "Sqldelight support for sqlx4k (PostgreSQL dialect).",
    )

    override fun apply(project: Project) {
        project.plugins.apply("com.vanniktech.maven.publish")
        project.extensions.configure<MavenPublishBaseExtension> {
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
                        email.set("smyrgoerge@gmail.com")
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
            publishToMavenCentral(SonatypeHost.CENTRAL_PORTAL)

            // Enable GPG signing for all publications
            signAllPublications()
        }
    }
}