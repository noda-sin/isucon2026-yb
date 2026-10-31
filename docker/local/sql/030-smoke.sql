-- SYNTHETIC DATA ONLY. Not official seed, not recovered production data.
-- All three users have the documented local-only secret: local-smoke-secret.
INSERT INTO users (id, username, secret_key_hash, display_name, region, created_at, updated_at)
SELECT '00000000-0000-4000-8000-00000000000' || n,
       'smoke_' || region,
       'dfc1aadb7fd9bec81e17b28eef110cd11ff84c3f38e3045ce59857af14b92e3f',
       'Synthetic ' || region, region,
       '2026-01-01T00:00:00Z'::timestamptz, '2026-01-01T00:00:00Z'::timestamptz
FROM (VALUES ('1', 'asia'), ('2', 'europe'), ('3', 'america')) AS seed(n,region);

INSERT INTO posting_windows
  (id, region, local_date, starts_at, on_time_deadline, created_at)
SELECT '10000000-0000-4000-8000-00000000000' || n, region,
       (clock_timestamp() AT TIME ZONE 'UTC')::date,
       clock_timestamp() - interval '1 minute',
       clock_timestamp() + interval '1 hour', '2026-01-01T00:00:00Z'::timestamptz
FROM (VALUES ('1', 'asia'), ('2', 'europe'), ('3', 'america')) AS seed(n,region);
