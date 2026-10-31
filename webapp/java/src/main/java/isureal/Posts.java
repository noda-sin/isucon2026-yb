package isureal;

import com.fasterxml.jackson.databind.JsonNode;
import java.sql.Connection;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.time.*;
import java.time.format.DateTimeParseException;
import java.util.*;
import static isureal.Core.*;

/** Initial post/feed/memories semantics, with JDBC transactions and shared authorization. */
public final class Posts {
    private Posts() {}
    public static final String COLUMNS = "p.id,p.author_id,p.posting_window_id,p.image_id,p.caption,p.visibility," +
        "p.location_kind,p.latitude,p.longitude,p.locality,p.posted_at,w.on_time_deadline,w.ended_at AS window_ended_at";
    private static final String[] USER_COLUMNS = {"id","username","secret_key_hash","display_name","bio","profile_image_id","region","account_type","status"};
    public static void install() {
        route("POST", "/api/posts", Auth.REGION, Posts::create);
        route("GET", "/api/posting-window/current", Auth.REGION, Posts::currentWindow);
        route("GET", "/api/posts/me/current", Auth.REGION, Posts::currentPost);
        route("GET", "/api/posts/:postId", Auth.REGION, Posts::get);
        route("PATCH", "/api/posts/:postId", Auth.REGION, Posts::update);
        route("DELETE", "/api/posts/:postId", Auth.REGION, Posts::delete);
        route("GET", "/api/feeds/:feed", Auth.REGION, Posts::feed);
        route("GET", "/api/memories", Auth.REGION, c -> memories(c,false));
        route("GET", "/api/memories/:date", Auth.REGION, c -> memories(c,true));
    }
    public static Row getPost(Connection db, String postId) throws Exception {
        return one(db,"SELECT " + COLUMNS + " FROM posts p JOIN posting_windows w ON w.id=p.posting_window_id WHERE p.id=$1 AND p.deleted_at IS NULL",postId);
    }
    public static List<Row> selectPosts(Connection db,String conditions,Object...args) throws Exception {
        StringJoiner columns=new StringJoiner(",");
        for(String col:USER_COLUMNS) columns.add("u."+col+" AS \"author."+col+"\"");
        return all(db,"SELECT "+COLUMNS+","+columns+" FROM posts p JOIN posting_windows w ON w.id=p.posting_window_id JOIN users u ON u.id=p.author_id AND u.status='active' WHERE p.deleted_at IS NULL "+conditions,args);
    }
    public static Row getPostWithAuthor(Connection db,String postId) throws Exception {
        List<Row> rows=selectPosts(db,"AND p.id=$1",postId);return rows.isEmpty()?null:rows.get(0);
    }
    public static Row author(Row post) {
        Row result=new Row();for(String key:USER_COLUMNS) result.put(key,post.get("author."+key));return result;
    }
    public static final class Viewer {
        public Row user;
        public String userId;
        public boolean hasPostedToday;
        public final Set<String> friends=new HashSet<>(),friendsOfFriends=new HashSet<>(),blocked=new HashSet<>();
        public final Map<String,Integer> mutualFriends=new HashMap<>();
    }
    public static Viewer viewer(Connection db,String userId) throws Exception {
        Row u=user(db,userId);if(u==null)return null;
        Viewer v=new Viewer();v.user=u;v.userId=userId;
        v.hasPostedToday=one(db,"SELECT EXISTS(SELECT 1 FROM posts p JOIN posting_windows posted ON posted.id=p.posting_window_id JOIN posting_windows current ON current.region=$2 AND current.ended_at IS NULL AND current.local_date=posted.local_date WHERE p.author_id=$1 AND p.deleted_at IS NULL) AS present",userId,u.str("region")).bool("present");
        for(Row r:all(db,"SELECT CASE WHEN blocker_id=$1 THEN blocked_id ELSE blocker_id END AS id FROM block_relations WHERE blocker_id=$1 OR blocked_id=$1",userId))v.blocked.add(r.str("id"));
        v.friends.addAll(friendIds(db,userId));if(v.friends.isEmpty())return v;
        for(Row r:all(db,"SELECT user_low_id,user_high_id FROM friendships WHERE user_low_id=ANY($1::text[]) OR user_high_id=ANY($1::text[])",v.friends)) {
            String low=r.str("user_low_id"),high=r.str("user_high_id");
            if(v.friends.contains(low)){v.friendsOfFriends.add(high);if(!high.equals(userId))v.mutualFriends.merge(high,1,Integer::sum);}
            if(v.friends.contains(high)){v.friendsOfFriends.add(low);if(!low.equals(userId))v.mutualFriends.merge(low,1,Integer::sum);}
        }return v;
    }
    public static boolean canView(Viewer v,Row post) {
        if(v==null||post==null)return false;String authorId=post.str("author_id");
        if(authorId.equals(v.userId))return true;
        if(post.get("window_ended_at")!=null||!v.hasPostedToday||v.blocked.contains(authorId))return false;
        return switch(post.str("visibility")) {
            case "public" -> true;
            case "friends" -> v.friends.contains(authorId);
            case "friends_of_friends" -> v.friends.contains(authorId)||v.friendsOfFriends.contains(authorId);
            default -> false;
        };
    }
    public static boolean canViewPost(Connection db,String userId,String postId) throws Exception {
        Row p=getPostWithAuthor(db,postId);return p!=null&&canView(viewer(db,userId),p);
    }
    public static Map<String,Object> response(Row post,Row author) throws Exception {
        Map<String,Object> location=map("kind",post.str("location_kind"));
        for(String field:List.of("latitude","longitude","locality"))if(post.get(field)!=null)location.put(field,post.get(field));
        Instant postedAt=post.time("posted_at"),deadline=post.time("on_time_deadline");boolean onTime=!postedAt.isAfter(deadline);
        return map("id",post.str("id"),"author",relationshipUser(author),"postingWindowId",post.str("posting_window_id"),
            "imageUrl",signedImageURL(author,post.str("image_id")),"caption",post.str("caption"),"visibility",post.str("visibility"),
            "location",location,"postedAt",postedAt,"onTime",onTime,"lateSeconds",onTime?0L:Duration.between(deadline,postedAt).getSeconds());
    }
    public static Map<String,Object> response(Connection db,Row post,Viewer ignored) throws Exception {
        return response(post,post.containsKey("author.id")?author(post):user(db,post.str("author_id")));
    }
    private static boolean validText(String value,int min,int max){return Core.validText(value,min,max);}
    private static boolean validVisibility(String value){return Set.of("friends","friends_of_friends","public").contains(value);}
    private static Instant before(Ctx c){if(c.query("beforePostedAt").isEmpty())return null;try{return Instant.parse(c.query("beforePostedAt"));}catch(DateTimeParseException ex){throw error(400,"invalid_pagination");}}
    private static void pagination(Instant before,String id){if((before==null)!=id.isEmpty())throw error(400,"invalid_pagination");}
    private static void get(Ctx c) throws Exception {
        try(Connection db=connect()){
            Row p=getPostWithAuthor(db,c.path("postId"));if(p==null||!canView(viewer(db,c.userId),p))throw error(404,"post_not_found");c.send(200,response(p,author(p)));
        }
    }
    private static void currentPost(Ctx c) throws Exception {
        try(Connection db=connect()){
            Row r=one(db,"SELECT p.id FROM posts p JOIN posting_windows w ON w.id=p.posting_window_id JOIN users u ON u.id=p.author_id JOIN posting_windows current ON current.region=u.region AND current.ended_at IS NULL AND current.local_date=w.local_date WHERE p.author_id=$1 AND p.deleted_at IS NULL",c.userId);
            Row p=r==null?null:getPost(db,r.str("id"));if(p==null)throw error(404,"post_not_found");c.send(200,response(p,user(db,c.userId)));
        }
    }
    private static void currentWindow(Ctx c) throws Exception {
        try(Connection db=connect()){
            Row w=one(db,"SELECT w.id,w.region,w.local_date,w.starts_at,w.on_time_deadline,w.ended_at,count(p.id) FILTER (WHERE p.deleted_at IS NULL) AS active,count(p.id) FILTER (WHERE p.deleted_at IS NOT NULL) AS deleted FROM users u JOIN posting_windows w ON w.region=u.region AND w.ended_at IS NULL LEFT JOIN posting_windows posted_window ON posted_window.local_date=w.local_date LEFT JOIN posts p ON p.author_id=u.id AND p.posting_window_id=posted_window.id WHERE u.id=$1 GROUP BY w.id,w.region,w.local_date,w.starts_at,w.on_time_deadline,w.ended_at",c.userId);
            if(w==null){c.send(200,map("window",null,"hasPosted",false,"canPost",false,"deletionCount",0));return;}
            int active=w.number("active").intValue(),deleted=w.number("deleted").intValue();
            c.send(200,map("window",map("id",w.str("id"),"region",w.str("region"),"localDate",w.get("local_date").toString(),"startsAt",w.time("starts_at"),"onTimeDeadline",w.time("on_time_deadline")),"hasPosted",active>0,"canPost",active==0&&deleted<2&&!Instant.now().isBefore(w.time("starts_at")),"deletionCount",deleted));
        }
    }
    private static void update(Ctx c) throws Exception {
        JsonNode body=c.body(),cn=body.get("caption"),vn=body.get("visibility");
        String caption=cn==null||cn.isNull()?null:cn.asText(),visibility=vn==null||vn.isNull()?null:vn.asText();
        if(cn!=null&&!cn.isNull()&&!cn.isTextual()||vn!=null&&!vn.isNull()&&!vn.isTextual())throw error(400,"invalid_post");
        if(caption==null&&visibility==null||caption!=null&&!validText(caption,0,1000)||visibility!=null&&!validVisibility(visibility))throw error(400,"invalid_post");
        Map<String,Object> result=tx(db->{
            one(db,"SELECT id FROM users WHERE id=$1 FOR NO KEY UPDATE",c.userId);
            Row p=getPost(db,c.path("postId"));if(p==null)throw error(404,"post_not_found");
            if(!c.userId.equals(p.str("author_id")))throw error(403,"post_not_editable");
            Row w=one(db,"SELECT ended_at FROM posting_windows WHERE id=$1",p.str("posting_window_id"));if(w.get("ended_at")!=null)throw error(403,"post_not_editable");
            if(caption!=null)exec(db,"UPDATE posts SET caption=$2 WHERE id=$1",p.str("id"),caption);
            if(visibility!=null)exec(db,"UPDATE posts SET visibility=$2 WHERE id=$1",p.str("id"),visibility);
            return response(getPost(db,p.str("id")),user(db,c.userId));
        });c.send(200,result);
    }
    private static void delete(Ctx c) throws Exception {
        tx(db->{
            one(db,"SELECT id FROM users WHERE id=$1 FOR NO KEY UPDATE",c.userId);
            Row p=getPost(db,c.path("postId"));if(p==null)throw error(404,"post_not_found");
            if(!c.userId.equals(p.str("author_id")))throw error(403,"post_not_editable");
            Row w=one(db,"SELECT local_date FROM posting_windows WHERE id=$1",p.str("posting_window_id"));
            Row count=one(db,"SELECT count(*) AS n FROM posts p JOIN posting_windows w ON w.id=p.posting_window_id WHERE p.author_id=$1 AND w.local_date=$2 AND p.deleted_at IS NOT NULL",c.userId,w.get("local_date"));
            if(count.number("n").longValue()>=2)throw error(409,"post_slot_unavailable");exec(db,"UPDATE posts SET deleted_at=clock_timestamp() WHERE id=$1",p.str("id"));return null;
        });c.empty(204);
    }
    private static void feed(Ctx c) throws Exception {
        String kind=c.path("feed");if(!Set.of("friends","friends-of-friends","public","following").contains(kind))throw error(400,"invalid_post");
        Instant beforeAt=before(c);String beforeId=c.query("beforeId");pagination(beforeAt,beforeId);
        boolean ranked=kind.equals("friends-of-friends");Long beforeScore=null;
        if(!c.query("beforeScore").isEmpty())try{beforeScore=Long.valueOf(c.query("beforeScore"));}catch(NumberFormatException ex){throw error(400,"invalid_pagination");}
        if(ranked&&(beforeScore==null)!=beforeId.isEmpty())throw error(400,"invalid_pagination");
        try(Connection db=connect()){
            Viewer v=viewer(db,c.userId);if(v==null){c.send(200,map("items",List.of(),"hasMore",false));return;}
            Set<String> following=new HashSet<>();if(kind.equals("following"))for(Row r:all(db,"SELECT followee_id FROM follows WHERE follower_id=$1",c.userId))following.add(r.str("followee_id"));
            List<Row> candidates=new ArrayList<>();
            for(Row p:selectPosts(db,"AND p.author_id<>$1 AND w.ended_at IS NULL AND ($2::timestamptz IS NULL OR (p.posted_at,p.id)<($2,$3)) ORDER BY p.posted_at DESC,p.id DESC",c.userId,ranked?null:beforeAt,ranked?"":beforeId)){
                String author=p.str("author_id");boolean matches=switch(kind){case "public"->"public".equals(p.str("visibility"));case "following"->following.contains(author);case "friends"->v.friends.contains(author);default->!v.friends.contains(author)&&v.friendsOfFriends.contains(author);};
                if(canView(v,p)&&matches)candidates.add(p);
            }
            Map<String,Long> scores=ranked?scores(db,v,candidates):Map.of();if(ranked)candidates.sort((a,b)->Long.compare(scores.get(b.str("id")),scores.get(a.str("id"))));
            List<Map<String,Object>> items=new ArrayList<>();
            for(Row p:candidates){
                long score=scores.getOrDefault(p.str("id"),0L);
                if(ranked&&beforeScore!=null){boolean comesBefore=score!=beforeScore?score<beforeScore:!p.time("posted_at").equals(beforeAt)?p.time("posted_at").isBefore(beforeAt):p.str("id").compareTo(beforeId)<0;if(!comesBefore)continue;}
                Map<String,Object> item=response(p,author(p));if(ranked)item.put("score",score);items.add(item);if(items.size()==51)break;
            }
            c.send(200,map("items",items.size()>50?new ArrayList<>(items.subList(0,50)):items,"hasMore",items.size()>50));
        }
    }
    private static Map<String,Long> scores(Connection db,Viewer v,List<Row> posts) throws Exception {
        Map<String,Long> scores=new HashMap<>(),connections=new HashMap<>(),engagements=new HashMap<>();if(posts.isEmpty())return scores;
        Set<String> authors=new HashSet<>(),ids=new HashSet<>();for(Row p:posts){authors.add(p.str("author_id"));ids.add(p.str("id"));}
        for(Row pair:all(db,"SELECT user_low_id,user_high_id FROM friendships WHERE user_low_id=ANY($1::text[]) OR user_high_id=ANY($1::text[])",authors)){
            String low=pair.str("user_low_id"),high=pair.str("user_high_id");if(!authors.contains(low)&&!authors.contains(high))continue;
            connections.merge(low,(long)v.mutualFriends.getOrDefault(high,0),Long::sum);connections.merge(high,(long)v.mutualFriends.getOrDefault(low,0),Long::sum);
        }
        for(Row e:all(db,"SELECT r.post_id,r.reactor_id AS user_id FROM realmojis r JOIN users u ON u.id=r.reactor_id AND u.status='active' WHERE r.post_id=ANY($1::text[]) AND r.replaced_at IS NULL AND r.deleted_at IS NULL UNION SELECT c.post_id,c.author_id AS user_id FROM comments c JOIN users u ON u.id=c.author_id AND u.status='active' WHERE c.post_id=ANY($1::text[]) AND c.deleted_at IS NULL",ids))
            if(ids.contains(e.str("post_id"))&&v.friends.contains(e.str("user_id")))engagements.merge(e.str("post_id"),1L,Long::sum);
        for(Row p:posts)scores.put(p.str("id"),10*engagements.getOrDefault(p.str("id"),0L)+3L*v.mutualFriends.getOrDefault(p.str("author_id"),0)+Math.min(connections.getOrDefault(p.str("author_id"),0L),30L));return scores;
    }
    private static void memories(Ctx c,boolean date) throws Exception {
        LocalDate start,end;
        try{
            if(date){if(!c.path("date").matches("\\d{4}-\\d{2}-\\d{2}"))throw new IllegalArgumentException();start=LocalDate.parse(c.path("date"));end=start.plusDays(1);}
            else{if(!c.query("month").matches("\\d{4}-\\d{2}"))throw new IllegalArgumentException();start=YearMonth.parse(c.query("month")).atDay(1);end=start.plusMonths(1);}
        }catch(Exception ex){throw error(400,"invalid_memory_period");}
        Instant beforeAt=before(c);String beforeId=c.query("beforeId");pagination(beforeAt,beforeId);
        try(Connection db=connect()){
            List<Row> posts=selectPosts(db,"AND p.author_id=$1 AND w.local_date>=$2 AND w.local_date<$3 AND ($4::timestamptz IS NULL OR (p.posted_at,p.id)<($4,$5)) ORDER BY p.posted_at DESC,p.id DESC LIMIT 51",c.userId,start,end,beforeAt,beforeId);
            boolean hasMore=posts.size()>50;List<Map<String,Object>> items=new ArrayList<>();
            for(Row p:posts.subList(0,Math.min(posts.size(),50))){Map<String,Object> item=response(p,author(p));item.put("realmojis",Media.memoryRealmojis(db,p.str("id")));item.put("comments",Media.memoryComments(db,p.str("id")));items.add(item);}
            c.send(200,map("items",items,"hasMore",hasMore));
        }
    }
    private static void create(Ctx c) throws Exception {
        Map<String,Media.Part> parts=Media.multipart(c);String caption=partText(parts,"caption"),visibility=partText(parts,"visibility"),kind=partText(parts,"location_kind");
        if(!parts.containsKey("image")||parts.get("image").filename()==null||parts.get("image").filename().isEmpty()||!validText(caption,0,1000)||!validVisibility(visibility))throw error(400,"invalid_post");
        Double latitude=numberPart(parts,"latitude"),longitude=numberPart(parts,"longitude");String locality=parts.containsKey("locality")?partText(parts,"locality"):null;
        boolean valid=switch(kind){case "none"->latitude==null&&longitude==null&&locality==null;case "exact"->latitude!=null&&longitude!=null&&locality==null&&latitude>=-90&&latitude<=90&&longitude>=-180&&longitude<=180;case "approximate"->latitude==null&&longitude==null&&locality!=null&&validText(locality,1,100);default->false;};
        if(!valid)throw error(400,"invalid_post");byte[] data=parts.get("image").bytes();if(!Media.validJpeg(data,4096))throw error(400,"invalid_post");
        Map<String,Object> result=tx(db->{
            one(db,"SELECT id FROM users WHERE id=$1 FOR NO KEY UPDATE",c.userId);Row u=user(db,c.userId);
            Row w=one(db,"SELECT id,local_date,starts_at FROM posting_windows WHERE region=$1 AND ended_at IS NULL FOR UPDATE",u.str("region"));
            if(w==null)w=one(db,"SELECT id,local_date,starts_at FROM posting_windows WHERE region=$1 AND ended_at IS NULL FOR UPDATE",u.str("region"));if(w==null)throw error(409,"post_slot_unavailable");
            Row counts=one(db,"SELECT count(*) FILTER (WHERE deleted_at IS NULL) AS active,count(*) FILTER (WHERE deleted_at IS NOT NULL) AS deleted FROM posts p JOIN posting_windows w ON w.id=p.posting_window_id WHERE p.author_id=$1 AND w.local_date=$2",c.userId,w.get("local_date"));
            Instant postedAt=Instant.now();if(counts.number("active").longValue()!=0||counts.number("deleted").longValue()>=2||postedAt.isBefore(w.time("starts_at")))throw error(409,"post_slot_unavailable");
            String imageId=id(),postId=id();exec(db,"INSERT INTO image_objects(id,owner_user_id,data,media_type,created_at) VALUES($1,$2,$3,'image/jpeg',$4)",imageId,c.userId,data,postedAt);
            exec(db,"INSERT INTO posts(id,author_id,posting_window_id,image_id,caption,visibility,location_kind,latitude,longitude,locality,posted_at) VALUES($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11)",postId,c.userId,w.str("id"),imageId,caption,visibility,kind,latitude,longitude,locality,postedAt);
            return response(getPost(db,postId),u);
        });c.send(201,result);
    }
    private static String partText(Map<String,Media.Part> parts,String name){
        if(!parts.containsKey(name))return "";
        try{return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(parts.get(name).bytes())).toString();}
        catch(CharacterCodingException ex){throw error(400,"invalid_post");}
    }
    private static Double numberPart(Map<String,Media.Part> parts,String name){
        if(!parts.containsKey(name))return null;
        String value=partText(parts,name);
        // strconv.ParseFloat permits Go numeric literals (including digit separators),
        // but Java's parser additionally accepts whitespace and f/d suffixes.
        String digits="[0-9](?:_?[0-9])*",hex="[0-9a-fA-F](?:_?[0-9a-fA-F])*";
        String decimal="[+-]?(?:"+digits+"(?:\\.(?:"+digits+")?)?|\\."+digits+")(?:[eE][+-]?"+digits+")?";
        String hexadecimal="[+-]?0[xX]_?(?:"+hex+"(?:\\.(?:"+hex+")?)?|\\."+hex+")[pP][+-]?"+digits;
        if(value.equalsIgnoreCase("nan"))return Double.NaN;
        if(value.matches("(?i)[+-]?(?:inf|infinity)"))return value.startsWith("-")?Double.NEGATIVE_INFINITY:Double.POSITIVE_INFINITY;
        if(!value.matches(decimal)&&!value.matches(hexadecimal))throw error(400,"invalid_post");
        try{return Double.valueOf(value.replace("_",""));}catch(NumberFormatException ex){throw error(400,"invalid_post");}
    }
}
