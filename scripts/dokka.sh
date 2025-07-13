#!/usr/bin/env sh

set -e

./gradlew :dokkaHtmlMultiModule
rm -rf ./docs/*
cp -R ./build/dokka/htmlMultiModule/* ./docs/

version=$(./gradlew properties -q | awk '/^version:/ {print $2}')
git add --all
git commit -m "Added documentation for '$version'."
