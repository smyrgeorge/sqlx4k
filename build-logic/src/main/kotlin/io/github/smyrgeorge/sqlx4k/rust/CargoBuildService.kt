package io.github.smyrgeorge.sqlx4k.rust

import org.gradle.api.Project
import org.gradle.api.provider.Provider
import org.gradle.api.services.BuildService
import org.gradle.api.services.BuildServiceParameters

/**
 * Serializes the tasks that build or consume a module's Rust crate.
 *
 * Every `cargo build` of a crate, whatever the target triple, reruns `build.rs`, which rewrites the one
 * cbindgen header that the cinterop tasks read (`src/rust/target/<crate>.h`). Tasks of a single project
 * run in parallel under the configuration cache, so a cargo build for one triple could rewrite the
 * header while the cinterop of another triple reads it, producing a klib without declarations. The
 * cargo tasks (the cinterop builds, the JNI host-build fallback and the cargo-ndk Android build) and the
 * cinterop tasks of a module all use this service with a single parallel usage, so none of them overlap.
 *
 * One service per module: each module owns one crate and one cargo target directory, so there is nothing
 * to serialize across modules.
 */
abstract class CargoBuildService : BuildService<BuildServiceParameters.None> {
    companion object {
        /** The service guarding [project]'s crate. */
        fun of(project: Project): Provider<CargoBuildService> =
            project.gradle.sharedServices.registerIfAbsent(
                "cargo-${project.path.trim(':').replace(':', '-')}",
                CargoBuildService::class.java
            ) {
                maxParallelUsages.set(1)
            }
    }
}
