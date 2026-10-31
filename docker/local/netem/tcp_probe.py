#!/usr/bin/env python3
"""TCP connect RTT proxy; no ICMP/raw sockets or extra capabilities."""
import argparse
import ipaddress
import json
import os
import socket
import statistics
import time


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--samples", type=int, default=5)
    args = parser.parse_args()
    if not 1 <= args.samples <= 100:
        parser.error("samples must be between 1 and 100")
    output = []
    targets = []
    for region in ("asia", "europe", "america"):
        targets.append((region, "master-" + region, 7100))
        for node in (1, 2):
            for port in (5433, 9100):
                targets.append((region, f"tserver-{region}-{node}", port))
    for region, target, port in targets:
        resolved = socket.gethostbyname(target)
        expected = {"asia": "172.29.1.0/24", "europe": "172.29.2.0/24", "america": "172.29.3.0/24"}[region]
        if ipaddress.ip_address(resolved) not in ipaddress.ip_network(expected):
            raise RuntimeError("target resolved outside the shaped region: " + target)
        values = []
        for _ in range(args.samples):
            start = time.perf_counter()
            with socket.create_connection((resolved, port), timeout=15):
                values.append((time.perf_counter() - start) * 1000)
        output.append({"source": os.environ["NETEM_TARGET_SERVICE"], "source_region": os.environ["NETEM_REGION"],
                       "target": target, "target_region": region, "target_ip": resolved, "port": port,
                       "samples_ms": values, "median_ms": statistics.median(values)})
    print(json.dumps(output))


if __name__ == "__main__":
    main()
