package isureal;

import java.sql.Connection;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static isureal.Core.*;

/** Relationship endpoints, ported from the preserved initial Go implementation. */
public final class Social {
    private Social() {}

    public static void install() {
        route("POST", "/api/friend-requests", Auth.REGION, Social::createRequest);
        route("POST", "/api/friend-requests/:requestId/:action", Auth.REGION, Social::transitionRequest);
        route("GET", "/api/relationships/:kind", Auth.REGION, Social::relationships);
        route("DELETE", "/api/friendships/:userId", Auth.REGION, Social::deleteFriendship);
        route("PUT", "/api/follows/:userId", Auth.REGION, Social::follow);
        route("DELETE", "/api/follows/:userId", Auth.REGION, Social::unfollow);
        route("PUT", "/api/blocks/:userId", Auth.REGION, Social::block);
        route("DELETE", "/api/blocks/:userId", Auth.REGION, Social::unblock);
    }

    // The order is shared with all other relationship operations to avoid a
    // reciprocal request acquiring user row locks in opposite orders.
    private static void lockPair(Connection db, String actor, String target) throws Exception {
        if (actor.equals(target) || target.isEmpty()) throw error(400, "invalid_relationship");
        for (String userId : List.of(low(actor, target), high(actor, target))) {
            if (one(db, "SELECT id FROM users WHERE id=$1 AND status='active' FOR NO KEY UPDATE", userId) == null) {
                throw error(404, "relationship_not_found");
            }
        }
    }

    private static String low(String a, String b) { return a.compareTo(b) <= 0 ? a : b; }
    private static String high(String a, String b) { return a.compareTo(b) >= 0 ? a : b; }

    private static Row request(Connection db, String requestId) throws Exception {
        return one(db, "SELECT id, requester_id, recipient_id, status, created_at, updated_at FROM friend_requests WHERE id=$1", requestId);
    }

    private static void insertFriendship(Connection db, Row request) throws Exception {
        String requester = request.str("requester_id");
        String recipient = request.str("recipient_id");
        exec(db, "INSERT INTO friendships (id,user_low_id,user_high_id,source_request_id) VALUES ($1,$2,$3,$4)",
                id(), low(requester, recipient), high(requester, recipient), request.str("id"));
    }

    private static Map<String, Object> requestResponse(Row request) {
        return map("relationId", request.str("id"), "status", request.str("status"), "updatedAt", request.time("updated_at"));
    }

    private static void createRequest(Ctx c) throws Exception {
        String recipient = text(c.body(), "recipientId");
        Map<String, Object> result = tx(db -> {
            lockPair(db, c.userId, recipient);
            if (blocked(db, c.userId, recipient) || friendIds(db, c.userId).contains(recipient)) {
                throw error(409, "relationship_conflict");
            }
            Row existing = one(db, """
                    SELECT id FROM friend_requests WHERE status='pending' AND
                    ((requester_id=$1 AND recipient_id=$2) OR (requester_id=$2 AND recipient_id=$1))
                    """, c.userId, recipient);
            String requestId;
            if (existing != null) {
                Row pending = request(db, existing.str("id"));
                if (pending.str("requester_id").equals(c.userId)) throw error(409, "relationship_conflict");
                requestId = pending.str("id");
                exec(db, "UPDATE friend_requests SET status='accepted',updated_at=clock_timestamp() WHERE id=$1", requestId);
                insertFriendship(db, pending);
            } else {
                requestId = id();
                exec(db, """
                        INSERT INTO friend_requests (id,requester_id,recipient_id,status,created_at,updated_at)
                        VALUES ($1,$2,$3,'pending',clock_timestamp(),clock_timestamp())
                        """, requestId, c.userId, recipient);
            }
            return requestResponse(request(db, requestId));
        });
        c.send(201, result);
    }

    private static void transitionRequest(Ctx c) throws Exception {
        String status = switch (c.path("action")) {
            case "accept" -> "accepted";
            case "reject" -> "rejected";
            case "cancel" -> "cancelled";
            default -> throw error(403, "relationship_action_forbidden");
        };
        String requestId = c.path("requestId");
        Row initial;
        try (Connection db = connect()) { initial = request(db, requestId); }
        if (initial == null) throw error(404, "relationship_not_found");
        String actor = c.userId;
        if ((status.equals("cancelled") && !actor.equals(initial.str("requester_id"))) ||
                (!status.equals("cancelled") && !actor.equals(initial.str("recipient_id")))) {
            throw error(403, "relationship_action_forbidden");
        }
        Map<String, Object> result = tx(db -> {
            lockPair(db, initial.str("requester_id"), initial.str("recipient_id"));
            Row current = request(db, requestId);
            if (current == null) throw error(404, "relationship_not_found");
            if (!current.str("status").equals("pending")) throw error(403, "relationship_action_forbidden");
            if (status.equals("accepted")) {
                if (blocked(db, current.str("requester_id"), current.str("recipient_id"))) {
                    throw error(409, "relationship_conflict");
                }
                insertFriendship(db, current);
            }
            exec(db, "UPDATE friend_requests SET status=$2,updated_at=clock_timestamp() WHERE id=$1", requestId, status);
            return requestResponse(request(db, requestId));
        });
        c.send(200, result);
    }

    private static void deleteFriendship(Ctx c) throws Exception {
        tx(db -> {
            String target = c.path("userId");
            lockPair(db, c.userId, target);
            int changed = exec(db, "DELETE FROM friendships WHERE user_low_id=$1 AND user_high_id=$2", low(c.userId, target), high(c.userId, target));
            requireChanged(changed, 409, "relationship_conflict");
            return null;
        });
        c.empty(204);
    }

