# ---------------------------------------------------------------
# CredentialChain - one image with the React UI + Spring Boot API
# Build & run everything with:  docker compose up --build
# ---------------------------------------------------------------

# 1) Build the React frontend
FROM node:20-alpine AS frontend
WORKDIR /app/frontend
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci
COPY frontend/ ./
RUN npx vite build --outDir dist

# 2) Build the Spring Boot jar (with the React build inside it)
FROM maven:3.9-eclipse-temurin-21 AS backend
WORKDIR /app/backend
COPY backend/pom.xml ./
RUN mvn -q -B dependency:go-offline
COPY backend/src ./src
COPY --from=frontend /app/frontend/dist ./src/main/resources/static
RUN mvn -q -B package -DskipTests

# 3) Small runtime image
FROM eclipse-temurin:21-jre
WORKDIR /app
RUN useradd --system --uid 1001 credchain && mkdir -p /app/data && chown credchain /app/data
COPY --from=backend /app/backend/target/credential-chain-1.0.0.jar app.jar
USER credchain
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
