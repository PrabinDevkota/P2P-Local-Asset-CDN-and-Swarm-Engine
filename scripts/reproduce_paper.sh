#!/bin/sh
# Recreate the B0 smoke table from a fresh checkout (blueprint P10-04).
set -eu
cd "$(dirname "$0")/.."
./mvnw -q -pl benchmark-runner -am org.codehaus.mojo:exec-maven-plugin:3.5.0:java -Dexec.mainClass=com.prabin.swarmedge.benchmark.ReproducePaper -Dexec.classpathScope=compile
