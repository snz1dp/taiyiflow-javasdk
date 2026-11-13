ARG BUILD_IMAGE=docker.m.daocloud.io/library/eclipse-temurin:21-jdk
ARG RUNTIME_IMAGE=docker.m.daocloud.io/library/eclipse-temurin:21-jre

FROM ${BUILD_IMAGE} AS build
WORKDIR /workspace
COPY . .
RUN ./mvnw -B -ntp -pl taiyiflow-sdk-worker -am clean package -DskipTests

FROM ${RUNTIME_IMAGE}
RUN groupadd --system taiyiflow \
    && useradd --system --gid taiyiflow --home-dir /app taiyiflow \
    && mkdir -p /app/lib /config /var/lib/taiyiflow/worker /var/lib/taiyiflow/runtime-state /var/lib/taiyiflow-credentials \
    && chown -R taiyiflow:taiyiflow /app /var/lib/taiyiflow /var/lib/taiyiflow-credentials
COPY --from=build /workspace/taiyiflow-sdk-worker/target/*-all.jar /app/worker.jar
COPY deploy/worker.yml /config/worker.yml
USER taiyiflow
ENV TAIYIFLOW_WORKER_CONFIG=/config/worker.yml
VOLUME ["/var/lib/taiyiflow/worker", "/var/lib/taiyiflow/runtime-state", "/var/lib/taiyiflow-credentials"]
STOPSIGNAL SIGTERM
ENTRYPOINT ["java", "-cp", "/app/worker.jar:/app/lib/*", "io.taiyiflow.sdk.worker.DigitalWorkerMain"]
