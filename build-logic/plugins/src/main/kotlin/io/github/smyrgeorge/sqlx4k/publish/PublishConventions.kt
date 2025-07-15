package io.github.smyrgeorge.sqlx4k.publish

import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.KotlinMultiplatform
import com.vanniktech.maven.publish.MavenPublishBaseExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import java.io.File
import java.util.*

@Suppress("unused")
class PublishConventions : Plugin<Project> {

    private val descriptions: Map<String, String> = mapOf(
        "sqlx4k" to "A high-performance Kotlin Native database driver for PostgreSQL, MySQL, and SQLite.",
        "sqlx4k-codegen" to "A high-performance Kotlin Native database driver for PostgreSQL, MySQL, and SQLite.",
        "sqlx4k-mysql" to "A high-performance Kotlin Native database driver for MySQL.",
        "sqlx4k-postgres" to "A high-performance Kotlin Native database driver for PostgreSQL.",
        "sqlx4k-sqlite" to "A high-performance Kotlin Native database driver for SQLite.",
    )

    private fun Project.loadPublishProperties() {
        val local = Properties()
        File(project.rootProject.rootDir, "local.properties").also {
            if (!it.exists()) return@loadPublishProperties
            it.inputStream().use { s -> local.load(s) }
        }

        // Set Maven Central credentials as project properties
        local.getProperty("mavenCentralUsername")?.let { project.setProperty("mavenCentralUsername", it) }
        local.getProperty("mavenCentralPassword")?.let { project.setProperty("mavenCentralPassword", it) }

        // Set signing properties as project properties
        local.getProperty("signing.keyId")?.let { project.setProperty("signing.keyId", it) }
        local.getProperty("signing.password")?.let { project.setProperty("signing.password", it) }
        local.getProperty("signing.secretKeyRingFile")?.let { project.setProperty("signing.secretKeyRingFile", it) }
    }

    override fun apply(project: Project) {
        project.loadPublishProperties()

        project.plugins.apply("com.vanniktech.maven.publish")
        project.extensions.configure<MavenPublishBaseExtension> {
            // sources publishing is always enabled by the Kotlin Multiplatform plugin
            configure(
                KotlinMultiplatform(
                    // whether to publish a sources jar
                    sourcesJar = true,
                    // configures the -javadoc artifact, possible values:
                    javadocJar = JavadocJar.Dokka("dokkaHtml"),
                )
            )
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
            publishToMavenCentral()

            // Enable GPG signing for all publications
            signAllPublications()
        }
    }
}
