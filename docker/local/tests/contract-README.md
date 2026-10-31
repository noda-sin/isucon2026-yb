# Go / Java HTTP contract comparison

This suite sends the same synthetic scenario to two independent local databases
through their three regional HTTP frontends. It never contacts non-loopback URLs
or follows redirects. It creates separate username namespaces for Go and Java.
No recovered data, official seed, or production credentials are used.

Prepare each database with `contract-admin.sql`. This creates the documented
synthetic administrator `contract_admin` / `local-contract-admin`; it does not
change an existing administrator. The fixture uses `INSERT ... SELECT ... WHERE
NOT EXISTS` so it also works when `administrators` is a compatibility view.

```sh
# Baseline only, or use --only java to diagnose Java independently:
python3 docker/local/tests/contract-java.py --only go --output /tmp/contract-go-baseline

# Full comparison (Go 18080–18082, Java 28080–28082):
python3 docker/local/tests/contract-java.py --output /tmp/contract-parity
```

`--go-base-port` and `--java-base-port` change the consecutive regional ports.
The script uses only Python's standard library and works with the Mac system
Python 3.9. `contract-fixture.jpg` is a generated 37 × 23 pixel gradient, encoded
with Go's standard JPEG encoder; it contains no user image.

The suite performs 216 observations spanning all 42 API routes. Every enabled
route has at least one successful path; disabled initialization intentionally
returns 503 and reversing certification intentionally returns 409. Protected
routes are also called without credentials. Additional cases cover validation,
missing objects, global duplicate usernames, region mismatch, owner permissions,
friend and friend-of-friend visibility, blocking, current posting/deletion quota,
pagination, strict JSON Content-Type handling, truncated JPEG rejection at five
truncation lengths across profile/post/RealMoji uploads, comments, replacement reactions, profile images, memories, admin
operations, session invalidation, and author region movement with existing posts,
images and sessions.

Image checks independently recompute the 1000-round HMAC-SHA256 signature from
the synthetic user's secret, verify the 24-hour expiry and canonical base64url,
and compare exact downloaded JPEG bytes. ZIP checks read the archive and compare
entry names, manifest references and exact image data. SSE connects before opening
a new window, checks its event's window ID, region and creation instant, and
ignores SSE comment lines. Deadline duration is checked independently of timestamp
normalization.

Comparison retains status codes, response field names, null versus omitted
fields, list order, booleans, numbers, errors, image hashes, ZIP structure and
selected response headers. UUIDs are assigned consistent aliases per run, so a
wrong existing author/reference ID is not normalized away. RFC3339 timestamps,
image expiry/signature values and the deliberately distinct username prefixes
are normalized. Headers compare media type (ignoring optional parameters), cache
control and content disposition; incidental HTTP date/server headers and ZIP
container encoding are not compared. This is behavioral parity on the exercised
scenario, not an exhaustive proof of all inputs or a performance test.

Artifacts in the requested output directory:

- `go.json` / `java.json`: normalized observations and per-route status coverage.
- `coverage.md`: route table including any `NOT RUN` routes after failure.
- `differences.json`: exact normalized observation differences after both full
  scenarios complete. A mismatch or incomplete scenario exits nonzero.

Run only on isolated synthetic development data. The suite advances posting
windows and retains generated test accounts/posts for inspection. Each new run
chooses a common date after the latest active window across both environments;
it does not invoke a database reset. Concurrent suites are unsupported.

## Verified baseline

Go completed all 216 observations and all 42 routes successfully on the local
YugabyteDB cluster. The matching route table is saved in
`contract-coverage-go.md`. Java parity has not been asserted by that baseline.
