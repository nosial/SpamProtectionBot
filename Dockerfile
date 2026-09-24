#
#   SpamProtectionBot Docker Image
#
#   Multi-stage build: compiles the shaded jar with Maven, then ships it on a minimal JRE image.
#   The bot needs a configuration.yml (see configuration.sample.yml) bind-mounted at
#   /app/configuration.yml, and persists its SQLite database under /data.
#

# The pom targets --release 17 for the bot's own code, but the JFederation dependency itself
# ships class files compiled for Java 24 — both the compiler that reads it and the JVM that later
# loads it at runtime need to be 24 or newer, regardless of the bot's own release target.
FROM maven:3.9-eclipse-temurin-24-alpine AS builder

WORKDIR /build

COPY pom.xml .
COPY src ./src

RUN mvn -B package -DskipTests

FROM eclipse-temurin:24-jre-alpine

ARG BUILD_DATE
ARG REVISION
ARG VERSION=1.0.0

LABEL org.opencontainers.image.title="SpamProtectionBot" \
      org.opencontainers.image.description="Telegram spam-protection bot backed by the Open Federated Database specification" \
      org.opencontainers.image.version="${VERSION}" \
      org.opencontainers.image.revision="${REVISION}" \
      org.opencontainers.image.created="${BUILD_DATE}" \
      org.opencontainers.image.source="https://github.com/nosial/SpamProtectionBot" \
      org.opencontainers.image.license="MIT"

RUN apk add --no-cache procps \
    && addgroup -S spb && adduser -S spb -G spb

WORKDIR /app

COPY --from=builder /build/target/spb-*.jar /app/spb.jar

RUN mkdir -p /data && chown -R spb:spb /app /data

USER spb

VOLUME ["/data"]

STOPSIGNAL SIGTERM

# The configuration and database paths are read from the environment rather than passed as
# arguments, so `docker run -e` or a compose file's `environment` can change them without replacing
# the entrypoint. Arguments given after the image name still take precedence over these.
ENV SPB_CONFIG=/app/configuration.yml \
    SPB_DATABASE=/data/database.db

ENTRYPOINT ["java", \
    "-XX:MaxRAMPercentage=75.0", \
    "-XX:+ExitOnOutOfMemoryError", \
    "--enable-native-access=ALL-UNNAMED", \
    "-jar", "/app/spb.jar"]

# There is no HTTP endpoint to probe — the bot only long-polls Telegram outbound — so this is a
# basic liveness check confirming the JVM is still running, not a deep readiness check.
HEALTHCHECK --interval=30s --timeout=5s --start-period=15s --retries=3 \
    CMD pgrep -f spb.jar || exit 1
