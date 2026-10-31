-- Local Docker topology only. Three TServer pairs, one pair per logical region.
-- Run outside a transaction; tablespaces are cluster-level objects.
SELECT 'CREATE TABLESPACE local_asia WITH (replica_placement=''{"num_replicas":3,"placement_blocks":[{"cloud":"local","region":"asia","zone":"asia","min_num_replicas":2,"leader_preference":1},{"cloud":"local","region":"europe","zone":"europe","min_num_replicas":1,"leader_preference":2}]}'');' WHERE NOT EXISTS (SELECT 1 FROM pg_tablespace WHERE spcname = 'local_asia')
\gexec
SELECT 'CREATE TABLESPACE local_europe WITH (replica_placement=''{"num_replicas":3,"placement_blocks":[{"cloud":"local","region":"europe","zone":"europe","min_num_replicas":2,"leader_preference":1},{"cloud":"local","region":"america","zone":"america","min_num_replicas":1,"leader_preference":2}]}'');' WHERE NOT EXISTS (SELECT 1 FROM pg_tablespace WHERE spcname = 'local_europe')
\gexec
SELECT 'CREATE TABLESPACE local_america WITH (replica_placement=''{"num_replicas":3,"placement_blocks":[{"cloud":"local","region":"america","zone":"america","min_num_replicas":2,"leader_preference":1},{"cloud":"local","region":"asia","zone":"asia","min_num_replicas":1,"leader_preference":2}]}'');' WHERE NOT EXISTS (SELECT 1 FROM pg_tablespace WHERE spcname = 'local_america')
\gexec
SELECT 'CREATE TABLESPACE local_global WITH (replica_placement=''{"num_replicas":3,"placement_blocks":[{"cloud":"local","region":"asia","zone":"asia","min_num_replicas":1},{"cloud":"local","region":"europe","zone":"europe","min_num_replicas":1},{"cloud":"local","region":"america","zone":"america","min_num_replicas":1}]}'');' WHERE NOT EXISTS (SELECT 1 FROM pg_tablespace WHERE spcname = 'local_global')
\gexec
