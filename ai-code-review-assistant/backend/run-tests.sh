#!/bin/sh
cd "$(dirname "$0")" || exit 1
rm -rf out && mkdir -p out/classes out/test
find src/main/java -name '*.java' > out/sources.txt
find src/test/java -name '*.java' > out/test-sources.txt
javac -encoding UTF-8 -d out/classes @out/sources.txt || exit 1
javac -encoding UTF-8 -cp out/classes -d out/test @out/test-sources.txt || exit 1
java -cp "out/classes:out/test" com.cecil.codereview.AllTests
