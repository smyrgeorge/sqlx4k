#!/usr/bin/env sh

set -e

# Builds the sqlx4k Gradle plugin and publishes it to mavenLocal.
#
# The example modules apply the sqlx4k Gradle plugin by id from mavenLocal, so it must be published
# before the main build can even configure — run this after a clean checkout and after every
# version bump. --configure-on-demand keeps Gradle from configuring the example modules (which
# would need the not-yet-published plugin). RELEASE_SIGNING_ENABLED=false leaves signing out of
# the publications entirely: mavenLocal artifacts need no signatures, and the CI build job has no
# signing keys (excluding the sign tasks with -x is NOT enough — publishing then fails on the
# missing .asc files on a clean workspace).
./gradlew \
    :sqlx4k-gradle-plugin:publishToMavenLocal \
    --configure-on-demand \
    -PRELEASE_SIGNING_ENABLED=false
