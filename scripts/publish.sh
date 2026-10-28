#!/usr/bin/env sh

set -e

# brew install --cask android-ndk
export ANDROID_NDK_HOME="/opt/homebrew/share/android-ndk"
export PATH="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/darwin-x86_64/bin:$PATH"

# https://github.com/MaterializeInc/homebrew-crosstools
# brew install MaterializeInc/crosstools/aarch64-unknown-linux-gnu
export CC_aarch64_unknown_linux_gnu=aarch64-unknown-linux-gnu-gcc
export CXX_aarch64_unknown_linux_gnu=aarch64-unknown-linux-gnu-g++

# https://github.com/MaterializeInc/homebrew-crosstools
# brew install MaterializeInc/crosstools/x86_64-unknown-linux-gnu
export CC_x86_64_unknown_linux_gnu=x86_64-unknown-linux-gnu-gcc
export CXX_x86_64_unknown_linux_gnu=x86_64-unknown-linux-gnu-g++

# brew install mingw-w64
export CC_x86_64_w64_mingw32=x86_64-w64-mingw32-gcc
export CXX_x86_64_w64_mingw32=x86_64-w64-mingw32-g++

# Publish the sqlx4k Gradle plugin to mavenLocal first — the example modules need it at the
# (possibly just bumped) current version before the build can configure.
./scripts/bootstrap.sh

./gradlew build -Ptargets=all

version=$(./gradlew properties -q | awk '/^version:/ {print $2}')

# Set the released version in the README snippets: the sqlx4k dependencies and the Gradle plugin, whatever version
# they carry (the previous release, or the x.y.z placeholder). The commit is part of the tagged release.
# The files are kept in the positional parameters (the script takes no arguments): sh has no arrays.
set -- README.md sqlx4k-sqlite/README.md sqlx4k-postgres-pgmq/README.md
perl -pi -e '
    s/(io\.github\.smyrgeorge:sqlx4k[\w-]*:)[^")\s]+/${1}'"$version"'/g;
    s/(id\("io\.github\.smyrgeorge\.sqlx4k"\) version ")[^"]+/${1}'"$version"'/g;
' "$@"
git diff --quiet -- "$@" || git commit -m "Set the version in the README files to '$version'." -- "$@"
git push

# Pushing the tag also publishes the documentation (the guides and the Dokka API reference) to GitHub Pages,
# through the docs workflow (.github/workflows/docs.yml).
git tag "$version" -f
git push --tags -f

./gradlew publishAllPublicationsToMavenCentralRepository -Ptargets=all
git checkout .
