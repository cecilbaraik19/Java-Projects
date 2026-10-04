# ---------- build ----------
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
# download dependencies first so they are cached between builds
COPY pom.xml .
RUN mvn -q -B dependency:go-offline
COPY src ./src
RUN mvn -q -B package -DskipTests

# ---------- run ----------
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
# run as an unprivileged user, never root
RUN addgroup -S app && adduser -S app -G app
COPY --from=build /app/target/attendance-dashboard-*.jar app.jar
USER app
ENV SPRING_PROFILES_ACTIVE=prod \
    TZ=Asia/Kolkata \
    JAVA_OPTS="-XX:MaxRAMPercentage=75 -Duser.timezone=Asia/Kolkata"
EXPOSE 8080
HEALTHCHECK --interval=15s --timeout=5s --start-period=60s --retries=5 \
  CMD wget -qO- http://localhost:8080/actuator/health | grep -q '"UP"' || exit 1
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
