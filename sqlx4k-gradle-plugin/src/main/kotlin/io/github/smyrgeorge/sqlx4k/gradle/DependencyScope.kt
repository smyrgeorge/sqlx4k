package io.github.smyrgeorge.sqlx4k.gradle

/**
 * The configuration the sqlx4k library dependencies are added to (see [Sqlx4kExtension.dependencyScope]).
 *
 * @property kmpConfiguration the configuration of a multiplatform project (`commonMain`).
 * @property jvmConfiguration the configuration of a plain JVM project (`main`).
 */
public enum class DependencyScope(internal val kmpConfiguration: String, internal val jvmConfiguration: String) {
    /** The dependencies are internal to the project: `implementation`. */
    Implementation("commonMainImplementation", "implementation"),

    /**
     * The dependencies are part of the project's API and visible to its consumers: `api`. Use it in
     * a library module whose public signatures (e.g. the generated code) expose sqlx4k types.
     */
    Api("commonMainApi", "api"),
}
