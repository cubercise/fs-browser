# fs-browser container image (built and verified with Podman; Docker-compatible).
#
# Multi-stage:
#   builder  -> maven:3.9-eclipse-temurin-25  compiles the WAR from source
#   runtime  -> eclipse-temurin:25-jre        runs it as a non-root user
#
# Both base images publish linux/amd64 and linux/arm64, so one Dockerfile
# serves x86 servers and Raspberry Pis; the WAR itself is architecture-neutral
# (Java bytecode plus static JS/CSS).

FROM docker.io/library/maven:3.9-eclipse-temurin-25 AS builder
WORKDIR /build
# Dependencies first, so the (large) Maven layer caches across source edits.
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
# Rest of the source (.dockerignore keeps target/ and node_modules/ out of
# the context; npm ci restores the frontend tree inside the image).
COPY . .
# Tests are skipped here on purpose: they already run in the normal
# `./mvnw package` build (Surefire backend tests + vitest frontend seam
# tests). Re-running them in every image layer only adds minutes of Pi time
# and memory pressure without changing the artifact. The image build's job
# is packaging, not verification.
RUN mvn -B package -DskipTests

FROM docker.io/library/eclipse-temurin:25-jre AS runtime
# Dedicated non-root user, fixed at uid/gid 1000 (rename of the base image's
# default "ubuntu" user) so bind-mounted host files owned by the usual first
# unprivileged user (uid 1000) are readable inside the container without
# chown tricks.
RUN usermod --login fsb --home /home/fsb --move-home --shell /usr/sbin/nologin ubuntu \
 && groupmod --new-name fsb ubuntu
WORKDIR /app
COPY --from=builder /build/target/fs-browser.war app.war
# Default Root. The application refuses to start if FSB_ROOT does not exist,
# so the mount point is baked in and owned by the runtime user.
RUN mkdir /data && chown fsb:fsb /data
USER fsb
EXPOSE 8080
# FSB_ROOT: the directory the app browses; point it at (or bind-mount your
# data at) /data. FSB_MODE: read-only (default, write endpoints answer 403)
# or read-write. Both are overridable with -e at run time.
ENV FSB_ROOT=/data \
    FSB_MODE=read-only
ENTRYPOINT ["java", "-jar", "/app/app.war"]
