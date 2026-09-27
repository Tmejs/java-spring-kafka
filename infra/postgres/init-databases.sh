#!/bin/sh
set -eu

psql --set ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname postgres \
  --set orders_user="$ORDERS_DB_USERNAME" \
  --set orders_password="$ORDERS_DB_PASSWORD" \
  --set inventory_user="$INVENTORY_DB_USERNAME" \
  --set inventory_password="$INVENTORY_DB_PASSWORD" \
  --set keycloak_user="$KEYCLOAK_DB_USERNAME" \
  --set keycloak_password="$KEYCLOAK_DB_PASSWORD" <<'SQL'
SELECT format('CREATE ROLE %I LOGIN PASSWORD %L', :'orders_user', :'orders_password')
WHERE NOT EXISTS (SELECT FROM pg_roles WHERE rolname = :'orders_user') \gexec
SELECT format('ALTER ROLE %I WITH LOGIN PASSWORD %L', :'orders_user', :'orders_password') \gexec

SELECT format('CREATE ROLE %I LOGIN PASSWORD %L', :'inventory_user', :'inventory_password')
WHERE NOT EXISTS (SELECT FROM pg_roles WHERE rolname = :'inventory_user') \gexec
SELECT format('ALTER ROLE %I WITH LOGIN PASSWORD %L', :'inventory_user', :'inventory_password') \gexec

SELECT format('CREATE ROLE %I LOGIN PASSWORD %L', :'keycloak_user', :'keycloak_password')
WHERE NOT EXISTS (SELECT FROM pg_roles WHERE rolname = :'keycloak_user') \gexec
SELECT format('ALTER ROLE %I WITH LOGIN PASSWORD %L', :'keycloak_user', :'keycloak_password') \gexec

SELECT format('CREATE DATABASE orders OWNER %I', :'orders_user')
WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = 'orders') \gexec
SELECT format('ALTER DATABASE orders OWNER TO %I', :'orders_user') \gexec
REVOKE CONNECT ON DATABASE orders FROM PUBLIC;
SELECT format('GRANT CONNECT ON DATABASE orders TO %I', :'orders_user') \gexec

SELECT format('CREATE DATABASE inventory OWNER %I', :'inventory_user')
WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = 'inventory') \gexec
SELECT format('ALTER DATABASE inventory OWNER TO %I', :'inventory_user') \gexec
REVOKE CONNECT ON DATABASE inventory FROM PUBLIC;
SELECT format('GRANT CONNECT ON DATABASE inventory TO %I', :'inventory_user') \gexec

SELECT format('CREATE DATABASE keycloak OWNER %I', :'keycloak_user')
WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = 'keycloak') \gexec
SELECT format('ALTER DATABASE keycloak OWNER TO %I', :'keycloak_user') \gexec
REVOKE CONNECT ON DATABASE keycloak FROM PUBLIC;
SELECT format('GRANT CONNECT ON DATABASE keycloak TO %I', :'keycloak_user') \gexec
SQL
