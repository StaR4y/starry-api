FROM node:20-bookworm-slim AS webui-build

WORKDIR /workspace/webui
COPY webui/package*.json ./
RUN npm ci
COPY webui/ ./
RUN npm run build

FROM eclipse-temurin:25-jdk AS build

WORKDIR /workspace
COPY . .
COPY --from=webui-build /workspace/webui/dist/ /workspace/webui/dist/
RUN chmod +x gradlew \
    && ./gradlew --no-daemon :service:bootJar -x test -PskipWebuiBuild=true \
    && jar tf service/build/libs/service-0.1.0-SNAPSHOT.jar | grep -q '^BOOT-INF/classes/static/index.html$'

FROM eclipse-temurin:25-jre-noble

RUN set -eux; \
    apt-get update; \
    apt-get install -y --no-install-recommends ca-certificates curl; \
    install -d -m 0755 /usr/share/postgresql-common/pgdg; \
    curl -fsSL https://www.postgresql.org/media/keys/ACCC4CF8.asc \
        -o /usr/share/postgresql-common/pgdg/apt.postgresql.org.asc; \
    . /etc/os-release; \
    echo "deb [signed-by=/usr/share/postgresql-common/pgdg/apt.postgresql.org.asc] https://apt.postgresql.org/pub/repos/apt ${VERSION_CODENAME}-pgdg main" \
        > /etc/apt/sources.list.d/pgdg.list; \
    apt-get update; \
    apt-get install -y --no-install-recommends postgresql-client-17; \
    rm -rf /var/lib/apt/lists/*

WORKDIR /app
COPY --from=build /workspace/service/build/libs/service-0.1.0-SNAPSHOT.jar /app/app.jar

ENV SERVER_PORT=8080
ENV SERVER_ADDRESS=0.0.0.0
EXPOSE 8080

ENTRYPOINT ["sh", "-c", "exec java ${JAVA_OPTS:-} -jar /app/app.jar"]
