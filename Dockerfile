# Builds the whole game into one image: the Vue app is bundled into the Spring Boot
# jar, so the page and /api share one address. Used by Render (see render.yaml).

# 1. Frontend
FROM node:20-alpine AS frontend
WORKDIR /app/frontend
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci
COPY frontend/ ./
RUN npm run build

# 2. Backend (pom.xml picks up ../frontend/dist as static files)
FROM maven:3.9-eclipse-temurin-17 AS backend
WORKDIR /app/backend
COPY backend/pom.xml ./
RUN mvn -q -B dependency:go-offline
COPY backend/src ./src
COPY --from=frontend /app/frontend/dist /app/frontend/dist
RUN mvn -q -B -DskipTests package && cp target/wargames-api-*.jar /app/app.jar

# 3. Runtime
FROM eclipse-temurin:17-jre-alpine
WORKDIR /app
COPY --from=backend /app/app.jar app.jar
# Fit in Render's free 512 MB instance.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=70 -XX:+UseSerialGC -Xss512k"
EXPOSE 8080
CMD ["java", "-jar", "app.jar"]
