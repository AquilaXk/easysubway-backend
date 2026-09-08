FROM eclipse-temurin:21-jre@sha256:7a65df4b22d2de92d4e04056e884f3b9122d70b21e2847fd66084278bd0ce037

RUN groupadd --system --gid 10001 app && \
    useradd --uid 10001 --gid 10001 --no-create-home --home-dir /nonexistent --shell /usr/sbin/nologin app && \
    mkdir -p /opt/easysubway/logs /opt/easysubway/native && \
    chown 10001:10001 /opt/easysubway/logs

WORKDIR /app

# bootJar만 복사한다. Gradle plain jar(*-plain.jar)는 제외한다.
COPY --chown=10001:10001 build/libs/ /tmp/jars/
COPY --chown=0:0 build/sqlite-native/linux-aarch64/libsqlitejdbc.so /opt/easysubway/native/libsqlitejdbc.so
COPY --chown=0:0 build/zstd-native/linux-aarch64/libzstd-jni-1.5.7-13.so /opt/easysubway/native/libzstd-jni-1.5.7-13.so
RUN set -eux; \
    jar="$(find /tmp/jars -maxdepth 1 -type f -name '*.jar' ! -name '*-plain.jar' | head -n 1)"; \
    test -n "$jar"; \
    mv "$jar" /app/app.jar; \
    rm -rf /tmp/jars; \
    chmod 0555 /opt/easysubway/native; \
    chmod 0444 /opt/easysubway/native/libsqlitejdbc.so /opt/easysubway/native/libzstd-jni-1.5.7-13.so

EXPOSE 8080

ENV SPRING_PROFILES_ACTIVE=prod

USER 10001:10001

ENTRYPOINT ["java", "-Dorg.sqlite.lib.path=/opt/easysubway/native", "-Dorg.sqlite.lib.name=libsqlitejdbc.so", "-DZstdNativePath=/opt/easysubway/native/libzstd-jni-1.5.7-13.so", "-jar", "/app/app.jar"]
