# Building

You will need the `Rust` toolchain to build this project. Check here: <https://rustup.rs/>

!!! note

    By default, the project will build only for your system architecture-os (e.g. `macosArm64`, `linuxArm64`, etc.)

Also, make sure that you have installed all the necessary targets (only if you want to build for all targets):

```shell
rustup target add aarch64-apple-ios-sim
rustup target add x86_64-linux-android
rustup target add aarch64-linux-android
rustup target add aarch64-apple-darwin
rustup target add aarch64-unknown-linux-gnu
rustup target add x86_64-unknown-linux-gnu
rustup target add x86_64-pc-windows-gnu
```

On a clean checkout (and after every version bump), bootstrap the build first: it publishes the sqlx4k Gradle plugin
to mavenLocal, which the example modules apply before the main build can even configure (see
[bootstrap.sh](https://github.com/smyrgeorge/sqlx4k/blob/main/scripts/bootstrap.sh)):

```shell
./scripts/bootstrap.sh
```

Then, run the build.

```shell
# will build only for the current target
./gradlew build
```

You can also build for specific targets.

```shell
./gradlew build -Ptargets=macosArm64
```

To build for all available targets, run:

```shell
./gradlew build -Ptargets=all
```

To run the examples, see [Examples](../getting-started/examples.md).

## Documentation

This site is built with [Zensical](https://zensical.org/) from the Markdown files under `docs/`, and the API reference
with Dokka. To preview the site locally:

```shell
pip install zensical
zensical serve
```

Both are built and deployed to GitHub Pages by the
[docs workflow](https://github.com/smyrgeorge/sqlx4k/blob/main/.github/workflows/docs.yml) on every release tag. To
generate the API reference locally:

```shell
./gradlew :dokka:dokkaGenerate
# Output: dokka/build/dokka/html
```

## Publishing

```shell
./gradlew publishAllPublicationsToMavenCentralRepository -Ptargets=all
```

## Checking for memory leaks

### macOS (using the `leaks` tool)

Check for memory leaks with the `leaks` tool. First, sign the binary:

```shell
codesign -s - -v -f --entitlements =(echo -n '<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "https://www.apple.com/DTDs/PropertyList-1.0.dtd"\>
<plist version="1.0">
    <dict>
        <key>com.apple.security.get-task-allow</key>
        <true/>
    </dict>
</plist>') ./bench/postgres-sqlx4k/build/bin/macosArm64/releaseExecutable/postgres-sqlx4k.kexe
```

Then run the tool:

```shell
leaks -atExit -- ./bench/postgres-sqlx4k/build/bin/macosArm64/releaseExecutable/postgres-sqlx4k.kexe
```
