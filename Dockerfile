# The image of Starry API. CI builds and publishes it on release tags (.github/workflows/release.yml), and
# "docker compose up -d --build" builds it from a checkout.
ARG STARRY_VERSION=dev

FROM node:22-bookworm-slim AS webui-build

ARG STARRY_VERSION
WORKDIR /workspace/webui
COPY webui/package*.json ./
RUN npm ci
COPY webui/ ./
RUN VITE_APP_VERSION="${STARRY_VERSION}" npm run build

FROM eclipse-temurin:25-jdk AS build

WORKDIR /workspace
COPY . .
COPY --from=webui-build /workspace/webui/dist/ /workspace/webui/dist/
RUN chmod +x gradlew \
    && ./gradlew --no-daemon :service:bootJar :service:copyleftSources -x test -PskipWebuiBuild=true \
    && jar tf service/build/libs/starry-api.jar | grep -q '^BOOT-INF/classes/static/index.html$'

FROM eclipse-temurin:25-jre-noble

ARG STARRY_VERSION
LABEL org.opencontainers.image.title="Starry API" \
      org.opencontainers.image.description="Self-hosted LLM API gateway and management console" \
      org.opencontainers.image.source="https://github.com/StaR4y/starry-api" \
      org.opencontainers.image.licenses="AGPL-3.0-only" \
      org.opencontainers.image.version="${STARRY_VERSION}"

RUN set -eux; \
    apt-get update; \
    apt-get install -y --no-install-recommends ca-certificates curl jq; \
    install -d -m 0755 /usr/share/postgresql-common/pgdg; \
    curl -fsSL https://www.postgresql.org/media/keys/ACCC4CF8.asc \
        -o /usr/share/postgresql-common/pgdg/apt.postgresql.org.asc; \
    . /etc/os-release; \
    echo "deb [signed-by=/usr/share/postgresql-common/pgdg/apt.postgresql.org.asc] https://apt.postgresql.org/pub/repos/apt ${VERSION_CODENAME}-pgdg main" \
        > /etc/apt/sources.list.d/pgdg.list; \
    apt-get update; \
    apt-get install -y --no-install-recommends postgresql-client-17; \
    rm -rf /var/lib/apt/lists/*

# CVE-2026-91403: eight eMudhra intermediate CAs whose private keys can be recomputed chain to emSign ECC Root CA - G3
# and to AAA Certificate Services, and no JDK release distrusts them yet. None of the upstreams this gateway calls
# chains to these roots (Sectigo sites validate through the USERTrust roots), so drop them from the JRE trust store.
# Remove this step once the base image ships a JDK with the fix.
RUN set -eu; \
    list() { keytool -J-Duser.language=en -list -v -cacerts -storepass changeit; }; \
    test "$(list | grep -c '^Alias name: ')" -gt 100; \
    list | awk '/^Alias name: /{alias=substr($0,13)} /^Owner: .*(emSign|CN=AAA Certificate Services,)/{print alias}' \
        | while IFS= read -r alias; do keytool -delete -noprompt -cacerts -storepass changeit -alias "$alias"; done; \
    ! list | grep -E '^Owner: .*(emSign|CN=AAA Certificate Services,)'

WORKDIR /app
COPY --from=build /workspace/service/build/libs/starry-api.jar /app/app.jar
COPY --chmod=0555 scripts/recover-data-management.sh /app/bin/recover-data-management
# The license, the notices and the source code of the libraries used under a copyleft license travel with the binary
# (section 3 of NOTICE).
COPY LICENSE NOTICE /app/
COPY licenses/ /app/licenses/
COPY --from=build /workspace/service/build/third-party-sources/ /app/third-party-sources/

ENV SERVER_PORT=8080
ENV SERVER_ADDRESS=0.0.0.0
EXPOSE 8080

ENTRYPOINT ["sh", "-c", "exec java ${JAVA_OPTS:-} -jar /app/app.jar"]
