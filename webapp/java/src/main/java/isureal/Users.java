package isureal;

import static isureal.Core.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.ibm.icu.lang.UCharacter;
import java.sql.*;
import java.text.Normalizer;
import java.util.*;

public final class Users {
    public static void install(){
        route("POST","/api/auth/register",Auth.NONE,Users::register);
        route("POST","/api/auth/login",Auth.NONE,Users::login);
        route("POST","/api/auth/logout",Auth.USER,Users::logout);
        route("GET","/api/me",Auth.REGION,Users::me);
        route("PATCH","/api/me",Auth.USER,Users::updateMe);
        route("GET","/api/users",Auth.REGION,Users::search);
        route("GET","/api/users/:userId",Auth.REGION,Users::getUser);
        route("POST","/api/me/account-deletion",Auth.REGION,Users::deleteAccount);
    }
    static void register(Ctx ctx)throws Exception{
        JsonNode b=ctx.body();String name=text(b,"username"),display=text(b,"displayName"),home=text(b,"region");ctx.username=name;
        if(!username(name)||!validText(display,1,100)||!validRegion(home))throw error(400,"invalid_registration");
        String secret=id();Object result=tx(c->{
            exec(c,"LOCK TABLE users IN SHARE ROW EXCLUSIVE MODE");
            if(one(c,"SELECT EXISTS(SELECT 1 FROM users WHERE username=$1) AS found",name).bool("found"))throw error(409,"username_taken");
            String uid=id();exec(c,"INSERT INTO users (id,username,secret_key_hash,display_name,region) VALUES ($1,$2,$3,$4,$5)",uid,name,sha256(secret),display,home);
            return map("secretKey",secret,"user",sessionUser(user(c,uid)));
        });ctx.send(201,result);
    }
    static void login(Ctx ctx)throws Exception{
        JsonNode b=ctx.body();String name=text(b,"username"),secret=text(b,"secretKey");ctx.username=name;
        if(!username(name))throw error(401,"invalid_credentials");
        Object result=tx(c->{
            Row u=one(c,"SELECT id,username,secret_key_hash,display_name,bio,profile_image_id,region,account_type,status FROM users WHERE username=$1 FOR NO KEY UPDATE",name);
            if(u==null||!u.str("status").equals("active")||!u.str("secret_key_hash").equals(sha256(secret)))throw error(401,"invalid_credentials");
            one(c,"SELECT id FROM users WHERE id=$1 AND status='active' FOR NO KEY UPDATE",u.str("id"));
            exec(c,"UPDATE login_sessions SET active=false,invalidated_at=clock_timestamp(),reason='replaced' WHERE user_id=$1 AND active=true",u.str("id"));
            String token=id();exec(c,"INSERT INTO login_sessions (id,user_id,token_hash) VALUES ($1,$2,$3)",id(),u.str("id"),sha256(token));
            return map("token",token,"user",sessionUser(u));
        });ctx.send(200,result);
    }
    static void logout(Ctx ctx)throws Exception{
        tx(c->{one(c,"SELECT id FROM users WHERE id=$1 FOR NO KEY UPDATE",ctx.userId);exec(c,"UPDATE login_sessions SET active=false,invalidated_at=clock_timestamp(),reason='logout' WHERE user_id=$1 AND active=true AND token_hash=$2",ctx.userId,ctx.tokenHash);return null;});ctx.empty(204);
    }
    static void me(Ctx ctx)throws Exception{
        try(Connection c=connect()){Row u=user(c,ctx.userId);if(u==null)throw error(404,"account_not_found");ctx.send(200,userJson(u));}
    }
    static void updateMe(Ctx ctx)throws Exception{
        JsonNode b=ctx.body();boolean display=b.hasNonNull("displayName"),bio=b.hasNonNull("bio"),home=b.hasNonNull("region");
        String d=text(b,"displayName"),bi=text(b,"bio"),h=text(b,"region");
        if((!display&&!bio&&!home)||(display&&!validText(d,1,100))||(bio&&!validText(bi,0,500))||(home&&!validRegion(h)))throw error(400,"invalid_profile");
        Object result=tx(c->{
            if(display)exec(c,"UPDATE users SET display_name=$2,updated_at=clock_timestamp() WHERE id=$1",ctx.userId,d);
            if(bio)exec(c,"UPDATE users SET bio=$2,updated_at=clock_timestamp() WHERE id=$1",ctx.userId,bi);
            if(home)exec(c,"UPDATE users SET region=$2,updated_at=clock_timestamp() WHERE id=$1",ctx.userId,h);
            Row u=user(c,ctx.userId);if(u==null)throw error(404,"account_not_found");return userJson(u);
        });ctx.send(200,result);
    }
    static String normalize(String value){return Normalizer.normalize(UCharacter.foldCase(Normalizer.normalize(value,Normalizer.Form.NFC),true),Normalizer.Form.NFC);}
    static void search(Ctx ctx)throws Exception{
        String query=ctx.query("q"),before=ctx.query("beforeUsername"),beforeID=ctx.query("beforeId");
        if(!validText(query,1,100))throw error(400,"invalid_relationship");if(before.isEmpty()!=beforeID.isEmpty())throw error(400,"invalid_pagination");
        try(Connection c=connect()){
            List<Row> users=all(c,"SELECT id,username,secret_key_hash,display_name,bio,profile_image_id,region,account_type,status FROM users WHERE status='active' AND id<>$1 AND ($2='' OR (username,id)>($2,$3)) ORDER BY username,id",ctx.userId,before,beforeID);
            Set<String> blocked=new HashSet<>();for(Row r:all(c,"SELECT CASE WHEN blocker_id=$1 THEN blocked_id ELSE blocker_id END AS id FROM block_relations WHERE blocker_id=$1 OR blocked_id=$1",ctx.userId))blocked.add(r.str("id"));
            String folded=normalize(query);List<Object> items=new ArrayList<>();
            for(Row u:users){if(!normalize(u.str("username")).contains(folded)&&!normalize(u.str("display_name")).contains(folded))continue;if(blocked.contains(u.str("id")))continue;if(!u.str("account_type").equals("certified_public"))u.put("bio","");items.add(relationshipUser(u));if(items.size()==51)break;}
            boolean more=items.size()>50;if(more)items=new ArrayList<>(items.subList(0,50));ctx.send(200,map("items",items,"hasMore",more));
        }
    }
    static void getUser(Ctx ctx)throws Exception{
        try(Connection c=connect()){
            Row u=user(c,ctx.path("userId"));if(u==null||blocked(c,ctx.userId,u.str("id")))throw error(404,"relationship_not_found");
            Set<String> friends=friendIds(c,ctx.userId);if(!u.str("account_type").equals("certified_public")&&!friends.contains(u.str("id")))u.put("bio","");ctx.send(200,relationshipUser(u));
        }
    }
    static void deleteAccount(Ctx ctx)throws Exception{
        tx(c->{Row u=one(c,"SELECT id FROM users WHERE id=$1 AND status='active' FOR NO KEY UPDATE",ctx.userId);if(u==null)throw error(404,"account_not_found");exec(c,"UPDATE users SET status='deleted',deleted_at=clock_timestamp(),updated_at=clock_timestamp() WHERE id=$1",ctx.userId);exec(c,"UPDATE login_sessions SET active=false,invalidated_at=clock_timestamp(),reason='account_deletion' WHERE user_id=$1 AND active=true",ctx.userId);return null;});ctx.empty(204);
    }
}
