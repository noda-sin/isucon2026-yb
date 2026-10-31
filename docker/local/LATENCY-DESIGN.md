# Regional latency design — not activated

Current Compose uses one Docker bridge with no delay injection. It reproduces placement and locality, not WAN timing.

A routed variant can use three non-overlapping regional networks with dedicated routers. Apply Linux tc netem only on cross-region egress, covering YSQL, Master RPC, TServer RPC and replication. Keep same-region paths unchanged. For target RTT100ms, add forward50ms + reverse50ms exactly once per leg; applying at both endpoints and routers doubles the delay.

A router container alone is insufficient: endpoints normally use Docker's gateway. Routes must explicitly traverse the router, including discovered/advertised addresses from yb_servers and peer RPC. No flat bridge or secondary-interface bypass may remain. Avoid host networking or changes to macOS routes. Namespace-level NET_ADMIN, forwarding and tc are additional privileges and have not been activated or granted here.

Verification: record baseline and shaped ICMP/TCP RTT for every region pair; check same-region RTT stays unchanged; inspect route/discovery addresses and qdisc counters for YSQL5433, Master7100 and TServer9100; ensure one shaper per direction; rerun transaction/API tests; remove profile and confirm baseline returns. No measured production latency/bandwidth matrix is available, so delay, jitter, loss and rate parameters must be labeled simulation inputs rather than production facts. Leave bandwidth/loss unset until explicitly specified.
