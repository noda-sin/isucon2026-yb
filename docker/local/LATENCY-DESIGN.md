# Regional latency design

The normal Compose configuration uses a Docker bridge without WAN delay. The optional [netem overlay](netem/README.md) assigns fixed addresses in three regional /24 ranges on one dedicated bridge, and places one shaping sidecar in each DB/app endpoint's network namespace.

The sidecars classify all cross-region IPv4 egress by destination CIDR. YSQL, Master RPC, TServer RPC and replication therefore use the same filters, including addresses discovered by the smart JDBC driver. There is no secondary endpoint network. Same-region traffic and management addresses are unshaped. Unlike a router design, this does not require endpoint route changes or forwarding containers.

Each sidecar has only NET_ADMIN after dropping all capabilities. It has no Docker socket, privileged mode or host networking. The code checks its expected interface/address and owns a dedicated qdisc handle. The capability itself can change other settings in the shared container namespace, so explicit permission is required before activation. The 2026-10-31 local test was explicitly approved for all 15 namespaces and temporary Docker communication interruption.

RTT100ms means forward50ms + reverse50ms, each applied once on egress. This is a simulation input, not a measured competition network value. Bandwidth, loss and jitter remain unset. One Mac does not reproduce independent regional failure domains.

The probe compares unshaped, shaped and recovered TCP connection times from every shaped namespace to all three Master RPC endpoints and all six TServers' YSQL/RPC endpoints. It checks discovered addresses and same-region timing as well. SQL transactions and API response times include additional work and must not be equated with handshake RTT. See the linked runbook for measured results, ON/OFF and exceptional cleanup.
