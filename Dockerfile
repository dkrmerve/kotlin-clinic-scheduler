# syntax=docker/dockerfile:1.7
# ---- build stage: the test suite gates the image. A red test means no image. ----------------------------
FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace

# 1. Dependency layer: only the build scripts and the wrapper, so this layer is cached until they change.
COPY gradlew gradlew.bat settings.gradle.kts build.gradle.kts gradle.properties .editorconfig ./
COPY gradle ./gradle
RUN chmod +x gradlew && ./gradlew --no-daemon dependencies --configuration testRuntimeClasspath -q > /dev/null

# 2. Sources: lint, compile, run the fast suite (H2) and the coverage gates, then assemble the distribution.
#    The PostgreSQL suite (Testcontainers) needs a Docker daemon, which does not exist inside a build: CI runs it separately.
COPY src ./src
RUN ./gradlew --no-daemon ktlintCheck test koverVerify koverVerifyLayers installDist -x integrationTest

# ---- runtime stage --------------------------------------------------------------------------------------
FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S clinic && adduser -S -G clinic clinic && apk add --no-cache wget
WORKDIR /app
COPY --from=build --chown=clinic:clinic /workspace/build/install/kotlin-clinic-scheduler ./
USER clinic
ENV JAVA_TOOL_OPTIONS="-XX:+UseContainerSupport -XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError -Djava.security.egd=file:/dev/./urandom"
EXPOSE 8080
HEALTHCHECK --interval=10s --timeout=3s --start-period=20s --retries=12 \
  CMD wget -q -O /dev/null http://127.0.0.1:8080/health/ready || exit 1
ENTRYPOINT ["./bin/kotlin-clinic-scheduler"]
