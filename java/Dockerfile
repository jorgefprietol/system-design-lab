FROM maven:3.9-eclipse-temurin-26@sha256:b2c1ad85954592f9928e84327c65201f308ad9b5d8ed7d5b823717c97bf23fbb AS build
WORKDIR /src
COPY java/pom.xml ./
RUN mvn -B -q dependency:go-offline
COPY java/src/ src/
RUN mvn -B -q package

FROM eclipse-temurin:21-jre@sha256:cff19e6215689161eb6162c11b86b0c60ddf802164f2eaf48d570f8fb79a36c5
RUN apt-get update && apt-get install -y --no-install-recommends curl ca-certificates && rm -rf /var/lib/apt/lists/* \
    && groupadd --gid 10001 lab && useradd --uid 10001 --gid lab --no-create-home lab \
    && mkdir /data && chown lab:lab /data
WORKDIR /app
COPY --from=build /src/target/system-design-lab-1.0.0.jar ./lab.jar
COPY web/ ./web/
ARG REVISION=development
LABEL org.opencontainers.image.source="https://github.com/jorgefprietol/system-design-lab" \
      org.opencontainers.image.revision="${REVISION}" org.atlas.commerce.schema="1"
ENV LAB_ROOT=/app LAB_DATA=/data LAB_HOST=0.0.0.0 LAB_PORT=8080 LAB_REVISION=${REVISION}
USER 10001:10001
EXPOSE 8080
HEALTHCHECK --interval=10s --timeout=3s --start-period=15s --retries=5 CMD curl --fail --silent http://127.0.0.1:8080/health || exit 1
ENTRYPOINT ["java", "-jar", "lab.jar"]
