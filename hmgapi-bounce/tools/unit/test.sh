#!/bin/sh
# Host unit test runner for BouncePolicy.
set -e
cd "$(dirname "$0")"
CP=$(mktemp -d); trap 'rm -rf "$CP"' EXIT
javac --release 8 -Xlint:-options -nowarn -d "$CP" \
    BounceUnitTest.java \
    ../../app/java/dev/lemoyne/bounce/BouncePolicy.java
java -cp "$CP" dev.lemoyne.bounce.BounceUnitTest
