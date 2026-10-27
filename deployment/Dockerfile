ARG BUILD_IMAGE=gradle:9.8.2-jdk25
ARG BASE_IMAGE=eclipse-temurin:25-jre

FROM ${BUILD_IMAGE} AS build
WORKDIR /workspace
COPY --chown=gradle:gradle . .
RUN gradle :app-service:bootJar --no-daemon -x test \
    && find applications/app-service/build/libs -name '*.jar' ! -name '*-plain.jar' -exec cp {} /workspace/application.jar \;

FROM ${BASE_IMAGE}
RUN addgroup --system ticketing && adduser --system --ingroup ticketing --no-create-home ticketing
WORKDIR /app
COPY --from=build --chown=ticketing:ticketing /workspace/application.jar application.jar
USER ticketing
ENV JAVA_OPTS="-XX:+UseContainerSupport -XX:MaxRAMPercentage=70"
ENTRYPOINT [ "/bin/sh", "-c", "exec java $JAVA_OPTS -jar /app/application.jar" ]
