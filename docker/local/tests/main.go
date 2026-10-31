// Integration checks against the local Compose cluster only. Every test uses
// synthetic, uniquely named rows and removes them when it finishes.
package main

import (
	"context"
	"errors"
	"fmt"
	"os"
	"strings"
	"time"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgconn"
)

var ctx context.Context
var regions = []string{"asia", "europe", "america"}

func must(err error) {
	if err != nil {
		panic(err)
	}
}
func require(ok bool, message string) {
	if !ok {
		panic(message)
	}
}
func connect(region string) *pgx.Conn {
	// Explicit endpoints use ordinary upstream pgx. No unsupported YB smart-driver
	// load_balance/topology_keys connection parameters are sent to PostgreSQL.
	host := os.Getenv("TEST_HOST_" + strings.ToUpper(region))
	if host == "" {
		host = "tserver-" + region + "-1"
	}
	config, err := pgx.ParseConfig("user=yugabyte dbname=isureal sslmode=disable connect_timeout=15")
	must(err)
	config.Host = host
	config.Port = 5433
	config.RuntimeParams["application_name"] = "local-yb-verification"
	config.RuntimeParams["statement_timeout"] = "20000"
	config.RuntimeParams["default_transaction_isolation"] = "read committed"
	c, err := pgx.ConnectConfig(ctx, config)
	must(err)
	return c
}
func exec(c *pgx.Conn, sql string, args ...any) { _, err := c.Exec(ctx, sql, args...); must(err) }
func scalar(c *pgx.Conn, sql string, args ...any) string {
	var s string
	must(c.QueryRow(ctx, sql, args...).Scan(&s))
	return s
}
func sqlstate(err error, code string) {
	var e *pgconn.PgError
	require(errors.As(err, &e) && e.Code == code, fmt.Sprintf("expected SQLSTATE %s, got %v", code, err))
}
func rollback(c *pgx.Conn)  { _, _ = c.Exec(context.Background(), "ROLLBACK") }
func closeConn(c *pgx.Conn) { _ = c.Close(context.Background()) }

func topology() {
	c := connect("asia")
	defer closeConn(c)
	rows, err := c.Query(ctx, "SELECT region, count(*) FROM yb_servers() GROUP BY region ORDER BY region")
	must(err)
	defer rows.Close()
	counts := map[string]int{}
	for rows.Next() {
		var region string
		var count int
		must(rows.Scan(&region, &count))
		counts[region] = count
	}
	must(rows.Err())
	require(len(counts) == 3, fmt.Sprintf("expected 3 regions, got %v", counts))
	for _, r := range regions {
		require(counts[r] == 2, fmt.Sprintf("expected 2 TServers in %s, got %d", r, counts[r]))
	}
	fmt.Printf("  topology: %v\n", counts)
}

func readCommitted(region string) {
	a, b := connect(region), connect(region)
	defer closeConn(a)
	defer closeConn(b)
	id := uuid.NewString()
	exec(a, "INSERT INTO locality_probe(region,id,payload) VALUES ($1,$2,'before')", region, id)
	defer func() {
		rollback(a)
		_, _ = a.Exec(context.Background(), "DELETE FROM locality_probe WHERE region=$1 AND id=$2", region, id)
	}()
	exec(a, "BEGIN ISOLATION LEVEL READ COMMITTED")
	require(scalar(a, "SHOW transaction_isolation") == "read committed", "transaction isolation mismatch")
	require(scalar(a, "SELECT payload FROM locality_probe WHERE region=$1 AND id=$2", region, id) == "before", "initial snapshot mismatch")
	exec(b, "UPDATE locality_probe SET payload='after' WHERE region=$1 AND id=$2", region, id)
	// Merely SHOWing read committed cannot detect fallback to Snapshot isolation.
	require(scalar(a, "SELECT payload FROM locality_probe WHERE region=$1 AND id=$2", region, id) == "after", "Read Committed is falling back to Snapshot: second statement did not see committed update")
	exec(a, "COMMIT")
}

func rowLocks(region string) {
	a, b := connect(region), connect(region)
	defer closeConn(a)
	defer closeConn(b)
	id := uuid.NewString()
	exec(a, "INSERT INTO locality_probe(region,id,payload) VALUES ($1,$2,'before')", region, id)
	defer func() {
		rollback(a)
		rollback(b)
		_, _ = a.Exec(context.Background(), "DELETE FROM locality_probe WHERE region=$1 AND id=$2", region, id)
	}()
	exec(a, "BEGIN ISOLATION LEVEL READ COMMITTED")
	require(scalar(a, "SELECT payload FROM locality_probe WHERE region=$1 AND id=$2 FOR UPDATE", region, id) == "before", "lock read mismatch")
	_, err := b.Exec(ctx, "SELECT payload FROM locality_probe WHERE region=$1 AND id=$2 FOR UPDATE NOWAIT", region, id)
	sqlstate(err, "55P03")
	done := make(chan error, 1)
	go func() {
		_, err := b.Exec(ctx, "UPDATE locality_probe SET payload='after-lock' WHERE region=$1 AND id=$2", region, id)
		done <- err
	}()
	select {
	case err := <-done:
		panic(fmt.Sprintf("UPDATE must wait for FOR UPDATE lock, returned early: %v", err))
	case <-time.After(500 * time.Millisecond):
	}
	exec(a, "COMMIT")
	select {
	case err := <-done:
		must(err)
	case <-time.After(25 * time.Second):
		panic("UPDATE did not resume after COMMIT")
	}
	require(scalar(a, "SELECT payload FROM locality_probe WHERE region=$1 AND id=$2", region, id) == "after-lock", "waiting writer did not commit")
}