    private static void follow(Ctx c) throws Exception {
        tx(db -> {
            String targetId = c.path("userId");
            lockPair(db, c.userId, targetId);
            Row target = user(db, targetId);
            if (!target.str("account_type").equals("certified_public") || blocked(db, c.userId, targetId)) {
                throw error(409, "relationship_conflict");
            }
            if (one(db, "SELECT id FROM follows WHERE follower_id=$1 AND followee_id=$2", c.userId, targetId) != null) {
                throw error(409, "relationship_conflict");
            }
            exec(db, "INSERT INTO follows (id,follower_id,followee_id) VALUES ($1,$2,$3)", id(), c.userId, targetId);
            return null;
        });
        c.empty(204);
    }

    private static void unfollow(Ctx c) throws Exception {
        tx(db -> {
            String target = c.path("userId");
            lockPair(db, c.userId, target);
            requireChanged(exec(db, "DELETE FROM follows WHERE follower_id=$1 AND followee_id=$2", c.userId, target), 409, "relationship_conflict");
            return null;
        });
        c.empty(204);
    }

    private static void block(Ctx c) throws Exception {
        tx(db -> {
            String target = c.path("userId");
            lockPair(db, c.userId, target);
            if (one(db, "SELECT id FROM block_relations WHERE blocker_id=$1 AND blocked_id=$2", c.userId, target) != null) {
                throw error(409, "relationship_conflict");
            }
            exec(db, "INSERT INTO block_relations (id,blocker_id,blocked_id) VALUES ($1,$2,$3)", id(), c.userId, target);
            exec(db, "DELETE FROM friendships WHERE user_low_id=$1 AND user_high_id=$2", low(c.userId, target), high(c.userId, target));
            exec(db, "DELETE FROM follows WHERE (follower_id=$1 AND followee_id=$2) OR (follower_id=$2 AND followee_id=$1)", c.userId, target);
            exec(db, """
                    UPDATE friend_requests SET status='cancelled',updated_at=clock_timestamp()
                    WHERE status='pending' AND ((requester_id=$1 AND recipient_id=$2) OR (requester_id=$2 AND recipient_id=$1))
                    """, c.userId, target);
            return null;
        });
        c.empty(204);
    }

    private static void unblock(Ctx c) throws Exception {
        tx(db -> {
            String target = c.path("userId");
            lockPair(db, c.userId, target);
            requireChanged(exec(db, "DELETE FROM block_relations WHERE blocker_id=$1 AND blocked_id=$2", c.userId, target), 403, "relationship_action_forbidden");
            return null;
        });
        c.empty(204);
    }

    private static void requireChanged(int count, int status, String code) {
        if (count == 0) throw error(status, code);
    }

    private static void relationships(Ctx c) throws Exception {
        String kind = c.path("kind");
        String beforeText = c.query("beforeUpdatedAt");
        String beforeId = c.query("beforeId");
        Instant before = null;
        if (!beforeText.isEmpty()) {
            try { before = Instant.parse(beforeText); }
            catch (DateTimeParseException e) { throw error(400, "invalid_pagination"); }
        }
        if ((before == null) != beforeId.isEmpty()) throw error(400, "invalid_pagination");
        String query = switch (kind) {
            case "friends" -> "SELECT id,user_low_id AS first_id,user_high_id AS second_id,updated_at FROM friendships WHERE user_low_id=$1 OR user_high_id=$1";
            case "following" -> "SELECT id,follower_id AS first_id,followee_id AS second_id,updated_at FROM follows WHERE follower_id=$1";
            case "followers" -> "SELECT id,follower_id AS first_id,followee_id AS second_id,updated_at FROM follows WHERE followee_id=$1";
            case "incoming-requests" -> "SELECT id,requester_id AS first_id,recipient_id AS second_id,updated_at FROM friend_requests WHERE recipient_id=$1 AND status='pending'";
            case "outgoing-requests" -> "SELECT id,requester_id AS first_id,recipient_id AS second_id,updated_at FROM friend_requests WHERE requester_id=$1 AND status='pending'";
            default -> throw error(400, "invalid_relationship");
        };
        List<Map<String, Object>> items = new ArrayList<>();
        try (Connection db = connect()) {
            List<Row> rows = all(db, """
                    SELECT relations.id AS relation_id,relations.updated_at AS relation_updated_at,
                           u.id,u.username,u.secret_key_hash,u.display_name,u.bio,u.profile_image_id,u.region,u.account_type,u.status
                    FROM (
                    """ + query + """
                    ) relations
                    JOIN users u ON u.status='active'
                      AND u.id=CASE WHEN relations.first_id=$1 THEN relations.second_id ELSE relations.first_id END
                    WHERE ($2::timestamptz IS NULL OR (relations.updated_at,relations.id)<($2,$3))
                    ORDER BY relations.updated_at DESC,relations.id DESC LIMIT 51
                    """, c.userId, before, beforeId);
            for (Row record : rows) {
                if (!record.str("account_type").equals("certified_public") && !kind.equals("friends")) record.put("bio", "");
                String status = kind.equals("incoming-requests") || kind.equals("outgoing-requests") ? "pending" : kind;
                items.add(map("relationId", record.str("relation_id"), "status", status,
                        "updatedAt", record.time("relation_updated_at"), "user", relationshipUser(record)));
            }
        }
        boolean hasMore = items.size() > 50;
        if (hasMore) items = new ArrayList<>(items.subList(0, 50));
        c.send(200, map("items", items, "hasMore", hasMore));
    }
}
