# Java implementation

JDK21, Jackson, HikariCP and YugabyteDB smart JDBC. This is a direct port of the preserved Go API implementation, kept alongside it for contract comparison. Build with `docker build -f webapp/java/Dockerfile -t isureal-local-java:dev .` at repository root.

The Java profile uses its own `isureal_java` database and regional storage; the Go baseline retains `isureal`. Startup and validation instructions are in the repository README and docker/local/VALIDATION-JAVA.md. The 42-route contract suite passes216 cases for both implementations.

Implementation-specific differences: SSE sends a comment heartbeat every15 seconds to detect disconnected clients; comments are not notification events. Malformed JSON/type binding errors return stable400 codes instead of Echo's parser wording. Internal SQL errors return `internal_error` instead of exposing database text. IP bans are checked on each request rather than a200ms cache. Local initialize remains503 until an explicit seed reset workflow is selected. JSON is the supported request format for JSON routes; Echo's incidental XML/form binder fallback is not included.

Transactions retry only SQLSTATE40001/40P01 after confirmed rollback, at most4 attempts. Connection-loss/uncertain commit errors are never automatically replayed. The smart driver uses the same-region pair, with strict topology-key fallback.
