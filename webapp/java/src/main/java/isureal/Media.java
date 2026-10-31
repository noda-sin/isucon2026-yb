package isureal;

import static isureal.Core.*;
import java.io.*;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.time.Instant;
import java.util.*;
import java.util.regex.*;
import java.util.zip.*;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;

/** Initial Go media, interaction and streaming API semantics. */
public final class Media {
    private Media() {}
    public record Part(byte[] bytes, String filename, String contentType) {
        public String text() { return new String(bytes, StandardCharsets.UTF_8); }
    }
    private static String parameter(String header,String key) {
        Matcher m=Pattern.compile("(?:^|;)\\s*"+Pattern.quote(key)+"\\s*=\\s*(?:\"((?:\\\\.|[^\"])*)\"|([^;\\s]*))",Pattern.CASE_INSENSITIVE).matcher(header);
        if(!m.find())return null;
        return m.group(1)==null?m.group(2):m.group(1).replaceAll("\\\\(.)","$1");
    }
    /** Binary-safe, first-value multipart reader (like the initial Go form binder). */
    public static Map<String,Part> multipart(Ctx c) throws Exception {
        return multipart(c.header("Content-Type"),c.raw());
    }
    static Map<String,Part> multipart(String type,byte[] body) {
        if(body.length==0)return new LinkedHashMap<>();
        String mediaType=type.split(";",2)[0].trim().toLowerCase(Locale.ROOT);
        // Echo binds these body formats, but neither can supply a multipart file.
        if(mediaType.equals("application/json")){
            try{mapper.readTree(body);}catch(IOException e){throw error(400,"invalid_json");}
            return new LinkedHashMap<>();
        }
        if(mediaType.equals("application/x-www-form-urlencoded"))return new LinkedHashMap<>();
        if(!mediaType.equals("multipart/form-data"))throw error(415,"code=415, message=Unsupported Media Type");
        String boundary=parameter(type,"boundary");
        if(boundary==null||boundary.isEmpty()||boundary.length()>70||boundary.indexOf('\r')>=0||boundary.indexOf('\n')>=0)throw error(400,"invalid_multipart");
        String raw=new String(body,StandardCharsets.ISO_8859_1), delimiter="--"+boundary;
        int pos=raw.indexOf(delimiter);
        if(pos<0)throw error(400,"invalid_multipart");
        Map<String,Part> parts=new LinkedHashMap<>();
        while(true){
            pos+=delimiter.length();
            if(raw.startsWith("--",pos))return parts;
            if(!raw.startsWith("\r\n",pos))throw error(400,"invalid_multipart");
            int headEnd=raw.indexOf("\r\n\r\n",pos+2);
            if(headEnd<0)throw error(400,"invalid_multipart");
            Map<String,String> headers=new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
            for(String line:raw.substring(pos+2,headEnd).split("\r\n")){
                int colon=line.indexOf(':');if(colon<=0)throw error(400,"invalid_multipart");
                headers.putIfAbsent(line.substring(0,colon).trim(),line.substring(colon+1).trim());
            }
            int bodyStart=headEnd+4, next=raw.indexOf("\r\n"+delimiter,bodyStart);
            // A boundary-like byte sequence inside a JPEG is not a delimiter.
            while(next>=0&&!raw.startsWith("\r\n",next+2+delimiter.length())&&!raw.startsWith("--",next+2+delimiter.length()))next=raw.indexOf("\r\n"+delimiter,next+2);
            if(next<0)throw error(400,"invalid_multipart");
            String disposition=headers.getOrDefault("Content-Disposition","");
            if(disposition.split(";",2)[0].trim().equalsIgnoreCase("form-data")){
                String name=parameter(disposition,"name"), filename=parameter(disposition,"filename");
                // Go keeps form values and uploaded files in separate maps. The only
                // file binding in these APIs is image; textual fields ignore file parts.
                boolean file=filename!=null&&!filename.isEmpty();
                if(name!=null && (name.equals("image")?file:!file))
                    parts.putIfAbsent(name,new Part(raw.substring(bodyStart,next).getBytes(StandardCharsets.ISO_8859_1),filename,headers.getOrDefault("Content-Type","application/octet-stream")));
            }
            pos=next+2;
        }
    }
    public static boolean validJpeg(byte[] bytes,int maxDimension) { return jpegResult(bytes,maxDimension)==0; }
    // -1 malformed/non-JPEG; 1 oversized; 0 accepted. Abuse logging distinguishes these.
    private static int jpegResult(byte[] bytes,int maxDimension) {
        try(ImageInputStream input=ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))){
            if(input==null)return -1;
            Iterator<ImageReader> readers=ImageIO.getImageReaders(input);
            if(!readers.hasNext())return -1;
            ImageReader reader=readers.next();
            try {
                if(!reader.getFormatName().equalsIgnoreCase("JPEG"))return -1;
                boolean[] warned={false};
                reader.addIIOReadWarningListener((source,warning)->warned[0]=true);
                reader.setInput(input,true,true);
                // Reject too-large images before allocating their full decoded raster.
                int width=reader.getWidth(0),height=reader.getHeight(0);
                if(width<1||height<1)return -1;
                if(width>maxDimension||height>maxDimension)return 1;
                return reader.read(0)!=null&&!warned[0]?0:-1;
            } finally { reader.dispose(); }
        }catch(IOException|RuntimeException e){return -1;}
    }
    public static void install(){
        route("PUT","/api/me/profile-image",Auth.USER,Media::putProfile);
        route("DELETE","/api/me/profile-image",Auth.USER,Media::deleteProfile);
        route("GET","/api/images/:imageId",Auth.NONE,Media::getImage);
        route("POST","/api/posts/:postId/comments",Auth.REGION,Media::createComment);
        route("GET","/api/posts/:postId/comments",Auth.REGION,Media::comments);
        route("DELETE","/api/comments/:commentId",Auth.REGION,Media::deleteComment);
        route("POST","/api/posts/:postId/realmojis",Auth.REGION,Media::replaceRealmoji);
        route("GET","/api/posts/:postId/realmojis",Auth.REGION,Media::realmojis);
        route("DELETE","/api/realmojis/:realMojiId",Auth.REGION,Media::deleteRealmoji);
        route("GET","/api/notifications",Auth.REGION,Media::notifications);
        route("GET","/api/me/export",Auth.REGION,Media::exportPosts);
    }
    private static void putProfile(Ctx c)throws Exception{
        if(!c.header("Content-Type").equals("image/jpeg"))throw error(415,"unsupported_media_type");
        byte[] data=c.raw();
        int validation=jpegResult(data,512);
        if(validation!=0){if(validation<0)c.abuse="invalid_image";throw error(400,"invalid_profile_image");}
        tx(db->{Row old=one(db,"SELECT profile_image_id FROM users WHERE id=$1 FOR NO KEY UPDATE",c.userId);
            if(old==null)throw error(404,"account_not_found");
            String image=id();exec(db,"INSERT INTO image_objects(id,owner_user_id,data,media_type) VALUES ($1,$2,$3,'image/jpeg')",image,c.userId,data);
            exec(db,"UPDATE users SET profile_image_id=$2,updated_at=clock_timestamp() WHERE id=$1",c.userId,image);
            if(old.get("profile_image_id")!=null)exec(db,"DELETE FROM image_objects WHERE id=$1",old.str("profile_image_id"));return null;});c.empty(204);
    }
    private static void deleteProfile(Ctx c)throws Exception{
        tx(db->{Row old=one(db,"SELECT profile_image_id FROM users WHERE id=$1 FOR NO KEY UPDATE",c.userId);
            if(old==null||old.get("profile_image_id")==null)throw error(404,"account_not_found");
            exec(db,"UPDATE users SET profile_image_id=NULL,updated_at=clock_timestamp() WHERE id=$1",c.userId);
            exec(db,"DELETE FROM image_objects WHERE id=$1",old.str("profile_image_id"));return null;});c.empty(204);
    }
    private static Map<String,List<String>> queryAll(String raw){
        Map<String,List<String>> values=new HashMap<>();if(raw==null)return values;
        for(String pair:raw.split("&",-1)){if(pair.indexOf(';')>=0)throw error(403,"image_forbidden");if(pair.isEmpty())continue;
            String[] kv=pair.split("=",2);
            try{String key=URLDecoder.decode(kv[0],StandardCharsets.UTF_8),value=URLDecoder.decode(kv.length==1?"":kv[1],StandardCharsets.UTF_8);values.computeIfAbsent(key,k->new ArrayList<>()).add(value);}catch(IllegalArgumentException e){throw error(403,"image_forbidden");}}
        return values;
    }
    private static void getImage(Ctx c)throws Exception{
        c.exchange.getResponseHeaders().set("Cache-Control","no-store");
        Map<String,List<String>> query=queryAll(c.exchange.getRequestURI().getRawQuery());
        if(query.getOrDefault("exp",List.of()).size()!=1||query.getOrDefault("sig",List.of()).size()!=1)throw error(403,"image_forbidden");
        String rawExp=query.get("exp").getFirst(),rawSig=query.get("sig").getFirst();long exp;byte[] sig;
        try{exp=Long.parseLong(rawExp);sig=Base64.getUrlDecoder().decode(rawSig);}catch(IllegalArgumentException e){throw error(403,"image_forbidden");}
        if(!Long.toString(exp).equals(rawExp)||exp<=Instant.now().getEpochSecond()||sig.length!=32||!Base64.getUrlEncoder().withoutPadding().encodeToString(sig).equals(rawSig))throw error(403,"image_forbidden");
        try(Connection db=connect()){
            Row owner=one(db,"SELECT u.secret_key_hash FROM image_objects i JOIN users u ON u.id=i.owner_user_id WHERE i.id=$1 AND u.status='active'",c.path("imageId"));
            if(owner==null)throw error(403,"image_forbidden");
            if(!MessageDigest.isEqual(sig,signature(owner,c.path("imageId"),exp))){c.abuse="invalid_signature";throw error(403,"image_forbidden");}
            Row object=one(db,"""
                SELECT i.data,i.media_type FROM image_objects i
                JOIN users u ON u.id=i.owner_user_id AND u.status='active'
                WHERE i.id=$1 AND (
                  u.profile_image_id=i.id
                  OR EXISTS(SELECT 1 FROM posts p WHERE p.image_id=i.id AND p.author_id=u.id AND p.deleted_at IS NULL)
                  OR EXISTS(SELECT 1 FROM realmojis r JOIN posts p ON p.id=r.post_id AND p.deleted_at IS NULL
                    JOIN users author ON author.id=p.author_id AND author.status='active'
                    WHERE r.image_id=i.id AND r.reactor_id=u.id AND r.replaced_at IS NULL AND r.deleted_at IS NULL))
                """,c.path("imageId"));
            if(object==null||exp<=Instant.now().getEpochSecond())throw error(403,"image_forbidden");
            c.sendBytes(200,object.bytes("data"),object.str("media_type"));
        }
    }
    private static void lockUsers(Connection db,String actor,String target,boolean requireActive)throws Exception{
        for(String uid:new TreeSet<>(List.of(actor,target))) {
            Row locked=one(db,"SELECT id FROM users WHERE id=$1 "+(requireActive?"AND status='active' ":"")+"FOR NO KEY UPDATE",uid);
            if(locked==null)throw requireActive?error(404,"relationship_not_found"):error(500,"account_not_found");
        }
    }
    private static boolean canComment(Connection db,String actor,String postId)throws Exception{
        Row post=Posts.getPostWithAuthor(db,postId);if(post==null)return false;
        Posts.Viewer viewer=Posts.viewer(db,actor);if(viewer==null||!Posts.canView(viewer,post)||post.get("window_ended_at")!=null)return false;
        if(actor.equals(post.str("author_id")))return viewer.hasPostedToday;
        return post.str("visibility").equals("public")||viewer.friends.contains(post.str("author_id"));
    }
    private static Map<String,Object> commentResponse(Row record,Row author){return map("id",record.str("id"),"author",relationshipUser(author),"body",record.str("body"),"createdAt",record.time("created_at"));}
    private static Map<String,Object> realmojiResponse(Row record,Row reactor){return map("id",record.str("id"),"reactor",relationshipUser(reactor),"reactionType",record.str("reaction_type"),"imageUrl",signedImageURL(reactor,record.str("image_id")),"createdAt",record.time("created_at"));}
    private static void createComment(Ctx c)throws Exception{
        String body=text(c.body(),"body");if(!validText(body,1,1000))throw error(400,"invalid_interaction");
        Row post;try(Connection db=connect()){post=Posts.getPost(db,c.path("postId"));}
        if(post==null)throw error(403,"interaction_forbidden");
        Map<String,Object> result=tx(db->{lockUsers(db,c.userId,post.str("author_id"),false);
            if(!canComment(db,c.userId,post.str("id")))throw error(403,"interaction_forbidden");
            String comment=id();exec(db,"INSERT INTO comments(id,post_id,author_id,body,created_at) VALUES($1,$2,$3,$4,clock_timestamp())",comment,post.str("id"),c.userId,body);
            return commentResponse(one(db,"SELECT id,body,created_at FROM comments WHERE id=$1",comment),user(db,c.userId));});c.send(201,result);
    }
    private static Instant cursor(Ctx c,String timeName,String idName){
        String time=c.query(timeName),id=c.query(idName);
        if(time.isEmpty()!=id.isEmpty())throw error(400,"invalid_pagination");
        try{return time.isEmpty()?null:Instant.parse(time);}catch(Exception e){throw error(400,"invalid_pagination");}
    }
    private static final String USER_FIELDS="id,username,secret_key_hash,display_name,bio,profile_image_id,region,account_type,status";
    private static String userColumns(String alias){StringJoiner sql=new StringJoiner(",");for(String key:USER_FIELDS.split(","))sql.add(alias+"."+key+" AS \"person."+key+"\"");return sql.toString();}
    private static Row person(Row row){Row u=new Row();for(String key:USER_FIELDS.split(","))u.put(key,row.get("person."+key));return u;}
    private static List<Map<String,Object>> commentRows(Connection db,String condition,Object...args)throws Exception{
        List<Map<String,Object>> list=new ArrayList<>();
        for(Row r:all(db,"SELECT c.id,c.body,c.created_at,"+userColumns("u")+" FROM comments c JOIN users u ON u.id=c.author_id AND u.status='active' WHERE c.deleted_at IS NULL "+condition,args))list.add(commentResponse(r,person(r)));
        return list;
    }
    private static List<Map<String,Object>> realmojiRows(Connection db,String condition,Object...args)throws Exception{
        List<Map<String,Object>> list=new ArrayList<>();
        for(Row r:all(db,"SELECT r.id,r.image_id,r.reaction_type,r.created_at,"+userColumns("u")+" FROM realmojis r JOIN users u ON u.id=r.reactor_id AND u.status='active' WHERE r.replaced_at IS NULL AND r.deleted_at IS NULL "+condition,args))list.add(realmojiResponse(r,person(r)));
        return list;
    }
    public static List<Map<String,Object>> memoryComments(Connection db,String postId)throws Exception{return commentRows(db,"AND c.post_id=$1 ORDER BY c.created_at,c.id LIMIT 50",postId);}
    public static List<Map<String,Object>> memoryRealmojis(Connection db,String postId)throws Exception{return realmojiRows(db,"AND r.post_id=$1 ORDER BY r.created_at DESC,r.id DESC LIMIT 50",postId);}
    private static void comments(Ctx c)throws Exception{
        Instant after=cursor(c,"afterCreatedAt","afterId");
        try(Connection db=connect()){
            if(!canComment(db,c.userId,c.path("postId")))throw error(403,"interaction_forbidden");
            List<Map<String,Object>> list=commentRows(db,"AND c.post_id=$1 AND ($2::timestamptz IS NULL OR (c.created_at,c.id)>($2,$3)) ORDER BY c.created_at,c.id LIMIT 51",c.path("postId"),after,c.query("afterId"));
            c.send(200,map("items",list.size()>50?list.subList(0,50):list,"hasMore",list.size()>50));
        }
    }
    private static void deleteComment(Ctx c)throws Exception{
        tx(db->{one(db,"SELECT id FROM users WHERE id=$1 FOR NO KEY UPDATE",c.userId);
            Row record=one(db,"SELECT c.author_id,p.author_id AS post_author_id FROM comments c JOIN posts p ON p.id=c.post_id WHERE c.id=$1 AND c.deleted_at IS NULL FOR UPDATE OF c",c.path("commentId"));
            if(record==null)throw error(404,"interaction_not_found");
            if(!c.userId.equals(record.str("author_id"))&&!c.userId.equals(record.str("post_author_id")))throw error(403,"interaction_forbidden");
            exec(db,"UPDATE comments SET deleted_at=clock_timestamp() WHERE id=$1",c.path("commentId"));return null;});c.empty(204);
    }
    private static void replaceRealmoji(Ctx c)throws Exception{
        Map<String,Part> form=multipart(c);Part image=form.get("image"),reaction=form.get("reactionType");String type=reaction==null?"":reaction.text();
        if(image==null||(image.filename()==null||image.filename().isEmpty())||!Set.of("like","smile","surprise","heart","laugh","instant").contains(type)||!validJpeg(image.bytes(),512))throw error(400,"invalid_realmoji");
        Row post;try(Connection db=connect()){post=Posts.getPost(db,c.path("postId"));}
        if(post==null||c.userId.equals(post.str("author_id")))throw error(403,"interaction_forbidden");
        Map<String,Object> result=tx(db->{lockUsers(db,c.userId,post.str("author_id"),true);
            if(!Posts.canViewPost(db,c.userId,post.str("id")))throw error(403,"interaction_forbidden");
            exec(db,"UPDATE realmojis SET replaced_at=clock_timestamp() WHERE post_id=$1 AND reactor_id=$2 AND replaced_at IS NULL AND deleted_at IS NULL",post.str("id"),c.userId);
            String imageId=id(),reactionId=id();exec(db,"INSERT INTO image_objects(id,owner_user_id,data,media_type) VALUES($1,$2,$3,'image/jpeg')",imageId,c.userId,image.bytes());
            exec(db,"INSERT INTO realmojis(id,post_id,reactor_id,image_id,reaction_type,created_at) VALUES($1,$2,$3,$4,$5,clock_timestamp())",reactionId,post.str("id"),c.userId,imageId,type);
            return realmojiResponse(one(db,"SELECT id,image_id,reaction_type,created_at FROM realmojis WHERE id=$1",reactionId),user(db,c.userId));});c.send(201,result);
    }
    private static void realmojis(Ctx c)throws Exception{
        Instant before=cursor(c,"beforeCreatedAt","beforeId");
        try(Connection db=connect()){
            if(!Posts.canViewPost(db,c.userId,c.path("postId")))throw error(403,"interaction_forbidden");
            List<Map<String,Object>> list=realmojiRows(db,"AND r.post_id=$1 AND ($2::timestamptz IS NULL OR (r.created_at,r.id)<($2,$3)) ORDER BY r.created_at DESC,r.id DESC LIMIT 51",c.path("postId"),before,c.query("beforeId"));
            c.send(200,map("items",list.size()>50?list.subList(0,50):list,"hasMore",list.size()>50));
        }
    }
    private static void deleteRealmoji(Ctx c)throws Exception{
        tx(db->{one(db,"SELECT id FROM users WHERE id=$1 FOR NO KEY UPDATE",c.userId);
            Row record=one(db,"SELECT r.reactor_id,p.author_id FROM realmojis r JOIN posts p ON p.id=r.post_id WHERE r.id=$1 AND r.deleted_at IS NULL FOR UPDATE OF r",c.path("realMojiId"));
            if(record==null)throw error(404,"interaction_not_found");
            if(!c.userId.equals(record.str("reactor_id"))&&!c.userId.equals(record.str("author_id")))throw error(403,"interaction_forbidden");
            exec(db,"UPDATE realmojis SET deleted_at=clock_timestamp() WHERE id=$1",c.path("realMojiId"));return null;});c.empty(204);
    }
    private static void notifications(Ctx c)throws Exception{
        Instant connectedAt;try(Connection db=connect()){connectedAt=one(db,"SELECT clock_timestamp() AS now").time("now");}
        c.exchange.getResponseHeaders().set("Content-Type","text/event-stream");c.status=200;c.committed=true;c.exchange.sendResponseHeaders(200,0);
        try(OutputStream out=c.exchange.getResponseBody()){
            out.flush();
            long heartbeat=System.nanoTime();
            while(!Thread.currentThread().isInterrupted()){
                Row notification;
                try(Connection db=connect()){
                    Row recipient=user(db,c.userId);if(recipient==null||!recipient.str("region").equals(c.sessionRegion))return;
                    notification=one(db,"""
                        SELECT n.id,n.posting_window_id,w.region,n.created_at FROM notifications n
                        JOIN posting_windows w ON w.id=n.posting_window_id
                        WHERE n.user_id=$1 AND n.created_at>$2 AND w.ended_at IS NULL
                        ORDER BY n.created_at,n.id LIMIT 1
                        """,c.userId,connectedAt);
                }
                if(notification!=null){
                    Object value=map("id",notification.str("id"),"postingWindowId",notification.str("posting_window_id"),"region",notification.str("region"),"createdAt",notification.time("created_at"));
                    out.write(("data: "+mapper.writeValueAsString(value)+"\n\n").getBytes(StandardCharsets.UTF_8));out.flush();return;
                }
                // SSE comments do not dispatch events. A periodic flush detects client disconnects.
                if(System.nanoTime()-heartbeat>=15_000_000_000L){
                    out.write(": keepalive\n\n".getBytes(StandardCharsets.UTF_8));out.flush();heartbeat=System.nanoTime();
                }
                Thread.sleep(500);
            }
        }catch(IOException ignored){/* a disconnected SSE client is not an application error */}
    }
    private static void exportPosts(Ctx c)throws Exception{
        List<Row> posts;Row author;
        try(Connection db=connect()){
            author=user(db,c.userId);
            posts=all(db,"""
                SELECT p.id,p.author_id,p.posting_window_id,p.image_id,p.caption,p.visibility,
                  p.location_kind,p.latitude,p.longitude,p.locality,p.posted_at,
                  w.on_time_deadline,w.ended_at AS window_ended_at,i.data AS image_data
                FROM posts p JOIN posting_windows w ON w.id=p.posting_window_id
                JOIN image_objects i ON i.id=p.image_id
                WHERE p.author_id=$1 AND p.deleted_at IS NULL ORDER BY p.posted_at,p.id
                """,c.userId);
        }
        ByteArrayOutputStream body=new ByteArrayOutputStream();List<Map<String,Object>> items=new ArrayList<>();
        try(ZipOutputStream zip=new ZipOutputStream(body)){
            for(Row post:posts){String imagePath=String.format(Locale.ROOT,"images/%06d.jpg",items.size()+1);byte[] data=post.bytes("image_data");CRC32 crc=new CRC32();crc.update(data);
                ZipEntry entry=new ZipEntry(imagePath);entry.setMethod(ZipEntry.STORED);entry.setSize(data.length);entry.setCompressedSize(data.length);entry.setCrc(crc.getValue());zip.putNextEntry(entry);zip.write(data);zip.closeEntry();
                Map<String,Object> item=new LinkedHashMap<>(Posts.response(post,author));item.put("imagePath",imagePath);items.add(item);
            }
            zip.putNextEntry(new ZipEntry("posts.json"));zip.write(mapper.writeValueAsBytes(items));zip.write('\n');zip.closeEntry();
        }
        c.exchange.getResponseHeaders().set("Content-Disposition","attachment; filename=\"isureal-posts.zip\"");c.exchange.getResponseHeaders().set("Cache-Control","no-store");c.sendBytes(200,body.toByteArray(),"application/zip");
    }
}
