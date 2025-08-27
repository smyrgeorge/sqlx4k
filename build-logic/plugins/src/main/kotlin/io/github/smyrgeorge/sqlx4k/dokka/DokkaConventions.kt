package io.github.smyrgeorge.sqlx4k.dokka

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.jetbrains.dokka.gradle.DokkaExtension

@Suppress("unused")
class DokkaConventions : Plugin<Project> {
    override fun apply(project: Project) {
        project.plugins.apply("org.jetbrains.dokka")
        project.extensions.configure<DokkaExtension> {
            dokkaSourceSets.configureEach {
                sourceLink {
                    localDirectory.set(project.rootDir)
                    remoteUrl("https://github.com/smyrgeorge/${project.rootProject.name}/tree/main")
                }
            }
        }
    }
}
