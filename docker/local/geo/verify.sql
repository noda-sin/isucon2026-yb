\set ON_ERROR_STOP on
BEGIN;
SET LOCAL ROLE isureal_java_app;
INSERT INTO users(id,username,secret_key_hash,display_name,region) VALUES
('geo-test-user-1','geo_test_user_1','test','Geo test 1','asia'),
('geo-test-user-2','geo_test_user_2','test','Geo test 2','europe');
LOCK TABLE users IN SHARE ROW EXCLUSIVE MODE;
SELECT id FROM users WHERE id='geo-test-user-1' FOR NO KEY UPDATE;
INSERT INTO administrators(id,username,password_hash) VALUES('geo-test-admin','geo_test_admin','test');
INSERT INTO administrator_sessions(id,administrator_id,token_hash) VALUES('geo-test-admin-session','geo-test-admin','test');
INSERT INTO login_sessions(id,user_id,token_hash) VALUES('geo-test-session','geo-test-user-1','test');
INSERT INTO image_objects(id,owner_user_id,data,media_type) VALUES('geo-test-image','geo-test-user-1','\x0102','image/jpeg');
INSERT INTO friendships(id,user_low_id,user_high_id) VALUES('geo-test-friendship','geo-test-user-1','geo-test-user-2');
INSERT INTO follows(id,follower_id,followee_id) VALUES('geo-test-follow','geo-test-user-1','geo-test-user-2');
INSERT INTO block_relations(id,blocker_id,blocked_id) VALUES('geo-test-block','geo-test-user-1','geo-test-user-2');
INSERT INTO posting_windows(id,region,local_date,starts_at,on_time_deadline,ended_at) VALUES('geo-test-window','asia','2000-01-01',clock_timestamp(),clock_timestamp(),clock_timestamp());
SELECT id FROM posting_windows WHERE id='geo-test-window' FOR UPDATE;
INSERT INTO posts(id,author_id,posting_window_id,image_id,visibility,location_kind,posted_at) VALUES('geo-test-post','geo-test-user-1','geo-test-window','geo-test-image','public','none',clock_timestamp());
INSERT INTO friend_requests(id,requester_id,recipient_id,status,created_at,updated_at) VALUES('geo-test-request','geo-test-user-1','geo-test-user-2','pending',clock_timestamp(),clock_timestamp());
INSERT INTO realmojis(id,post_id,reactor_id,image_id,reaction_type,created_at) VALUES('geo-test-realmoji','geo-test-post','geo-test-user-1','geo-test-image','smile',clock_timestamp());
INSERT INTO comments(id,post_id,author_id,body,created_at) VALUES('geo-test-comment','geo-test-post','geo-test-user-1','test',clock_timestamp());
INSERT INTO notifications(id,user_id,posting_window_id,created_at) VALUES('geo-test-notification','geo-test-user-1','geo-test-window',clock_timestamp());
INSERT INTO ip_bans(ip,banned_until) VALUES('192.0.2.254',clock_timestamp());
SELECT c.id FROM comments c JOIN posts p ON p.id=c.post_id WHERE c.id='geo-test-comment' FOR UPDATE OF c;
SELECT r.id FROM realmojis r JOIN posts p ON p.id=r.post_id WHERE r.id='geo-test-realmoji' FOR UPDATE OF r;
DO $$ BEGIN
 BEGIN
 INSERT INTO users(id,username,secret_key_hash,display_name,region) VALUES('geo-test-user-3','geo_test_user_1','test','Duplicate username','america');
 RAISE EXCEPTION 'username uniqueness was not enforced';
 EXCEPTION WHEN unique_violation THEN NULL; END;
 BEGIN
 INSERT INTO users(id,username,secret_key_hash,display_name,region) VALUES('geo-test-user-1','geo_test_user_duplicate_id','test','Duplicate id','america');
 RAISE EXCEPTION 'global id uniqueness was not enforced';
 EXCEPTION WHEN unique_violation THEN NULL; END;
 BEGIN
 INSERT INTO follows(id,follower_id,followee_id) VALUES('geo-test-follow-duplicate','geo-test-user-1','geo-test-user-2');
 RAISE EXCEPTION 'pair uniqueness was not enforced';
 EXCEPTION WHEN unique_violation THEN NULL; END;
 BEGIN
 INSERT INTO comments(id,post_id,author_id,body,created_at) VALUES('geo-test-comment-orphan','missing-post','geo-test-user-1','bad',clock_timestamp());
 RAISE EXCEPTION 'foreign key was not enforced';
 EXCEPTION WHEN foreign_key_violation THEN NULL; END;
 BEGIN
 DELETE FROM users WHERE id='geo-test-user-1';
 RAISE EXCEPTION 'referenced user deletion was not restricted';
 EXCEPTION WHEN foreign_key_violation THEN NULL; END;
 BEGIN
 INSERT INTO realmojis(id,post_id,reactor_id,image_id,reaction_type,created_at) VALUES('geo-test-realmoji-duplicate','geo-test-post','geo-test-user-1','geo-test-image','smile',clock_timestamp());
 RAISE EXCEPTION 'active realmoji uniqueness was not enforced';
 EXCEPTION WHEN unique_violation THEN NULL; END;
