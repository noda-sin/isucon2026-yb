#!/usr/bin/env bash
set -euo pipefail
export PGHOST="${PGHOST:-tserver-asia-1}" PGPORT="${PGPORT:-5433}" PGUSER=yugabyte PGDATABASE=isureal_java
sql() { /home/yugabyte/bin/ysqlsh -X -v ON_ERROR_STOP=1 "$@"; }
mode=${JAVA_SEED_MODE:-smoke}
case "$mode" in smoke|empty|external) ;; *) echo 'JAVA_SEED_MODE must be smoke, empty, or external' >&2; exit 2;; esac
if [[ "$mode" == external && ! -r /seed/data.sql ]]; then
  echo 'External seed requires trusted data-only INSERT statements at /seed/data.sql (COPY into views is unsupported).' >&2
  exit 2
fi
for attempt in $(seq 1 120); do
  if sql -d yugabyte -Atqc 'SELECT 1' >/dev/null 2>&1; then break; fi
  if [[ "$attempt" == 120 ]]; then echo 'YSQL readiness timeout' >&2; exit 1; fi
  sleep 2
done
sql -d yugabyte <<'SQL'
SELECT 'CREATE DATABASE isureal_java' WHERE NOT EXISTS (SELECT 1 FROM pg_database WHERE datname='isureal_java')\gexec
SQL
if [[ $(sql -Atqc "SELECT to_regclass('geo.bootstrap_metadata') IS NOT NULL") == t ]]; then
  if [[ $(sql -Atqc 'SELECT schema_version FROM geo.bootstrap_metadata WHERE singleton') == 1 ]]; then
    echo 'Existing geo database retained.'
    exit 0
  fi
fi
if [[ $(sql -Atqc "SELECT EXISTS(SELECT 1 FROM pg_namespace WHERE nspname='geo')") == t ]]; then
  echo 'Incomplete or different geo schema exists; refusing automatic replacement.' >&2
  exit 1
fi
sql -f /geo/schema.sql
case "$mode" in
  smoke) sql -U isureal_java_app -1 -f /geo/smoke.sql ;;
  external) sql -U isureal_java_app -1 -f /seed/data.sql ;;
  empty) echo 'Empty Java application data selected.' ;;
esac
sql -v mode="$mode" <<'SQL'
CREATE TABLE geo.bootstrap_metadata (
  singleton boolean PRIMARY KEY CHECK(singleton),
  schema_version integer NOT NULL,
  seed_mode text NOT NULL,
  initialized_at timestamptz NOT NULL DEFAULT clock_timestamp()
) TABLESPACE local_global SPLIT INTO 1 TABLETS;
INSERT INTO geo.bootstrap_metadata VALUES(true,1,:'mode',clock_timestamp());
SQL
echo 'Geo database initialized: 15 entity tables, 45 regional partitions, global constraint registries.'
