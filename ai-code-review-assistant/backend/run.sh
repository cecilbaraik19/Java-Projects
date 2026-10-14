#!/bin/sh
# Build and start the backend (it also serves the website from ../frontend). Needs Java 17+.
cd "$(dirname "$0")" || exit 1
command -v javac >/dev/null || { echo "Java JDK 17+ not found."; exit 1; }
echo "Compiling backend..."
rm -rf out && mkdir -p out/classes
find src/main/java -name '*.java' > out/sources.txt
javac -encoding UTF-8 -d out/classes @out/sources.txt || { echo "Build failed."; exit 1; }
jar --create --file out/code-review.jar --main-class com.cecil.codereview.App -C out/classes .
echo "Starting... open http://localhost:8080 in your browser. Press Ctrl+C to stop."
exec java -jar out/code-review.jar "$@"
