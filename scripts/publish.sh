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

./gradlew clean build -Ptargets=all

./gradlew :dokkaHtmlMultiModule
rm -rf ./docs/*
cp -R ./build/dokka/htmlMultiModule/* ./docs/

version=$(./gradlew properties -q | awk '/^version:/ {print $2}')
git add --all
git commit -m "Added documentation for '$version'."
git push

git tag "$version" -f
git push --tags -f

./gradlew publishAllPublicationsToMavenCentralRepository -Ptargets=all
git checkout .
