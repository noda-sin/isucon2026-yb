package isureal;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.sun.net.httpserver.*;
import com.zaxxer.hikari.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.regex.*;
import java.io.*;

public final class Core {
    public static final String region = env("ISUREAL_REGION", "asia");
    public static final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule()).disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private static HikariDataSource pool;
    public static String env(String key, String fallback) { String v=System.getenv(key); return v==null||v.isEmpty()?fallback:v; }
    public static boolean validRegion(String value) { return Set.of("asia","europe","america").contains(value); }
    public static boolean validText(String value,int min,int max) {
        if(value==null)return false;
        for(int i=0;i<value.length();i++){char c=value.charAt(i); if(Character.isHighSurrogate(c)){if(i+1>=value.length()||!Character.isLowSurrogate(value.charAt(++i)))return false;}else if(Character.isLowSurrogate(c))return false;}
        int n=value.codePointCount(0,value.length());return n>=min&&n<=max;
    }
    public static String text(JsonNode body,String key){JsonNode v=body.get(key);if(v==null||v.isNull())return "";if(!v.isTextual())throw error(400,"invalid_json_type");return v.textValue();}
    public static boolean username(String s){return s!=null&&s.matches("[a-z0-9_.-]{1,64}");}
    public static String id(){return UUID.randomUUID().toString();}
    public static String sha256(String s){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
    public static LinkedHashMap<String,Object> map(Object... entries){var m=new LinkedHashMap<String,Object>();for(int i=0;i<entries.length;i+=2)m.put((String)entries[i],entries[i+1]);return m;}
    public static class ApiError extends RuntimeException {public final int status; public final String code; ApiError(int s,String c){super(c);status=s;code=c;}}
    public static ApiError error(int status,String code){return new ApiError(status,code);}
    public static class Row extends LinkedHashMap<String,Object> {
        public String str(String key){Object v=get(key);return v==null?"":v.toString();}
        public boolean bool(String key){Object v=get(key);return Boolean.TRUE.equals(v)||"t".equals(v)||"true".equals(v);}
        public Instant time(String key){Object v=get(key);return v==null?null:v instanceof Instant i?i:v instanceof OffsetDateTime d?d.toInstant():v instanceof LocalDate d?d.atStartOfDay(ZoneOffset.UTC).toInstant():Instant.parse(v.toString());}
        public byte[] bytes(String key){return (byte[])get(key);}
        public Number number(String key){return (Number)get(key);}
    }
    public static synchronized void initPool(){
        if(pool!=null)return;
        if(!validRegion(region))throw new IllegalArgumentException("invalid ISUREAL_REGION");
        var hc=new HikariConfig();hc.setDriverClassName("com.yugabyte.Driver");
        String hosts=env("ISUREAL_DB_HOSTS","tserver-"+region+"-1:5433,tserver-"+region+"-2:5433");
        hc.setJdbcUrl("jdbc:yugabytedb://"+hosts+"/"+env("ISUREAL_DB_NAME","isureal_java"));
        hc.setUsername(env("ISUREAL_DB_USER","yugabyte"));hc.setPassword(env("ISUREAL_DB_PASSWORD","yugabyte"));
        hc.addDataSourceProperty("ApplicationName","isureal-java-"+region);hc.addDataSourceProperty("load-balance","true");hc.addDataSourceProperty("topology-keys",env("ISUREAL_DB_TOPOLOGY_KEYS","local."+region+"."+region+":1"));
        hc.addDataSourceProperty("fallback-to-topology-keys-only","true");hc.addDataSourceProperty("yb-servers-refresh-interval","30");hc.addDataSourceProperty("connectTimeout","10");
        hc.addDataSourceProperty("sslmode",env("ISUREAL_DB_SSLMODE","disable"));
        hc.setMaximumPoolSize(Integer.parseInt(env("ISUREAL_DB_MAX_OPEN_CONNS","4")));hc.setMinimumIdle(1);hc.setConnectionTimeout(30000);hc.setTransactionIsolation("TRANSACTION_READ_COMMITTED");
        hc.setPoolName("isureal-"+region);pool=new HikariDataSource(hc);
    }
    public static Connection connect() throws SQLException {if(pool==null)initPool();Connection c=pool.getConnection();c.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);return c;}
    @FunctionalInterface public interface Work<T>{T apply(Connection c)throws Exception;}
    public static boolean retryable(SQLException e){return "40001".equals(e.getSQLState())||"40P01".equals(e.getSQLState());}
    private static void retryPause(int attempt)throws SQLException{try{Thread.sleep(java.util.concurrent.ThreadLocalRandom.current().nextLong(10L<<attempt,30L<<attempt));}catch(InterruptedException e){Thread.currentThread().interrupt();throw new SQLException("transaction retry interrupted","57014",e);}}
    // Retry only a transaction known to have aborted; never retry an ambiguous connection/commit failure.
    // Callbacks must contain database operations and in-memory result construction only.
    public static <T>T tx(Work<T> fn)throws Exception {
        for(int attempt=0;;attempt++){
            try(Connection c=connect()){
                c.setAutoCommit(false);
                try{T value=fn.apply(c);c.commit();return value;}
                catch(Exception|Error e){
                    try{c.rollback();}catch(SQLException rollbackError){e.addSuppressed(rollbackError);throw e;}
                    if(!(e instanceof SQLException se)||!retryable(se)||attempt>=3)throw e;
                }
            }
            retryPause(attempt);
        }
    }
    public record BoundSQL(String sql,List<Object> args){}
    public static BoundSQL bind(String sql,Object...args){
        StringBuilder out=new StringBuilder();List<Object> bindings=new ArrayList<>();boolean single=false,dbl=false;
        for(int i=0;i<sql.length();i++){char ch=sql.charAt(i);
            if(ch=='\''&&!dbl){out.append(ch);if(single&&i+1<sql.length()&&sql.charAt(i+1)=='\''){out.append(sql.charAt(++i));continue;}single=!single;continue;}
            if(ch=='"'&&!single){dbl=!dbl;out.append(ch);continue;}
            if(!single&&!dbl&&ch=='$'&&i+1<sql.length()&&Character.isDigit(sql.charAt(i+1))){int j=i+1;while(j<sql.length()&&Character.isDigit(sql.charAt(j)))j++;int index=Integer.parseInt(sql.substring(i+1,j));if(index<1||index>args.length)throw new IllegalArgumentException("SQL bind count");bindings.add(args[index-1]);out.append('?');i=j-1;}else out.append(ch);
        }return new BoundSQL(out.toString(),bindings);
    }
    private static PreparedStatement prepare(Connection c,String sql,Object...args)throws SQLException{
        BoundSQL b=bind(sql,args);PreparedStatement p=c.prepareStatement(b.sql());
        try{for(int i=0;i<b.args().size();i++){Object v=b.args().get(i);if(v instanceof Instant t)p.setObject(i+1,t.atOffset(ZoneOffset.UTC));else if(v instanceof Set<?> s)p.setArray(i+1,c.createArrayOf("text",s.toArray()));else if(v instanceof List<?> l)p.setArray(i+1,c.createArrayOf("text",l.toArray()));else if(v instanceof String[] a)p.setArray(i+1,c.createArrayOf("text",a));else p.setObject(i+1,v);}return p;}catch(SQLException|RuntimeException e){p.close();throw e;}
    }
    public static List<Row> all(Connection c,String sql,Object...args)throws SQLException{try(PreparedStatement p=prepare(c,sql,args);ResultSet rs=p.executeQuery()){var list=new ArrayList<Row>();var meta=rs.getMetaData();while(rs.next()){Row row=new Row();for(int i=1;i<=meta.getColumnCount();i++){Object value=rs.getObject(i);if(value instanceof java.sql.Timestamp t)value=t.toInstant();else if(value instanceof java.sql.Date d)value=d.toLocalDate();else if(value instanceof OffsetDateTime t)value=t.toInstant();row.put(meta.getColumnLabel(i),value);}list.add(row);}return list;}}
    public static Row one(Connection c,String sql,Object...args)throws SQLException{List<Row> rows=all(c,sql,args);return rows.isEmpty()?null:rows.getFirst();}
    public static int exec(Connection c,String sql,Object...args)throws SQLException{
        for(int attempt=0;;attempt++){
            try(PreparedStatement p=prepare(c,sql,args)){return p.executeUpdate();}
            catch(SQLException e){if(!c.getAutoCommit()||!retryable(e)||attempt>=3)throw e;retryPause(attempt);}
        }
    }
    public static Row user(Connection c,String id)throws SQLException{return one(c,"SELECT id,username,secret_key_hash,display_name,bio,profile_image_id,region,account_type,status FROM users WHERE id=$1 AND status='active'",id);}
    public static Map<String,Object> sessionUser(Row u){return map("id",u.str("id"),"username",u.str("username"),"displayName",u.str("display_name"),"bio",u.str("bio"),"region",u.str("region"),"accountType",u.str("account_type"),"status",u.str("status"));}
    public static Map<String,Object> userJson(Row u){var m=sessionUser(u);m.put("profileImageUrl",u.get("profile_image_id")==null?null:signedImageURL(u,u.str("profile_image_id")));return m;}
    public static Map<String,Object> relationshipUser(Row u){return map("id",u.str("id"),"username",u.str("username"),"displayName",u.str("display_name"),"profileImageUrl",u.get("profile_image_id")==null?null:signedImageURL(u,u.str("profile_image_id")),"bio",u.str("bio"),"accountType",u.str("account_type"));}
    public static byte[] signature(Row user,String image,long exp){try{byte[] key=HexFormat.of().parseHex(user.str("secret_key_hash"));if(key.length!=32)throw new IllegalArgumentException("invalid image signing key");Mac mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(key,"HmacSHA256"));byte[] value=(exp+":"+image).getBytes(StandardCharsets.UTF_8);for(int i=0;i<1000;i++)value=mac.doFinal(value);return value;}catch(Exception e){throw new IllegalStateException(e);}}
    public static String signedImageURL(Row user,String image){long exp=Instant.now().plusSeconds(86400).getEpochSecond();return "/api/images/"+URLEncoder.encode(image,StandardCharsets.UTF_8).replace("+","%20")+"?exp="+exp+"&sig="+Base64.getUrlEncoder().withoutPadding().encodeToString(signature(user,image,exp));}
    public static boolean blocked(Connection c,String a,String b)throws SQLException{return one(c,"SELECT EXISTS(SELECT 1 FROM block_relations WHERE (blocker_id=$1 AND blocked_id=$2) OR (blocker_id=$2 AND blocked_id=$1)) AS found",a,b).bool("found");}
    public static Set<String> friendIds(Connection c,String id)throws SQLException{Set<String> set=new HashSet<>();for(Row r:all(c,"SELECT user_low_id,user_high_id FROM friendships WHERE user_low_id=$1 OR user_high_id=$1",id))set.add(r.str("user_low_id").equals(id)?r.str("user_high_id"):r.str("user_low_id"));return set;}

    public enum Auth{NONE,USER,REGION,ADMIN}
    @FunctionalInterface public interface Handler{void accept(Ctx c)throws Exception;}
    record Route(String method,String pattern,Auth auth,Handler handler){}
    private static final List<Route> routes=new ArrayList<>();
    public static void route(String method,String pattern,Auth auth,Handler handler){routes.add(new Route(method,pattern,auth,handler));}
    public static class Ctx{
        public final HttpExchange exchange;public boolean committed;public String userId,tokenHash,sessionRegion,administratorId,abuse,username;public int status=200;
        private final Map<String,String> paths=new HashMap<>(),queries=new HashMap<>();private byte[] raw;private JsonNode body;
        private boolean queryParsed;
        Ctx(HttpExchange x){exchange=x;}
        public String path(String key){return paths.getOrDefault(key,"");}
        public String query(String key){if(!queryParsed){String query=exchange.getRequestURI().getRawQuery();if(query!=null)for(String pair:query.split("&")){String[] p=pair.split("=",2);queries.putIfAbsent(decode(p[0]),p.length==1?"":decode(p[1]));}queryParsed=true;}return queries.getOrDefault(key,"");}public String header(String key){String v=exchange.getRequestHeaders().getFirst(key);return v==null?"":v;}
        public String ip(){String v=header("X-Client-IP");return v.isEmpty()?exchange.getRemoteAddress().getAddress().getHostAddress():v;}
        public byte[] raw()throws IOException{if(raw==null)raw=exchange.getRequestBody().readAllBytes();return raw;}
        public JsonNode body()throws IOException{if(body==null)body=jsonBody(raw(),header("Content-Type"),header("Transfer-Encoding").isEmpty());return body;}
        public void send(int code,Object value)throws IOException{sendBytes(code,mapper.writeValueAsBytes(value),"application/json; charset=UTF-8");}
        public void empty(int code)throws IOException{status=code;committed=true;exchange.sendResponseHeaders(code,-1);exchange.close();}
        public void sendBytes(int code,byte[] value,String contentType)throws IOException{status=code;exchange.getResponseHeaders().set("Content-Type",contentType);committed=true;exchange.sendResponseHeaders(code,value.length);try(OutputStream out=exchange.getResponseBody()){out.write(value);}exchange.close();}
    }
    static JsonNode jsonBody(byte[] bytes,String contentType,boolean knownLength)throws IOException{
        // Echo skips binding a zero Content-Length even when the media type is absent.
        if(bytes.length==0&&knownLength)return mapper.createObjectNode();
        String mime=contentType.split(";",2)[0].trim();
        if(!mime.equals("application/json"))throw error(415,"code=415, message=Unsupported Media Type");
        try{JsonNode result=mapper.readTree(bytes);if(result==null)throw error(400,"invalid_json");if(result.isNull())return mapper.createObjectNode();if(!result.isObject())throw error(400,"invalid_json_type");return result;}
        catch(com.fasterxml.jackson.core.JsonProcessingException e){throw error(400,"invalid_json");}
    }
    static String decode(String s){try{return URLDecoder.decode(s,StandardCharsets.UTF_8);}catch(IllegalArgumentException e){throw error(400,"invalid_url");}}
    private static boolean match(Route r,Ctx c){String[] pattern=r.pattern().split("/",-1),path=c.exchange.getRequestURI().getRawPath().split("/",-1);if(pattern.length!=path.length)return false;for(int i=0;i<pattern.length;i++){if(pattern[i].startsWith(":"))c.paths.put(pattern[i].substring(1),decode(path[i]));else if(!pattern[i].equals(path[i]))return false;}return true;}
    private static void checkIPBan(Ctx ctx)throws Exception{
        try(Connection c=connect()){if(one(c,"SELECT banned_until FROM ip_bans WHERE ip=$1 AND banned_until>clock_timestamp()",ctx.ip())!=null)throw error(403,"ip_banned");}
    }
    private static void authorize(Ctx ctx,Auth auth)throws Exception{
        if(auth==Auth.NONE)return;
        try(Connection c=connect()){
            String code=auth==Auth.ADMIN?"invalid_administrator_session":"invalid_session";
            String h=ctx.header("Authorization");if(!h.startsWith("Bearer "))throw error(401,code);ctx.tokenHash=sha256(h.substring(7));
            if(auth==Auth.ADMIN){Row s=one(c,"SELECT administrator_id FROM administrator_sessions WHERE token_hash=$1 AND active=true",ctx.tokenHash);if(s==null)throw error(401,code);ctx.administratorId=s.str("administrator_id");return;}
            Row s=one(c,"SELECT s.user_id,u.region FROM login_sessions s JOIN users u ON u.id=s.user_id WHERE s.token_hash=$1 AND s.active=true AND u.status='active'",ctx.tokenHash);if(s==null)throw error(401,code);ctx.userId=s.str("user_id");ctx.sessionRegion=s.str("region");if(auth==Auth.REGION&&!region.equals(ctx.sessionRegion))throw error(403,"region_mismatch");
        }
    }
    private static final Map<String,String> actions=new HashMap<>();
    private static void dispatch(HttpExchange exchange)throws IOException{
        Ctx c=null;Route found=null;
        try{c=new Ctx(exchange);checkIPBan(c);Set<String> allowed=new TreeSet<>();for(Route r:routes){c.paths.clear();if(match(r,c)){allowed.add(r.method());if(r.method().equals(exchange.getRequestMethod())){found=r;break;}}}if(found==null){if(!allowed.isEmpty()){exchange.getResponseHeaders().set("Allow",String.join(", ",allowed));throw error(405,"Method Not Allowed");}throw error(404,"Not Found");}authorize(c,found.auth());found.handler().accept(c);if(!c.committed)c.empty(204);}
        catch(Exception e){if(c==null)c=new Ctx(exchange);if(!c.committed){if(e instanceof ApiError ae)c.send(ae.status,map("error",ae.code));else{System.err.println("request failed: "+e.getClass().getSimpleName()+(e instanceof SQLException se?" SQLSTATE="+se.getSQLState():""));c.send(500,map("error","internal_error"));}}}
        finally{if(c!=null)log(c,found);exchange.close();}
    }
    private static void log(Ctx c,Route route){String level=c.status>=500?"error":c.exchange.getRequestMethod().equals("GET")&&!(c.status==403&&"invalid_signature".equals(c.abuse))?"debug":"info";Map<String,Integer> ranks=Map.of("debug",0,"info",1,"error",2);if(ranks.get(level)<ranks.getOrDefault(env("ISUREAL_LOG_LEVEL","info"),0))return;String action=route==null?null:actions.get(route.method()+" "+route.pattern());var m=map("level",level,"action",action,"status",c.status,"ip",c.ip(),"user_id",c.userId);if("register".equals(action)||"login".equals(action))m.put("username",username(c.username)?c.username:null);if(c.abuse!=null&&!c.abuse.isEmpty())m.put("abuse",c.abuse);try{System.out.println(mapper.writeValueAsString(m));}catch(Exception ignored){}}
    public static void main(String[] args)throws Exception{
        initPool();Users.install();Admin.install();Social.install();Posts.install();Media.install();
        route("GET","/healthz",Auth.NONE,c->{try(Connection db=connect()){one(db,"SELECT 1");c.empty(200);}});
        route("POST","/api/initialize",Auth.NONE,c->{throw error(503,"local_initialize_disabled");});
        loadActions();HttpServer server=HttpServer.create(new InetSocketAddress(8080),0);server.createContext("/",Core::dispatch);server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());Runtime.getRuntime().addShutdownHook(new Thread(()->{server.stop(1);pool.close();}));server.start();
    }
    static void loadActions(){
        actions.put("POST /api/initialize","initialize");
        actions.put("POST /api/auth/register","register");
        actions.put("POST /api/auth/login","login");
        actions.put("POST /api/auth/logout","logout");
        actions.put("GET /api/users","search_users");
        actions.put("GET /api/users/:userId","get_user");
        actions.put("GET /api/me/export","export_posts");
        actions.put("GET /api/me","get_me");
        actions.put("PATCH /api/me","update_me");
        actions.put("PUT /api/me/profile-image","put_profile_image");
        actions.put("DELETE /api/me/profile-image","delete_profile_image");
        actions.put("POST /api/me/account-deletion","delete_account");
        actions.put("POST /api/posts","create_post");
        actions.put("GET /api/posting-window/current","get_current_posting_window");
        actions.put("GET /api/posts/me/current","get_my_current_post");
        actions.put("GET /api/posts/:postId","get_post");
        actions.put("PATCH /api/posts/:postId","update_post");
        actions.put("DELETE /api/posts/:postId","delete_post");
        actions.put("GET /api/feeds/:feed","get_feed");
        actions.put("GET /api/memories","get_memories");
        actions.put("GET /api/memories/:date","get_memories_by_date");
        actions.put("POST /api/posts/:postId/comments","create_comment");
        actions.put("GET /api/posts/:postId/comments","get_comments");
        actions.put("DELETE /api/comments/:commentId","delete_comment");
        actions.put("POST /api/posts/:postId/realmojis","replace_real_moji");
        actions.put("GET /api/posts/:postId/realmojis","get_real_mojis");
        actions.put("DELETE /api/realmojis/:realMojiId","delete_real_moji");
        actions.put("GET /api/images/:imageId","get_image");
        actions.put("GET /api/notifications","get_notifications");
        actions.put("POST /api/friend-requests","create_friend_request");
        actions.put("POST /api/friend-requests/:requestId/:action","transition_friend_request");
        actions.put("GET /api/relationships/:kind","get_relationships");
        actions.put("DELETE /api/friendships/:userId","delete_friendship");
        actions.put("PUT /api/follows/:userId","follow");
        actions.put("DELETE /api/follows/:userId","unfollow");
        actions.put("PUT /api/blocks/:userId","block");
        actions.put("DELETE /api/blocks/:userId","unblock");
        actions.put("POST /api/admin/auth/login","admin_login");
        actions.put("POST /api/admin/auth/logout","admin_logout");
        actions.put("PUT /api/admin/users/:userId/certification","certify_user");
        actions.put("DELETE /api/admin/users/:userId/certification","delete_user_certification");
        actions.put("POST /api/admin/posting-window","open_posting_window");
    }
}
