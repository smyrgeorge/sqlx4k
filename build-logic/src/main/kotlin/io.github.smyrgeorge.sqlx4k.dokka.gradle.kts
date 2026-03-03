import org.jetbrains.dokka.gradle.DokkaExtension

plugins {
    id("org.jetbrains.dokka")
}

extensions.configure<DokkaExtension> {
    dokkaSourceSets.configureEach {
        sourceLink {
            localDirectory.set(rootDir)
            remoteUrl("https://github.com/smyrgeorge/${rootProject.name}/tree/main")
        }
    }
}
