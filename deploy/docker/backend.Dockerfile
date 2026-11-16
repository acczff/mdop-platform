# syntax=docker/dockerfile:1

FROM eclipse-temurin:25-jre-noble AS runtime

RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --system --gid 10001 mdop \
    && useradd --system --uid 10001 --gid mdop --home-dir /app --shell /usr/sbin/nologin mdop

WORKDIR /app

ARG JAR_FILE=tmp/release/candidate/backend/mdop.jar
ARG RELEASE_CONFIG=deploy/release/application-release.yml

COPY --chown=mdop:mdop ${JAR_FILE} /app/mdop.jar
COPY --chown=mdop:mdop ${RELEASE_CONFIG} /app/config/application-release.yml

USER mdop:mdop

EXPOSE 8081 8091

HEALTHCHECK --interval=15s --timeout=5s --retries=5 --start-period=45s \
    CMD curl -fsS "http://127.0.0.1:${MDOP_MANAGEMENT_PORT:-8091}/actuator/health" || exit 1

ENTRYPOINT ["java", "-jar", "/app/mdop.jar", "--spring.config.additional-location=optional:file:/app/config/application-release.yml"]
