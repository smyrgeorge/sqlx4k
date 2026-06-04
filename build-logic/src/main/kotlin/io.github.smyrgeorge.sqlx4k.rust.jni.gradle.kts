import io.github.smyrgeorge.sqlx4k.multiplatform.Utils
import io.github.smyrgeorge.sqlx4k.rust.RustJniExtension
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension

/**
 * Builds a Rust crate as a shared library for the JNI targets (JVM host + Android) and wires the
 * outputs into the build. Reusable by any module that ships a Rust crate consumed over JNI.
 *
 *  - **Android**: `cargo ndk` builds the per-ABI `.so` into `src/androidMain/jniLibs` (packaged
 *    into the AAR; requires the Android NDK + `cargo ndk`). Wired before the JNI-merge tasks.
 *  - **JVM host**: `cargo build` builds the host `dylib`/`so`/`dll`, copies it into the JVM
 *    resources (so it can be extracted from the classpath at runtime), and exposes its directory
 *    to `jvmTest` and the Android host (Robolectric) tests via the `<crate>.native.path` system
 *    property, which the runtime loader reads to `System.load` the host library.
 *
 * Kotlin/Native (FFI/cinterop) targets are built and linked separately by the
 * `io.github.smyrgeorge.sqlx4k.multiplatform.lib` plugin (the `rust(...)` helper).
 *
 * ```kotlin
 * plugins { id("io.github.smyrgeorge.sqlx4k.rust.jni") }
 * rustJni { crateName = "sqlx4k_sqlite_cipher" }
 * ```
 */
val rustJni = extensions.create("rustJni", RustJniExtension::class.java)

