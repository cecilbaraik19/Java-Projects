#!/usr/bin/env sh
# Compile and run all backend tests. No JUnit download needed.
set -e
cd "$(dirname "$0")"
rm -rf out && mkdir -p out/classes out/test
find src -name '*.java' > out/sources.txt
find test -name '*.java' > out/test-sources.txt
javac -encoding UTF-8 -d out/classes @out/sources.txt
javac -encoding UTF-8 -cp out/classes -d out/test @out/test-sources.txt
java -cp out/classes:out/test com.cecil.parisar.AllTests
