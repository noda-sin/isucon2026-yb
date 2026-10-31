package isureal

import (
	"net/http"
	"net/http/httptest"
	"testing"

	"github.com/jackc/pgx/v5"
	"github.com/labstack/echo/v5"
)

func TestLocalDSNUsesSupportedPGXParameters(t *testing.T) {
	c := &Config{DBHost: "tserver-asia-a", DBPort: "5433", DBUser: "local", DBPassword: "a@b:/?", DBName: "isureal", DBSSLMode: "disable"}
	parsed, err := pgx.ParseConfig(c.DatabaseDSN())
	if err != nil {
		t.Fatal(err)
	}
	if parsed.Host != c.DBHost || parsed.Password != c.DBPassword || parsed.Port != 5433 {
		t.Fatal("connection target or escaped password changed")
	}
	if parsed.RuntimeParams["default_transaction_isolation"] != "read committed" {
		t.Fatalf("read committed runtime parameter mismatch: %q", parsed.RuntimeParams["default_transaction_isolation"])
	}
	if _, ok := parsed.RuntimeParams["topology_keys"]; ok {
		t.Fatal("upstream pgx must not forward Yugabyte smart-driver options")
	}
}

func TestLocalInitializeGuard(t *testing.T) {
	e := echo.New()
	req := httptest.NewRequest(http.MethodPost, "/api/initialize", nil)
	rec := httptest.NewRecorder()
	err := initializeHandler(&HandlerContext{Config: &Config{DisableInitialize: true}})(e.NewContext(req, rec))
	apiErr, ok := err.(*APIError)
	if !ok || apiErr.Status != http.StatusServiceUnavailable || apiErr.Code != "local_initialize_disabled" {
		t.Fatalf("initialize must refuse before executing the reset script: %v", err)
	}
}
