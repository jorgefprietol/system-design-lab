#!/bin/sh
set -eu
app_password=$(cat /run/secrets/app/app-password)
bootstrap_sha=$(sha256sum /docker-entrypoint-initdb.d/commerce.sql.in | cut -d ' ' -f 1)
psql --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" --set ON_ERROR_STOP=1 --set "app_password=$app_password" --set "bootstrap_sha=$bootstrap_sha" --file /docker-entrypoint-initdb.d/commerce.sql.in
