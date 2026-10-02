FROM postgres:17.10-alpine@sha256:742f40ea20b9ff2ff31db5458d127452988a2164df9e17441e191f3b72252193
# Patch the OS and replace the bundled Go privilege helper with Alpine's native helper.
RUN apk upgrade --no-cache && apk add --no-cache su-exec \
    && rm /usr/local/bin/gosu \
    && sed -i 's/exec gosu postgres/exec su-exec postgres/g' /usr/local/bin/docker-entrypoint.sh \
    && ! grep -q 'exec gosu' /usr/local/bin/docker-entrypoint.sh
ARG REVISION=development
LABEL org.opencontainers.image.source="https://github.com/jorgefprietol/system-design-lab" \
      org.opencontainers.image.revision="${REVISION}" org.atlas.commerce.schema="1"
