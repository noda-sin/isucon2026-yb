-- Stage 1: preserve the initial application's globally unique IDs and foreign keys.
SET default_tablespace = 'local_global';
\i /initial-sql/001-schema.sql

-- The initial app serializes registration with LOCK TABLE. Also enforce the
-- invariant in the database, across all regions and direct seed imports.
CREATE UNIQUE INDEX users_username_global_unique ON users(username);

-- This is a topology fixture, NOT a migrated application table.
-- It is intentionally separate from users/posts so we do not pretend that
-- adding region to their PKs preserves their existing FK/API contracts.
-- A parent's explicit default tablespace is inherited by its PK index. Reset
-- it so each child's PK follows that child's regional tablespace in YSQL.
RESET default_tablespace;
CREATE TABLE locality_probe (
  region text NOT NULL,
  id text NOT NULL,
  payload text NOT NULL,
  PRIMARY KEY (region, id)
) PARTITION BY LIST (region);
CREATE TABLE locality_probe_asia PARTITION OF locality_probe
  FOR VALUES IN ('asia') TABLESPACE local_asia;
CREATE TABLE locality_probe_europe PARTITION OF locality_probe
  FOR VALUES IN ('europe') TABLESPACE local_europe;
CREATE TABLE locality_probe_america PARTITION OF locality_probe
  FOR VALUES IN ('america') TABLESPACE local_america;
INSERT INTO locality_probe VALUES
  ('asia', 'placement', 'synthetic topology probe'),
  ('europe', 'placement', 'synthetic topology probe'),
  ('america', 'placement', 'synthetic topology probe');
