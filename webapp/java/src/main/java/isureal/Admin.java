package isureal;

import static isureal.Core.*;
import com.fasterxml.jackson.databind.JsonNode;
import java.sql.*;
import java.time.*;

public final class Admin {
    public static void install(){
        route("POST","/api/admin/auth/login",Auth.NONE,Admin::login);
        route("POST","/api/admin/auth/logout",Auth.ADMIN,Admin::logout);
        route("PUT","/api/admin/users/:userId/certification",Auth.ADMIN,Admin::certify);
        route("DELETE","/api/admin/users/:userId/certification",Auth.ADMIN,c->{throw error(409,"certification_irreversible");});
        route("POST","/api/admin/posting-window",Auth.ADMIN,Admin::openWindow);
    }
    static void login(Ctx ctx)throws Exception{
        JsonNode b=ctx.body();String name=text(b,"username"),password=text(b,"password");if(!username(name))throw error(401,"invalid_credentials");
        Row admin;try(Connection c=connect()){admin=one(c,"SELECT id,password_hash FROM administrators WHERE username=$1",name);}
        if(admin==null||!admin.str("password_hash").equals(sha256(password)))throw error(401,"invalid_credentials");
        String token=id();tx(c->{exec(c,"INSERT INTO administrator_sessions (id,administrator_id,token_hash) VALUES ($1,$2,$3)",id(),admin.str("id"),sha256(token));return null;});ctx.send(200,map("token",token,"administratorId",admin.str("id")));
    }
    static void logout(Ctx ctx)throws Exception{
        try(Connection c=connect()){exec(c,"UPDATE administrator_sessions SET active=false,invalidated_at=clock_timestamp() WHERE administrator_id=$1 AND token_hash=$2 AND active=true",ctx.administratorId,ctx.tokenHash);}ctx.empty(204);
    }
    static void certify(Ctx ctx)throws Exception{
        try(Connection c=connect()){if(exec(c,"UPDATE users SET account_type='certified_public',updated_at=clock_timestamp() WHERE id=$1 AND status='active' AND account_type='regular'",ctx.path("userId"))==0)throw error(409,"certification_conflict");}ctx.empty(204);
    }
    static void openWindow(Ctx ctx)throws Exception{
        JsonNode b=ctx.body();String home=text(b,"region"),date=text(b,"localDate");LocalDate day;
        try{if(!date.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}"))throw new IllegalArgumentException();day=LocalDate.parse(date);}catch(Exception e){throw error(400,"invalid_posting_window");}
        if(!validRegion(home))throw error(400,"invalid_posting_window");long seconds=86400;
        if(b.hasNonNull("onTimeSeconds")){JsonNode n=b.get("onTimeSeconds");if(!n.isIntegralNumber()||!n.canConvertToLong())throw error(400,"invalid_json_type");seconds=n.longValue();}
        // Go time.Duration multiplies signed int64 nanoseconds; preserve overflow semantics.
        final long durationNanos=seconds*1_000_000_000L;
        Object result=tx(c->{
            exec(c,"LOCK TABLE users IN SHARE ROW EXCLUSIVE MODE");
            if(one(c,"SELECT EXISTS(SELECT 1 FROM posting_windows WHERE region=$1 AND local_date >= $2) AS found",home,day).bool("found"))throw error(409,"posting_window_conflict");
            Instant start=one(c,"SELECT clock_timestamp() AS now").time("now"),deadline=start.plusNanos(durationNanos);
            exec(c,"UPDATE posting_windows SET ended_at=$2 WHERE region=$1 AND ended_at IS NULL",home,start);
            String wid=id();exec(c,"INSERT INTO posting_windows (id,region,local_date,starts_at,on_time_deadline) VALUES ($1,$2,$3,$4,$5)",wid,home,day,start,deadline);
            exec(c,"INSERT INTO notifications (id,user_id,posting_window_id,created_at) SELECT gen_random_uuid()::text,id,$2,$3 FROM users WHERE region=$1 AND status='active'",home,wid,start);
            Row w=one(c,"SELECT id,region,local_date,starts_at,on_time_deadline FROM posting_windows WHERE id=$1",wid);
            return map("id",w.str("id"),"region",w.str("region"),"localDate",w.str("local_date"),"startsAt",w.time("starts_at"),"onTimeDeadline",w.time("on_time_deadline"));
        });ctx.send(201,result);
    }
}
