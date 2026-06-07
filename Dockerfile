FROM node:20-bookworm-slim AS webui-build

WORKDIR /workspace/webui
COPY webui/package*.json ./
RUN npm ci
COPY webui/ ./
RUN npm run build

FROM eclipse-temurin:17-jdk AS build

WORKDIR /workspace
COPY . .
COPY --from=webui-build /workspace/webui/dist/ /workspace/webui/dist/
RUN chmod +x gradlew && ./gradlew --no-daemon :service:bootJar -x test -PskipWebuiBuild=true

FROM eclipse-temurin:17-jre

WORKDIR /app
COPY --from=build /workspace/service/build/libs/service-0.1.0-SNAPSHOT.jar /app/app.jar

ENV SERVER_PORT=8080
EXPOSE 8080

ENTRYPOINT ["sh", "-c", "exec java ${JAVA_OPTS:-} -jar /app/app.jar"]
