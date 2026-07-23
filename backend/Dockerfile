FROM eclipse-temurin:21-jre@sha256:273396ed5998598ed1091e8d72711c2d36980a0e65103859c55a4e977a41ffd3

RUN groupadd --system --gid 10001 app && \
    useradd --uid 10001 --gid 10001 --no-create-home --home-dir /nonexistent --shell /usr/sbin/nologin app && \
    mkdir -p /opt/easysubway/logs && \
    chown 10001:10001 /opt/easysubway/logs

WORKDIR /app

# bootJar만 복사한다. Gradle plain jar(*-plain.jar)는 제외한다.
COPY --chown=10001:10001 build/libs/ /tmp/jars/
RUN set -eux; \
    jar="$(find /tmp/jars -maxdepth 1 -type f -name '*.jar' ! -name '*-plain.jar' | head -n 1)"; \
    test -n "$jar"; \
    mv "$jar" /app/app.jar; \
    rm -rf /tmp/jars

EXPOSE 8080

ENV SPRING_PROFILES_ACTIVE=prod

USER 10001:10001

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
