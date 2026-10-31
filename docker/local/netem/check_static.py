#!/usr/bin/env python3
"""Pure file/command-plan validation; never starts containers or runs tc/ip."""
import importlib.util
import ipaddress
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
spec = importlib.util.spec_from_file_location("controller", Path(__file__).with_name("controller.py"))
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)
config = json.loads((ROOT / "compose.netem.yaml").read_text())
services = config["services"]
sidecars = {name: service for name, service in services.items() if name.startswith("netem-")}
assert len(sidecars) == 15
assert config["networks"]["netem-lab"]["enable_ipv6"] is False
seen = set()
for name, service in services.items():
    if name in sidecars:
        target = service["network_mode"].removeprefix("service:")
        assert target in services and service["cap_add"] == ["NET_ADMIN"] and service["cap_drop"] == ["ALL"]
        assert service.get("privileged", False) is False and "volumes" not in service and "networks" not in service
        assert service["security_opt"] == ["no-new-privileges:true"] and service["read_only"] is True
        source = service["environment"]["NETEM_SOURCE_IP"]
        region = service["environment"]["NETEM_REGION"]
        assert source == services[target]["networks"]["netem-lab"]["ipv4_address"]
        commands = module.plan("eth0", region, "100")
        assert commands[1][commands[1].index("delay") + 1] == "50ms"
        assert commands[0][-16:] == ["0"] * 16  # all unclassified priority -> unshaped band
        destinations = [c[c.index("dst") + 1] for c in commands[2:]]
        assert set(destinations) == set(module.REGIONS.values()) - {module.REGIONS[region]}
        assert not any(ipaddress.ip_address(source) in ipaddress.ip_network(cidr) for cidr in destinations)
        assert all("ingress" not in c and "loss" not in c and "rate" not in c for c in commands)
    else:
        assert set(service["networks"]) == {"netem-lab"}
        address = service["networks"]["netem-lab"].get("ipv4_address")
        if address:
            assert address not in seen
            seen.add(address)
for invalid in ("-1", "NaN", "Infinity", "10001"):
    try:
        module.delay(invalid)
        raise AssertionError("accepted invalid RTT")
    except ValueError:
        pass
print("PASS: 15 namespace sidecars, least capability, single bridge, no same-region delay, RTT/2 egress and no ingress duplication")
