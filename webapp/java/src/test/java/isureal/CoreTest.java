package isureal;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import java.time.*;
import java.util.*;

class CoreTest {
    @Test void repeatedNumberedSQLParametersRetainOrder(){
        Core.BoundSQL b=Core.bind("SELECT '$2', \"$1\", id FROM users WHERE id=$2 OR id=$1 OR id=$2", "first", "second");
        assertEquals("SELECT '$2', \"$1\", id FROM users WHERE id=? OR id=? OR id=?",b.sql());
        assertEquals(List.of("second","first","second"),b.args());
    }
    @Test void nullableSQLParameterIsPreserved(){
        assertNull(Core.bind("SELECT $1",(Object)null).args().getFirst());
    }
    @Test void unicodeLengthAndSearchUseCodePointsAndFullFold(){
        assertTrue(Core.validText("😀",1,1));
        assertFalse(Core.validText("\ud800",1,1));
        assertEquals("strasse",Users.normalize("Straße"));
        assertEquals(Users.normalize("é"),Users.normalize("e\u0301"));
    }
    @Test void imageSignatureMatchesIndependentKnownFixture(){
        Core.Row u=new Core.Row();u.put("secret_key_hash",Core.sha256("synthetic-secret"));
        assertEquals("18764fc86bbd5354551b08c7fb9963102375c4bc978964a242e66f19f2cd13c0",HexFormat.of().formatHex(Core.signature(u,"11111111-1111-1111-1111-111111111111",1800000000L)));
    }
    @Test void retriesAreLimitedToKnownTransactionAbortStates(){
        assertTrue(Core.retryable(new java.sql.SQLException("serialization", "40001")));
        assertTrue(Core.retryable(new java.sql.SQLException("deadlock", "40P01")));
        assertFalse(Core.retryable(new java.sql.SQLException("connection failure", "08006")));
        assertFalse(Core.retryable(new java.sql.SQLException("duplicate", "23505")));
    }
    @Test void contentTypeMatchesEchoForJsonAndEmptyBodies()throws Exception{
        byte[] json="{\"username\":\"name\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        assertEquals("name",Core.jsonBody(json,"application/json; charset=utf-8",true).get("username").asText());
        assertTrue(Core.jsonBody(new byte[0],"text/plain",true).isObject());
        Core.ApiError e=assertThrows(Core.ApiError.class,()->Core.jsonBody(json,"text/plain",true));
        assertEquals(415,e.status);assertEquals("code=415, message=Unsupported Media Type",e.code);
    }
    @Test void jsonKeepsNullAndUTCTimestamp()throws Exception{
        var json=Core.mapper.readTree(Core.mapper.writeValueAsBytes(Core.map("nullable",null,"time",Instant.parse("2026-10-31T09:10:11.123456Z"))));
        assertTrue(json.get("nullable").isNull());assertEquals("2026-10-31T09:10:11.123456Z",json.get("time").asText());
    }
}
