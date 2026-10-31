# Java port collaboration contract

JDK21 HttpServer + Jackson + HikariCP + YB smart JDBC42.7.3-yb-7. Package `isureal`.
Core provides static:
- `region` String, `mapper` ObjectMapper; `connect()` Connection using RC.
- `tx(Work<T>)` transaction lambda (Connection -> T, throws Exception), rollback on errors.
- `one(Connection,String,Object...)` -> Row or null, `all(...)` -> List<Row>, `exec(...)` -> int. SQL accepts numbered `$1` placeholders including repeats; JDBC binding converts them.
- Row extends LinkedHashMap<String,Object>, getters `str(key)`, `bool(key)`, `time(key)` -> Instant, `bytes(key)`, `number(key)` -> Number. SQL dates normalize to LocalDate, timestamps Instant.
- `map(Object... key,value)` linked map allows null values; `id()` UUID string; `sha256(String)` hex; `error(int,String)` RuntimeException.
- `user(Connection,id)` -> active Row/null, `userJson(Row)` -> full getMe response map, `relationshipUser(Row)` -> social-user response map, `signedImageURL(Row,String imageId)`.
- `blocked(Connection,String,String)` bool; `friendIds(Connection,String)` Set<String>.
- `route(method,pattern,Auth,Handler)`; Auth NONE/USER/REGION/ADMIN. Path syntax /api/posts/:postId. Handler.accept(Ctx) throws Exception.
- Ctx public fields userId, tokenHash, sessionRegion, exchange(HttpExchange), committed(boolean). Methods path(String),query(String) -> String (missing empty); body()->JsonNode; raw()->byte[]; header(String)->String; send(int,Object),empty(int),sendBytes(int,byte[],String contentType).

Core main installs Users.install(), Admin.install(), Social.install(), Posts.install(), Media.install(), then serves8080. APIs/errors/ordering/null/time/locks follow webapp/go exactly. SQL initial semantics are authoritative. No data or region hardcoding beyond topology.

Ownership: core/auth/users/admin agent Core.java Users.java Admin.java pom.xml Dockerfile. Social agent Social.java. Posts agent Posts.java. Media agent Media.java. Root owns compose integration and tests. Schema agent owns docker/local/geo/** and compose.geo.yaml. Do not edit other owners' files without coordination.
