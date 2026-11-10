#!/usr/bin/env sh
# Build and start the backend (it also serves the website from ../frontend). Needs Java 17+.
set -e
cd "$(dirname "$0")"
command -v javac >/dev/null 2>&1 || { echo "Java JDK 17+ not found."; exit 1; }
echo "Compiling backend..."
rm -rf out && mkdir -p out/classes
find src -name '*.java' > out/sources.txt
javac -encoding UTF-8 -d out/classes @out/sources.txt
jar --create --file out/parisar-campus.jar --main-class com.cecil.parisar.App -C out/classes .
echo "Starting... open http://localhost:8080 in your browser. Press Ctrl+C to stop."
exec java -jar out/parisar-campus.jar
