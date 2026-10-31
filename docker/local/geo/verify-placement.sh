#!/usr/bin/env bash
# Run inside a YugabyteDB image on the Compose network, after db-init succeeds.
set -euo pipefail
YB_ADMIN=${YB_ADMIN:-/home/yugabyte/bin/yb-admin}
YSQLSH=${YSQLSH:-/home/yugabyte/bin/ysqlsh}
MASTERS=${MASTERS:-master-asia:7100,master-europe:7100,master-america:7100}
PGHOST=${PGHOST:-tserver-asia-1}
export PGHOST PGPORT=5433 PGUSER=yugabyte PGDATABASE=isureal_java
scratch=$(mktemp -d)
trap 'rm -rf "$scratch"' EXIT

# Wait for actual replica/leader convergence, not just tablespace DDL metadata.
check_placement() {
  "$YSQLSH" -X -A -t -F ' ' -v ON_ERROR_STOP=1 -c 'SELECT uuid, region FROM yb_servers()' > "$scratch/nodes" || return 1
  "$YSQLSH" -X -At -F ' ' -v ON_ERROR_STOP=1 -c "SELECT c.relname,replace(t.spcname,'local_','') FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace JOIN pg_tablespace t ON t.oid=c.reltablespace WHERE n.nspname='geo' AND t.spcname IN ('local_asia','local_europe','local_america') AND (c.relkind='r' OR (c.relkind='i' AND NOT EXISTS(SELECT 1 FROM pg_index i WHERE i.indexrelid=c.oid AND i.indisprimary))) ORDER BY c.relname" > "$scratch/objects" || return 1
  if [[ $(wc -l < "$scratch/objects") -ne 102 ]]; then echo 'Expected 45 regional partitions and 57 secondary indexes' >&2; return 1; fi
  while read -r table_name local_region; do
    case "$local_region" in asia) remote_region=europe;; europe) remote_region=america;; america) remote_region=asia;; esac
    "$YB_ADMIN" --master_addresses "$MASTERS" list_tablets ysql.isureal_java "$table_name" 0 > "$scratch/tablets" || return 1
    awk 'length($1)==32 && $1 !~ /[^0-9a-f]/ {print $1}' "$scratch/tablets" > "$scratch/ids"
    if [[ ! -s "$scratch/ids" ]]; then echo "No tablets for $table_name" >&2; return 1; fi
    while read -r tablet; do
      "$YB_ADMIN" --master_addresses "$MASTERS" list_tablet_servers "$tablet" > "$scratch/peers" || return 1
      if ! awk -v local_region="$local_region" -v remote_region="$remote_region" -v tablet="$tablet" '
        NR==FNR {region[$1]=$2; next}
        length($1)==32 && $1 !~ /[^0-9a-f]/ {
          total++; r=region[$1]; count[r]++;
          for(i=2;i<=NF;i++) { if($i=="LEADER") {leaders++; voters++; leader_region=r}; if($i=="FOLLOWER") voters++ }
        }
        END {
          if(total!=3 || voters!=3 || count[local_region]!=2 || count[remote_region]!=1 || leaders!=1 || leader_region!=local_region) {
            printf "Not converged tablet=%s replicas=%d local=%d remote=%d leaders=%d leader_region=%s\n",tablet,total,count[local_region],count[remote_region],leaders,leader_region > "/dev/stderr"; exit 1
          }
          printf "PASS tablet=%s local=%s:2 remote=%s:1 leader=%s\n",tablet,local_region,remote_region,leader_region
        }' "$scratch/nodes" "$scratch/peers"; then return 1; fi
    done < "$scratch/ids"
  done < "$scratch/objects"
}
for attempt in $(seq 1 10); do
  if check_placement; then echo 'All application regional tablets and secondary indexes verified (RF3, local2+remote1, local leader).'; exit 0; fi
  if [[ "$attempt" != 10 ]]; then sleep 2; fi
done
echo 'FAIL: replica placement did not converge within the bounded retry window.' >&2
exit 1
