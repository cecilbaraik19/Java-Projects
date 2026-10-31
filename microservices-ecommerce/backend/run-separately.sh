#!/usr/bin/env bash
# Starts every service as its own process (logs in out/logs). Ctrl+C stops them all.
set -e
cd "$(dirname "$0")"
rm -rf out && mkdir -p out/classes out/logs
find src/main/java -name '*.java' > out/sources.txt
javac -encoding UTF-8 -d out/classes @out/sources.txt
jar --create --file out/microservices-ecommerce.jar --main-class com.cecil.shop.App -C out/classes .
pids=()
trap 'kill "${pids[@]}" 2>/dev/null' EXIT
java -jar out/microservices-ecommerce.jar registry > out/logs/registry.log 2>&1 & pids+=($!)
sleep 2
for s in event-bus auth-service catalog-service inventory-service cart-service order-service payment-service notification-service api-gateway; do
  java -jar out/microservices-ecommerce.jar "$s" > "out/logs/$s.log" 2>&1 & pids+=($!)
  echo "started $s (pid $!)"
done
echo "Open http://localhost:8080 . Stop one with: kill <pid>. Ctrl+C stops all."
wait
