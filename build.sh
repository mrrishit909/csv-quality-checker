#!/bin/bash
# Compile, run the tests, and package dq.jar. Needs only a JDK (21+): no Maven, no dependencies.
#   ./build.sh            then:  java -jar dq.jar data.csv
set -euo pipefail
cd "$(dirname "$0")"
rm -rf out && mkdir -p out
javac -d out --release 21 -Xlint:all $(find src test -name '*.java')
java -cp out dq.Tests
if [ -f src/dq/Main.java ]; then
  jar --create --file dq.jar --main-class dq.Main -C out dq
  echo "built dq.jar"
fi