// Resolved lazily in afterEvaluate so the consuming module's `rustJni { }` values are visible.
afterEvaluate {
    val crateName = rustJni.crateName.ifEmpty {
        error("rustJni.crateName must be set in $path")
    }
    val rustDir = layout.projectDirectory.dir(rustJni.cargoDir)
    val jniLibsDir = layout.projectDirectory.dir("src/androidMain/jniLibs")
    val generatedResources = layout.buildDirectory.dir("generated/resources/jvmAndroid")
    val cargo: String = file("${System.getProperty("user.home")}/.cargo/bin/cargo")
        .takeIf { it.exists() }?.absolutePath ?: "cargo"

    // Files whose changes should trigger a Rust rebuild. Excludes `target/` (the cargo output
    // directory, inside rustDir) to avoid input/output overlap errors in Gradle.
    val rustSources = fileTree(rustDir) {
        include("Cargo.toml", "Cargo.lock", "build.rs", "cbindgen.toml")
        include("src/**")
    }

    // The JNI fan-out follows the selected Kotlin targets (`-Ptargets`, via Utils.targetsOf): the
    // default (build host only) stays light and needs no Android/cross toolchains, while
    // `-Ptargets=all` produces the full Android + multi-host JVM build. No separate flag is needed,
    // and the consuming module declares no target lists.
    val selectedTargets = Utils.targetsOf(project)

    // ── Android (cargo-ndk → jniLibs) ───────────────────────────────────────────────────────────
    // ABIs are derived from the selected androidNative targets. When none are selected (e.g. the
    // default build) the task is neither created nor wired, so no Android NDK / cargo-ndk is needed:
    // the AAR still assembles (without the `.so`), and the JVM/Robolectric host tests load the host
    // library built by `buildRustJvm` instead.
    val androidAbis = selectedTargets.mapNotNull {
        when (it) {
            "androidNativeArm64" -> "arm64-v8a"
            "androidNativeX64" -> "x86_64"
            else -> null
        }
    }
    if (androidAbis.isNotEmpty()) {
        val ndkCommand = buildList {
            add(cargo); add("ndk")
            androidAbis.forEach { add("-t"); add(it) }
            add("-o"); add(jniLibsDir.asFile.absolutePath)
            add("build"); add("--release")
        }
        val buildRustAndroid = tasks.register<Exec>("buildRustAndroid") {
            group = "rust"
            description = "Builds the Rust crate's Android JNI libraries (.so per ABI) via cargo-ndk into src/androidMain/jniLibs."
            workingDir(rustDir)
            inputs.files(rustSources).withPathSensitivity(PathSensitivity.RELATIVE)
            outputs.dir(jniLibsDir)
            commandLine(*ndkCommand.toTypedArray())
        }
        // Wire before any task that merges JNI libraries into the AAR.
        tasks.matching {
            it.name.contains("JniLib", ignoreCase = true) || it.name.contains("MergeJni", ignoreCase = true)
        }.configureEach { dependsOn(buildRustAndroid) }
    }

    // ── JVM / desktop hosts (cargo → JVM resources) ─────────────────────────────────────────────
    // Detect the build host; it is always bundled into the JVM jar (so jvmTest can load it).
    val hostOs = System.getProperty("os.name").lowercase()
    val hostArch = System.getProperty("os.arch").lowercase()
    val isMacHost = hostOs.contains("mac") || hostOs.contains("darwin")
    val isLinuxHost = hostOs.contains("linux")
    val isWindowsHost = hostOs.contains("windows")
    val isArm64Host = hostArch == "aarch64" || hostArch == "arm64"
    val hostTarget = when {
        isMacHost && isArm64Host -> "aarch64-apple-darwin"
        isMacHost -> "x86_64-apple-darwin"
        isLinuxHost && isArm64Host -> "aarch64-unknown-linux-gnu"
        isLinuxHost -> "x86_64-unknown-linux-gnu"
        isWindowsHost && isArm64Host -> "aarch64-pc-windows-msvc"
        isWindowsHost -> "x86_64-pc-windows-msvc"
        else -> error("Unsupported host for rustJni: os=$hostOs arch=$hostArch")
    }

    // For a Rust target triple, returns (cargo output lib path, classpath resource name). The
    // resource name must match the runtime loader's `hostNativeLibName()`: Unix prefixes with
    // `lib`, Windows doesn't; the extension is OS-specific; arm64 variants carry an `_aarch64`
    // suffix so one fat jar can hold every desktop host's library without collision.
    fun jvmLibInfo(triple: String): Pair<String, String> {
        val isWindows = triple.contains("windows")
        val isApple = triple.contains("apple")
        val isArm64 = triple.startsWith("aarch64") || triple.startsWith("arm64")
        val prefix = if (isWindows) "" else "lib"
        val ext = when {
            isApple -> "dylib"
            isWindows -> "dll"
            else -> "so"
        }
        val suffix = if (isArm64) "_aarch64" else ""
        val libFile = "target/$triple/release/$prefix$crateName.$ext"
        val resourceName = "$prefix$crateName$suffix.$ext"
        return libFile to resourceName
    }

    // Desktop hosts bundled into the JVM jar, derived from the selected native targets, plus the
    // build host (always — so jvmTest/Robolectric can load a matching library). The default build
    // yields just the host; `-Ptargets=all` yields the portable fat jar.
    val jvmTargets = (selectedTargets.mapNotNull {
        when (it) {
            "macosArm64" -> "aarch64-apple-darwin"
            "linuxArm64" -> "aarch64-unknown-linux-gnu"
            "linuxX64" -> "x86_64-unknown-linux-gnu"
            "mingwX64" -> "x86_64-pc-windows-gnu"
            else -> null
        }
    } + hostTarget).distinct()

    val buildJvmTasks = jvmTargets.map { triple ->
        val (libFile, _) = jvmLibInfo(triple)
        tasks.register<Exec>("buildRustJvm_$triple") {
            group = "rust"
            description = "Builds the Rust crate's JVM/desktop shared library for $triple."
            workingDir(rustDir)
            inputs.files(rustSources).withPathSensitivity(PathSensitivity.RELATIVE)
            outputs.file(rustDir.file(libFile))
            commandLine(cargo, "build", "--release", "--target", triple)
        }
    }

    // `Sync` (not `Copy`) so the resource dir mirrors exactly the currently-selected targets: a
    // library for a target that is no longer built (e.g. after dropping a host from `-Ptargets`) is
    // removed instead of lingering and being bundled into the jar.
    val copyNativeLibJvm = tasks.register<Sync>("copyNativeLibJvm") {
        group = "rust"
        description = "Syncs the built JVM/desktop native libraries into the JVM resources (jar packaging + host tests)."
        into(generatedResources)
        dependsOn(buildJvmTasks)
        jvmTargets.forEach { triple ->
            val (libFile, resourceName) = jvmLibInfo(triple)
            from(rustDir.file(libFile)) { rename(".+", resourceName) }
        }
    }

    // Package the host shared library into the JVM jar (extracted from the classpath at runtime).
    // Guard on the jvm *target* so we don't spuriously create `jvmMain` for native-only builds.
    val kmp = extensions.findByType(KotlinMultiplatformExtension::class.java)
    if (kmp != null && kmp.targets.findByName("jvm") != null) {
        kmp.sourceSets.getByName("jvmMain").resources.srcDir(generatedResources)
    }

    tasks.matching {
        it.name.startsWith("process") && (it.name.endsWith("Resources") || it.name.endsWith("JavaRes"))
    }.configureEach { dependsOn(copyNativeLibJvm) }
    tasks.matching { it.name == "jvmProcessResources" }.configureEach { dependsOn(copyNativeLibJvm) }

    // jvmTest and the Android host (Robolectric) tests run on the host JVM; both load the host
    // shared library from this directory via the `<crate>.native.path` system property.
    val nativePathProperty = "$crateName.native.path"
    tasks.withType<Test>().configureEach {
        if (name == "jvmTest" || name.contains("HostTest", ignoreCase = true)) {
            dependsOn(copyNativeLibJvm)
            systemProperty(nativePathProperty, generatedResources.get().asFile.absolutePath)
        }
    }
}
