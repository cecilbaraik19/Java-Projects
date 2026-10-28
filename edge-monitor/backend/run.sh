#!/usr/bin/env bash
# Build and start the backend (it also serves the website from ../frontend).
set -e
cd "$(dirname "$0")"
rm -rf out && mkdir -p out/classes
find src/main/java -name '*.java' > out/sources.txt
javac -encoding UTF-8 -d out/classes @out/sources.txt
jar --create --file out/edge-monitor.jar --main-class com.cecil.edge.App -C out/classes .
echo "Starting... open http://localhost:${PORT:-8080}"
java -jar out/edge-monitor.jar
