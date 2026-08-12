FROM eclipse-temurin:21-jre@sha256:273396ed5998598ed1091e8d72711c2d36980a0e65103859c55a4e977a41ffd3

RUN groupadd --system --gid 10001 app && \
    useradd --uid 10001 --gid 10001 --no-create-home --home-dir /nonexistent --shell /usr/sbin/nologin app && \
    mkdir -p /opt/easysubway/logs /opt/easysubway/native && \
    chown 10001:10001 /opt/easysubway/logs

WORKDIR /app

# bootJar만 복사한다. Gradle plain jar(*-plain.jar)는 제외한다.
COPY --chown=10001:10001 build/libs/ /tmp/jars/
COPY --chown=0:0 build/sqlite-native/linux-aarch64/libsqlitejdbc.so /opt/easysubway/native/libsqlitejdbc.so
RUN set -eux; \
    jar="$(find /tmp/jars -maxdepth 1 -type f -name '*.jar' ! -name '*-plain.jar' | head -n 1)"; \
    test -n "$jar"; \
    mv "$jar" /app/app.jar; \
    rm -rf /tmp/jars; \
    chmod 0555 /opt/easysubway/native; \
    chmod 0444 /opt/easysubway/native/libsqlitejdbc.so

EXPOSE 8080

ENV SPRING_PROFILES_ACTIVE=prod \
    JAVA_TOOL_OPTIONS="-Dorg.sqlite.lib.path=/opt/easysubway/native -Dorg.sqlite.lib.name=libsqlitejdbc.so"

USER 10001:10001

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
