# Smart JDBC integration checks

Run the opt-in probe as a short-lived container on the existing local cluster:

```sh
docker compose -f compose.yaml -f compose.geo.yaml -f compose.java.yaml --profile java run --rm --no-deps \
  -e ISUREAL_RUN_JDBC_PROBE=true --entrypoint java app-java-asia \
  -cp /app/isureal.jar isureal.JdbcProbe
```

Repeat with `app-java-europe` and `app-java-america`. Build the Java image first after source changes. This does not restart the running application services. The probe uses the application role and creates then deletes one synthetic user through the public compatibility view. It does not use official seed data.

The supported-operations gate asserts:

- Twelve simultaneously open smart-driver connections stay within the selected region and reach both local TServers.
- Two explicitly addressed JDBC sessions connect to distinct TServers.
- A Read Committed transaction sees another session's committed change in its second SELECT. Checking only the isolation setting is insufficient.
- `FOR NO KEY UPDATE` blocks a competing writer, the writer resumes after COMMIT, and its update is visible.

The application pools set `ApplicationName=isureal-java-<region>`; probe sessions use `isureal-jdbc-probe-<region>`. Inspect each TServer's `pg_stat_activity` to correlate those names with the expected region. `host(inet_server_addr())` is used to remove the address mask before comparing with DNS results.

## Partition NOWAIT is a separate capability gate

The pinned YugabyteDB `2025.2.8.0-b60` can return SQLSTATE `40001` after query-layer retries instead of PostgreSQL's immediate `55P03` when `FOR UPDATE NOWAIT` scans an unpruned partitioned table through `public.users`. This was reproduced with the smart JDBC driver. The upstream report describes the same partition-scan-dependent failure: [YugabyteDB issue #34844](https://github.com/yugabyte/yugabyte-db/issues/34844).

The default probe prints `KNOWN_LIMITATION` for this exact observed mismatch and continues independent blocking-lock checks. It does **not** report NOWAIT as passing. Unknown errors still fail immediately. The final supported-operations success message covers locality, Read Committed and blocking row locks only.

For the strict PostgreSQL-compatible NOWAIT gate, add:

```sh
-e ISUREAL_REQUIRE_PARTITION_NOWAIT=true
```

In this mode a `40001` mismatch causes a nonzero exit after the independent checks and cleanup. Keep this strict gate when evaluating a future YugabyteDB release; do not convert `40001` into a fake `55P03` in application code. The current application handlers do not use NOWAIT, so the limitation is outside their current SQL operation set.

## Observed local results (2026-10-31)

The Java 21 Docker image and fixed smart JDBC `42.7.3-yb-7` were tested against the running six-TServer cluster. All three default probe processes exited 0 for the supported-operations gate:

| Region | 12 smart connections | RC across two TServers | Blocking writer wait/release | Separate NOWAIT capability |
|---|---|---|---|---|
| Asia | Exactly its two local TServers | PASS | PASS | KNOWN_LIMITATION: `40001`, expected `55P03` |
| Europe | Exactly its two local TServers | PASS | PASS | PASS: `55P03` |
| America | Exactly its two local TServers | PASS | PASS | KNOWN_LIMITATION: `40001`, expected `55P03` |

The strict Asia probe exited **1** after completing the independent checks and removing its synthetic user, proving that `ISUREAL_REQUIRE_PARTITION_NOWAIT=true` does not hide the mismatch. These differing regional NOWAIT results are consistent with the upstream report's partition scan-order condition.

A separate read of `pg_stat_activity` on every TServer found the running application pools only in their matching regions. At that instant: Asia TServers had 3/1 connections named `isureal-java-asia`; Europe 3/1 named `isureal-java-europe`; America 2/1 named `isureal-java-america`. Pool counts can change; the verified property is that no connection carrying another region's application name appeared on these nodes.

Local raw diagnostic logs for this run: `/tmp/isureal-jdbc-final-asia.log`, `/tmp/isureal-jdbc-final-europe.log`, `/tmp/isureal-jdbc-final-america.log`, `/tmp/isureal-jdbc-strict-asia.log`. These are temporary evidence paths on the development Mac, not repository deliverables. Rerun the commands above for reproducible evidence.