func advisoryLocks() {
	a, b := connect("asia"), connect("europe")
	defer closeConn(a)
	defer closeConn(b)
	defer rollback(a)
	defer rollback(b)
	// Fixed transaction-level key is released even after a failed suite.
	exec(a, "BEGIN")
	exec(a, "SELECT pg_advisory_xact_lock(781436219)")
	var acquired bool
	must(b.QueryRow(ctx, "SELECT pg_try_advisory_xact_lock(781436219)").Scan(&acquired))
	require(!acquired, "cross-region advisory lock did not exclude competitor")
	exec(a, "COMMIT")
	must(b.QueryRow(ctx, "SELECT pg_try_advisory_xact_lock(781436219)").Scan(&acquired))
	require(acquired, "advisory lock not released at COMMIT")
}

func identityAndMove() {
	a, b := connect("asia"), connect("europe")
	defer closeConn(a)
	defer closeConn(b)
	userID, duplicateID, sessionID := uuid.NewString(), uuid.NewString(), uuid.NewString()
	username := "verify_" + strings.ReplaceAll(userID, "-", "")
	defer func() {
		rollback(a)
		rollback(b)
		_, _ = a.Exec(context.Background(), "DELETE FROM login_sessions WHERE id=$1", sessionID)
		_, _ = a.Exec(context.Background(), "DELETE FROM users WHERE id=$1 OR id=$2", userID, duplicateID)
	}()
	exec(a, "INSERT INTO users(id,username,secret_key_hash,display_name,region) VALUES($1,$2,'synthetic-not-a-login','Verification','asia')", userID, username)
	_, err := b.Exec(ctx, "INSERT INTO users(id,username,secret_key_hash,display_name,region) VALUES($1,$2,'synthetic-not-a-login','Duplicate','europe')", duplicateID, username)
	sqlstate(err, "23505")
	_, err = b.Exec(ctx, "INSERT INTO login_sessions(id,user_id,token_hash) VALUES($1,$2,'synthetic-not-a-token')", sessionID, duplicateID)
	sqlstate(err, "23503")
	exec(b, "INSERT INTO login_sessions(id,user_id,token_hash) VALUES($1,$2,'synthetic-not-a-token')", sessionID, userID)
	exec(a, "BEGIN")
	exec(a, "UPDATE users SET region='america' WHERE id=$1", userID)
	exec(a, "ROLLBACK")
	require(scalar(b, "SELECT region FROM users WHERE id=$1", userID) == "asia", "rolled-back region move was visible")
	exec(a, "UPDATE users SET region='europe' WHERE id=$1", userID)
	require(scalar(b, "SELECT u.region FROM users u JOIN login_sessions s ON s.user_id=u.id WHERE s.id=$1", sessionID) == "europe", "region move broke referencing session")
	_, err = a.Exec(ctx, "DELETE FROM users WHERE id=$1", userID)
	sqlstate(err, "23503")
	_, err = a.Exec(ctx, "INSERT INTO users(id,username,secret_key_hash,display_name,region) VALUES($1,$2,'synthetic-not-a-login','Duplicate','america')", duplicateID, username)
	sqlstate(err, "23505")
}

func partitionMove() {
	c := connect("asia")
	defer closeConn(c)
	id := uuid.NewString()
	defer func() { rollback(c); _, _ = c.Exec(context.Background(), "DELETE FROM locality_probe WHERE id=$1", id) }()
	exec(c, "INSERT INTO locality_probe(region,id,payload) VALUES('asia',$1,'synthetic')", id)
	for _, region := range regions {
		exec(c, "UPDATE locality_probe SET region=$1 WHERE id=$2", region, id)
		actual := scalar(c, "SELECT tableoid::regclass::text FROM locality_probe WHERE id=$1", id)
		require(actual == "locality_probe_"+region, fmt.Sprintf("region routing expected %s, got %s", region, actual))
		space := scalar(c, "SELECT t.spcname FROM pg_class c JOIN pg_tablespace t ON t.oid=c.reltablespace WHERE c.oid=$1::regclass", actual)
		require(space == "local_"+region, "partition tablespace mismatch: "+space)
	}
}

func main() {
	var cancel context.CancelFunc
	ctx, cancel = context.WithTimeout(context.Background(), 8*time.Minute)
	defer cancel()
	failures := 0
	run := func(name string, f func()) {
		func() {
			defer func() {
				if err := recover(); err != nil {
					fmt.Printf("FAIL %s: %v\n", name, err)
					failures++
				}
			}()
			f()
			fmt.Println("PASS " + name)
		}()
	}
	run("six TServers in three logical regions", topology)
	for _, region := range regions {
		r := region
		run(r+" actual Read Committed statement snapshot", func() { readCommitted(r) })
		run(r+" row lock exclusion and wakeup", func() { rowLocks(r) })
	}
	run("cross-region transaction advisory lock", advisoryLocks)
	run("global username uniqueness, FK, user region move", identityAndMove)
	run("probe partition routing, tablespaces, row movement", partitionMove)
	if failures > 0 {
		fmt.Printf("%d checks failed\n", failures)
		os.Exit(1)
	}
	fmt.Println("All local integration checks passed. Application data remains globally placed (stage 1); probe routing is not evidence of application geo-partitioning.")
}
