package isureal;

import static isureal.Core.*;
import java.net.InetAddress;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;

/** Opt-in live integration test; run as a separate process inside the Java container. */
public final class JdbcProbe {
    static Connection open(String hosts, boolean smart)throws Exception {
        Class.forName("com.yugabyte.Driver");
        Properties p=new Properties();p.setProperty("user",env("ISUREAL_DB_USER","yugabyte"));p.setProperty("password",env("ISUREAL_DB_PASSWORD","yugabyte"));
        p.setProperty("sslmode",env("ISUREAL_DB_SSLMODE","disable"));p.setProperty("connectTimeout","10");p.setProperty("ApplicationName","isureal-jdbc-probe-"+region);
        p.setProperty("load-balance",Boolean.toString(smart));
        if(smart){p.setProperty("topology-keys",env("ISUREAL_DB_TOPOLOGY_KEYS","local."+region+"."+region+":1"));p.setProperty("fallback-to-topology-keys-only","true");p.setProperty("yb-servers-refresh-interval","30");}
        Connection c=DriverManager.getConnection("jdbc:yugabytedb://"+hosts+"/"+env("ISUREAL_DB_NAME","isureal_java"),p);
        c.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);exec(c,"SET statement_timeout='15s'");return c;
    }
    static void require(boolean value,String message){if(!value)throw new AssertionError(message);}
    static String address(Connection c)throws Exception{return one(c,"SELECT host(inet_server_addr()) AS address").str("address");}
    public static void main(String[] args)throws Exception {
        if(!"true".equals(env("ISUREAL_RUN_JDBC_PROBE","false")))throw new IllegalStateException("Set ISUREAL_RUN_JDBC_PROBE=true for synthetic live integration checks");
        String[] hosts=env("ISUREAL_DB_HOSTS","tserver-"+region+"-1:5433,tserver-"+region+"-2:5433").split(",");require(hosts.length==2,"exactly two local seed endpoints required");
        Set<String> expected=new HashSet<>();for(String endpoint:hosts){String name=endpoint.substring(0,endpoint.lastIndexOf(':'));for(InetAddress addr:InetAddress.getAllByName(name))expected.add(addr.getHostAddress());}
        List<Connection> samples=new ArrayList<>();Set<String> observed=new HashSet<>();
        try {
            for(int i=0;i<12;i++){Connection c=open(String.join(",",hosts),true);samples.add(c);String target=address(c);require(expected.contains(target),"smart connection escaped the local region");observed.add(target);}
            require(observed.size()==2,"both same-region TServers must receive connections");
            System.out.println("PASS JDBC smart locality "+region+": 12 connections use exactly two local TServers");
        } finally {for(Connection c:samples)c.close();}
        String nowaitFailure=null;
        try(Connection a=open(hosts[0],false);Connection b=open(hosts[1],false)) {
            require(!address(a).equals(address(b)),"direct-host sessions did not connect to distinct TServers");
            String uid=id(),name="jdbc_probe_"+id().replace("-","");
            try {
                exec(a,"INSERT INTO users(id,username,secret_key_hash,display_name,region) VALUES($1,$2,$3,'before',$4)",uid,name,sha256("synthetic-nonlogin"),region);
                a.setAutoCommit(false);
                require(one(a,"SHOW transaction_isolation").values().iterator().next().equals("read committed"),"RC setting missing");
                require(one(a,"SELECT display_name FROM users WHERE id=$1",uid).str("display_name").equals("before"),"initial snapshot mismatch");
                exec(b,"UPDATE users SET display_name='after' WHERE id=$1",uid);
                require(one(a,"SELECT display_name FROM users WHERE id=$1",uid).str("display_name").equals("after"),"RC silently mapped to snapshot isolation");
                a.commit();a.setAutoCommit(true);
                System.out.println("PASS JDBC Read Committed statement snapshot "+region+": committed update visible across two TServers");
                a.setAutoCommit(false);one(a,"SELECT id FROM users WHERE id=$1 FOR NO KEY UPDATE",uid);
                try{one(b,"SELECT id FROM users WHERE id=$1 FOR UPDATE NOWAIT",uid);throw new AssertionError("NOWAIT unexpectedly acquired row lock");}
                catch(SQLException e){
                    if("55P03".equals(e.getSQLState()))System.out.println("PASS JDBC NOWAIT SQLSTATE 55P03 "+region);
                    else if("40001".equals(e.getSQLState())){
                        nowaitFailure="NOWAIT on the partitioned public.users view returned 40001 instead of 55P03 in "+region;
                        System.out.println("KNOWN_LIMITATION JDBC NOWAIT "+region+": SQLSTATE 40001; upstream issue https://github.com/yugabyte/yugabyte-db/issues/34844. Continuing independent blocking-lock checks.");
                    }else throw e;
                }
                try(ExecutorService executor=Executors.newSingleThreadExecutor()) {
                    Future<Integer> writer=executor.submit(()->exec(b,"UPDATE users SET display_name='after-lock' WHERE id=$1",uid));
                    try{writer.get(500,TimeUnit.MILLISECONDS);throw new AssertionError("writer did not wait on FOR NO KEY UPDATE");}catch(TimeoutException expectedWait){}
                    a.commit();a.setAutoCommit(true);require(writer.get(20,TimeUnit.SECONDS)==1,"writer did not update exactly one user");
                    require(one(a,"SELECT display_name FROM users WHERE id=$1",uid).str("display_name").equals("after-lock"),"waiting writer result missing");
                }
                System.out.println("PASS JDBC row lock/wait/release "+region);
            } finally {if(!a.getAutoCommit()){a.rollback();a.setAutoCommit(true);}exec(a,"DELETE FROM users WHERE id=$1",uid);}
        }
        if(nowaitFailure!=null&&"true".equals(env("ISUREAL_REQUIRE_PARTITION_NOWAIT","false")))throw new AssertionError(nowaitFailure+"; locality, RC and blocking row-lock checks passed, synthetic probe user removed");
        System.out.println("PASS supported JDBC operations only: locality, RC and blocking row locks; synthetic probe user removed. NOWAIT capability is reported separately.");
    }
}
