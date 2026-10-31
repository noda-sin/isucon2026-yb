#!/usr/bin/env python3
"""Regenerate the explicit network/sidecar override; does not contact Docker."""
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
REGIONS = {"asia": 1, "europe": 2, "america": 3}


def generate():
    services = {}
    for region, number in REGIONS.items():
        nodes = {"master-" + region: 10, "tserver-" + region + "-1": 11, "tserver-" + region + "-2": 12,
                 "app-" + region: 20, "app-java-" + region: 21, "web-" + region: 30, "web-java-" + region: 31}
        for target, host in nodes.items():
            source = f"172.29.{number}.{host}"
            services[target] = {"networks": {"netem-lab": {"ipv4_address": source}}}
            if "-java-" in target:
                services[target]["profiles"] = ["java"]
            if target.startswith("web-"):
                continue
            services["netem-" + target] = {
                "profiles": ["netem-java" if target.startswith("app-java-") else "netem"],
                "image": "isureal-local-netem:dev",
                "build": {"context": ".", "dockerfile": "docker/local/netem/Dockerfile"},
                "network_mode": "service:" + target,
                "depends_on": {target: {"condition": "service_started", "restart": True}},
                "cap_drop": ["ALL"], "cap_add": ["NET_ADMIN"],
                "security_opt": ["no-new-privileges:true"],
                "read_only": True, "tmpfs": ["/run:rw,noexec,nosuid,size=8m"],
                "environment": {"NETEM_REGION": region, "NETEM_SOURCE_IP": source,
                                "NETEM_TARGET_SERVICE": target, "NETEM_RTT_MS": "${NETEM_RTT_MS:-100}",
                                "NETEM_APPROVED": "${NETEM_APPROVED:-no}"},
                "restart": "no", "stop_grace_period": "90s", "mem_limit": "64m", "pids_limit": 32,
                "healthcheck": {"test": ["CMD", "python3", "/opt/netem/controller.py", "status"],
                                "interval": "15s", "timeout": "10s", "retries": 3},
            }
    for name in ("db-init", "geo-init", "verify"):
        services[name] = {"networks": {"netem-lab": {}}}
        if name == "geo-init":
            services[name]["profiles"] = ["java"]
        elif name == "verify":
            services[name]["profiles"] = ["test"]
    result = {"services": services,
              "networks": {"netem-lab": {"driver": "bridge", "enable_ipv6": False,
                                        "ipam": {"config": [{"subnet": "172.29.0.0/16", "ip_range": "172.29.0.0/24", "gateway": "172.29.0.1"}]}}}}
    (ROOT / "compose.netem.yaml").write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")


if __name__ == "__main__":
    generate()
