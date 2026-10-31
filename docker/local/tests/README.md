# Local YugabyteDB integration checks

Build the repository-root Docker context with `docker/local/tests/Dockerfile` and
run it on the Compose network after successful initialization. The binary exits
nonzero on any failed check and uses only synthetic rows with random IDs.
It uses the same upstream pgx version as the initial application. The default
hosts are `tserver-{asia,europe,america}-1:5433`; `TEST_HOST_ASIA`,
`TEST_HOST_EUROPE`, and `TEST_HOST_AMERICA` can override the host names.

Checks:

- Six TServers reported by `yb_servers()`, two in each logical region.
- A second statement in the same Read Committed transaction sees another
  connection's committed update, independently through each regional endpoint.
  `SHOW transaction_isolation` alone cannot distinguish true RC from YB's older
  Snapshot fallback.
- `SELECT FOR UPDATE NOWAIT` rejects a conflicting lock (`55P03`), an ordinary
  writer waits, and resumes after the lock holder commits.
- Transaction advisory locks exclude a competing connection in another region
  and release on commit, matching the application's username registration lock.
- The global username unique index rejects duplicates across regions (`23505`).
- Invalid references and deletion of a referenced user fail (`23503`); rollback
  and commit of a user's region update preserve its session reference.
- The synthetic locality probe moves between actual region partitions and each
  partition points to its intended tablespace.

`verify-placement.sh` runs inside a YB container with the script mounted. It uses
`yb-admin list_tablets` and `list_tablet_servers`, joins peer UUIDs to
`yb_servers()` regions, and checks **every probe tablet** has exactly two local
voting replicas, one next-region replica, and a local leader. It waits up to 60
attempts for placement convergence. This is stronger than inspecting tablespace
JSON alone. Defaults assume the Compose master service names; override `MASTERS`
if needed. The script must be invoked using `bash`.

These tests do not assert full application geo-partitioning: application tables
remain globally placed with their original IDs and FK relationships (stage 1).
Synthetic probe relocation is not a test of migrating a user's entire graph.
The suite is not a benchmark or a production regional-failover test.

Sources:

- https://docs.yugabyte.com/stable/architecture/transactions/read-committed/
- https://docs.yugabyte.com/stable/api/ysql/exprs/func_yb_servers/
- https://docs.yugabyte.com/stable/admin/yb-admin/