END $$;
UPDATE users SET region='america',updated_at=clock_timestamp() WHERE id='geo-test-user-1';
UPDATE realmojis SET replaced_at=clock_timestamp() WHERE id='geo-test-realmoji';
INSERT INTO realmojis(id,post_id,reactor_id,image_id,reaction_type,created_at) VALUES('geo-test-realmoji-replacement','geo-test-post','geo-test-user-1','geo-test-image','smile',clock_timestamp());
DELETE FROM follows WHERE id='geo-test-follow';
INSERT INTO follows(id,follower_id,followee_id) VALUES('geo-test-follow-restored','geo-test-user-1','geo-test-user-2');
DO $$ BEGIN
 BEGIN
 PERFORM * FROM geo.registry_users;
 RAISE EXCEPTION 'app role could access registry';
 EXCEPTION WHEN insufficient_privilege THEN NULL; END;
END $$;
RESET ROLE;
DO $$
DECLARE t record; n integer;
BEGIN
 FOR t IN SELECT * FROM (VALUES
 ('users','id'),('image_objects','owner_user_id'),('login_sessions','user_id'),
 ('friendships','user_low_id'),('follows','follower_id'),('block_relations','blocker_id'),
 ('posts','author_id'),('friend_requests','requester_id'),('realmojis','reactor_id'),
 ('comments','author_id'),('notifications','user_id')) AS owned(tab,col)
 LOOP
   EXECUTE format('SELECT count(*) FROM geo.data_%I WHERE %I=$1 AND placement_region <> $2',t.tab,t.col) INTO n USING 'geo-test-user-1','america';
   IF n<>0 THEN RAISE EXCEPTION 'region move left rows in wrong partition: %',t.tab; END IF;
   EXECUTE format('SELECT count(*) FROM geo.registry_%I WHERE %I=$1 AND geo_region <> $2',t.tab,t.col) INTO n USING 'geo-test-user-1','america';
   IF n<>0 THEN RAISE EXCEPTION 'region move left registry in wrong region: %',t.tab; END IF;
 END LOOP;
 IF (SELECT region FROM users WHERE id='geo-test-user-1') <> 'america' THEN RAISE EXCEPTION 'user region not moved'; END IF;
 IF (SELECT count(*) FROM pg_inherits i JOIN pg_class c ON c.oid=i.inhrelid JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname='geo' AND c.relkind='r')<>45 THEN RAISE EXCEPTION 'expected 45 region partitions'; END IF;
END $$;
SELECT 'PASS: all 15 entities, 45 partitions, defaults, view locks, global ID/username/pair/partial uniqueness, FK restrictions, atomic owner region cascade, app-role isolation' AS result;
ROLLBACK;
