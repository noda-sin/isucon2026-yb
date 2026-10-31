package isureal

import (
	"net"
	"net/url"
	"os"
	"strconv"
	"strings"
)

type Config struct {
	Region            string
	DBHost            string
	DBPort            string
	DBUser            string
	DBPassword        string
	DBName            string
	LogLevel          string
	DBSSLMode         string
	DBMaxOpenConns    int
	DisableInitialize bool
}

func ConfigFromEnv() *Config {
	maxConns, err := strconv.Atoi(envOrDefault("ISUREAL_DB_MAX_OPEN_CONNS", "30"))
	if err != nil || maxConns < 1 {
		panic("ISUREAL_DB_MAX_OPEN_CONNS must be a positive integer")
	}
	return &Config{
		Region:            envOrDefault("ISUREAL_REGION", "asia"),
		DBHost:            envOrDefault("ISUREAL_DB_HOST", "127.0.0.1"),
		DBPort:            envOrDefault("ISUREAL_DB_PORT", "5432"),
		DBUser:            envOrDefault("ISUREAL_DB_USER", "isureal"),
		DBPassword:        envOrDefault("ISUREAL_DB_PASSWORD", "isureal"),
		DBName:            envOrDefault("ISUREAL_DB_NAME", "isureal"),
		LogLevel:          envOrDefault("ISUREAL_LOG_LEVEL", "debug"),
		DBSSLMode:         envOrDefault("ISUREAL_DB_SSLMODE", "prefer"),
		DBMaxOpenConns:    maxConns,
		DisableInitialize: os.Getenv("ISUREAL_DISABLE_INITIALIZE") == "true",
	}
}

func (c *Config) DatabaseDSN() string {
	u := url.URL{
		Scheme: "postgres",
		User:   url.UserPassword(c.DBUser, c.DBPassword),
		Host:   net.JoinHostPort(c.DBHost, c.DBPort),
		Path:   "/" + c.DBName,
	}
	q := u.Query()
	if c.DBSSLMode != "" {
		q.Set("sslmode", c.DBSSLMode)
	}
	// pgx forwards this PostgreSQL runtime parameter on every new connection.
	// Yugabyte's yb_enable_read_committed_isolation server flag must also be true.
	q.Set("default_transaction_isolation", "read committed")
	q.Set("connect_timeout", "10")
	// PostgreSQL URI parsing treats '+' literally, unlike form query parsing.
	u.RawQuery = strings.ReplaceAll(q.Encode(), "+", "%20")
	return u.String()
}

func envOrDefault(key, defaultValue string) string {
	if value := os.Getenv(key); value != "" {
		return value
	}
	return defaultValue
}
