#!/usr/bin/env bash
set -euo pipefail

# Intentionally fixed to the isolated compose database. Never source env.sh.
export PGHOST="${PGHOST:-yb-asia-a}" PGPORT="${PGPORT:-5433}" PGUSER="${PGUSER:-yugabyte}"
export PGDATABASE=isureal
ysqlsh=/home/yugabyte/bin/ysqlsh
seed_mode=${SEED_MODE:-smoke}
case "$seed_mode" in smoke|empty|external) ;; *) echo 'SEED_MODE must be smoke, empty, or external' >&2; exit 2;; esac
if [[ "$seed_mode" == external && ! -r /seed/data.sql ]]; then
  echo 'External mode needs a trusted, data-only SQL file mounted at /seed/data.sql' >&2
  exit 2
fi

sql() { "$ysqlsh" -X -v ON_ERROR_STOP=1 "$@"; }
for attempt in $(seq 1 120); do
  if sql -d yugabyte -Atqc 'SELECT 1' >/dev/null 2>&1; then break; fi
  if [[ "$attempt" == 120 ]]; then echo 'YSQL did not become ready' >&2; exit 1; fi
  sleep 2
done

exists=$(sql -d yugabyte -Atqc "SELECT count(*) FROM pg_database WHERE datname='isureal'")
if [[ "$exists" == 1 ]]; then
  marker=$(sql -Atqc "SELECT to_regclass('public.local_bootstrap_metadata') IS NOT NULL")
  if [[ "$marker" == t ]]; then
    version=$(sql -Atqc 'SELECT schema_version FROM local_bootstrap_metadata WHERE singleton')
    if [[ "$version" == 1 ]]; then
      echo 'Existing initialized local database retained; no data or schema was reset.'
      exit 0
    fi
  fi
  echo 'Database exists without the expected completion marker. Refusing to overwrite it; inspect the previous init error.' >&2
  exit 1
fi

sql -d yugabyte -f /local/sql/010-tablespaces.sql
sql -d yugabyte -c 'CREATE DATABASE isureal'
sql -f /local/sql/020-bootstrap.sql
case "$seed_mode" in
  smoke) sql -f /local/sql/030-smoke.sql ;;
  external) sql -f /seed/data.sql ;;
  empty) echo 'Empty application dataset selected (topology probe retained).' ;;
esac

# Snapshot is always made AFTER the selected dataset; no partial dump is read.
sql -c "SET default_tablespace = 'local_global'" -f /initial-sql/003-initial-snapshot.sql
sql -c "UPDATE initial_data_boundary SET created_at = COALESCE(created_at, '-infinity'::timestamptz)"
sql -v seed_mode="$seed_mode" <<'SQL'
CREATE TABLE local_bootstrap_metadata (
  singleton boolean PRIMARY KEY CHECK (singleton),
  schema_version integer NOT NULL,
  seed_mode text NOT NULL,
  created_at timestamptz NOT NULL DEFAULT clock_timestamp()
) TABLESPACE local_global;
INSERT INTO local_bootstrap_metadata (singleton, schema_version, seed_mode)
VALUES (true, 1, :'seed_mode');
SQL
echo 'Local database bootstrap completed.'
