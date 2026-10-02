FROM mcr.microsoft.com/dotnet/sdk:10.0.401@sha256:83e0db97c45d2e39b80123fe42940a23c423405a17f80b608a4b8768033d6392 AS build
WORKDIR /src
COPY global.json ./
COPY csharp/SystemDesignLab.csproj csharp/
RUN dotnet restore csharp/SystemDesignLab.csproj
COPY csharp/ csharp/
RUN dotnet publish csharp/SystemDesignLab.csproj -c Release --no-restore -o /out

FROM mcr.microsoft.com/dotnet/aspnet:10.0@sha256:57460add89e2b3dd1950c41d8b7dc96eeb7a24d13d98e3656ce9997a8b746bd6
RUN apt-get update && apt-get install -y --no-install-recommends curl ca-certificates && rm -rf /var/lib/apt/lists/* \
    && groupadd --gid 10001 lab && useradd --uid 10001 --gid lab --no-create-home lab \
    && mkdir /data && chown lab:lab /data
WORKDIR /app
COPY --from=build /out/ ./
COPY web/ ./web/
ARG REVISION=development
LABEL org.opencontainers.image.source="https://github.com/jorgefprietol/system-design-lab" \
      org.opencontainers.image.revision="${REVISION}" org.atlas.commerce.schema="1"
ENV LAB_ROOT=/app LAB_DATA=/data LAB_HOST=0.0.0.0 LAB_PORT=8080 LAB_REVISION=${REVISION} DOTNET_EnableDiagnostics=0
USER 10001:10001
EXPOSE 8080
HEALTHCHECK --interval=10s --timeout=3s --start-period=15s --retries=5 CMD curl --fail --silent http://127.0.0.1:8080/health || exit 1
ENTRYPOINT ["dotnet", "SystemDesignLab.dll"]
