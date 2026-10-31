#!/usr/bin/env python3
"""Host-only explicit control. Never mount the Docker socket into sidecars."""
import argparse
import decimal
import json
import os
from pathlib import Path
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[3]
FILES = ("compose.yaml", "compose.geo.yaml", "compose.java.yaml", "compose.netem.yaml")
REGIONS = ("asia", "europe", "america")


def command(args, env, capture=False):
    result = subprocess.run(args, cwd=ROOT, env=env, check=True, text=True,
                            stdout=subprocess.PIPE if capture else None)
    return result.stdout if capture else None


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=("on", "off", "status", "measure", "config"))
    parser.add_argument("--java", action="store_true", help="also shape all three Java app namespaces")
    parser.add_argument("--rtt-ms", default="100", help="added RTT (each cross-region egress gets half)")
    parser.add_argument("--samples", type=int, default=5)
    parser.add_argument("--keep-probes", action="store_true", help="off: delete qdiscs but retain idle sidecars for baseline measurements")
    parser.add_argument("--out", type=Path, help="write TCP measurements as JSON")
    args = parser.parse_args()
    try:
        rtt = decimal.Decimal(args.rtt_ms)
        if not rtt.is_finite() or not 0 <= rtt <= 10000:
            raise ValueError()
    except (decimal.InvalidOperation, ValueError):
        parser.error("RTT must be finite and between 0 and 10000 ms")
    env = dict(os.environ, NETEM_RTT_MS=args.rtt_ms)
    compose = ["docker", "compose"]
    for name in FILES:
        compose += ["-f", name]
    compose += ["--profile", "netem"]
    if args.java:
        compose += ["--profile", "java", "--profile", "netem-java"]
    if args.action == "config":
        command(compose + ["config", "--quiet"], env)
        return
    if args.action in ("on", "off") and env.get("NETEM_APPROVED") != "yes":
        parser.error("NET_ADMIN approval pending: set NETEM_APPROVED=yes only after the user approves the specific capability")
    running = set(command(compose + ["ps", "--status", "running", "--services"], env, True).split())
    if not args.java and any(name.startswith("app-java-") for name in running):
        parser.error("Java endpoints are running: add --java so they cannot bypass shaping")
    parents = []
    for region in REGIONS:
        parents.extend(["master-" + region, "tserver-" + region + "-1", "tserver-" + region + "-2", "app-" + region])
        if args.java:
            parents.append("app-java-" + region)
    sidecars = ["netem-" + parent for parent in parents]
    if args.action == "on":
        # Network attachment changes require endpoint recreation on the first
        # invocation. Volumes are retained; this is not a live topology edit.
        command(compose + ["up", "-d", "--build"], env)
        for name in sidecars:
            command(compose + ["exec", "-T", name, "python3", "/opt/netem/controller.py", "on", "--rtt-ms", args.rtt_ms], env)
    elif args.action == "off":
        # A forced-killed sidecar may leave its endpoint qdisc. Use a fresh
        # one-shot sidecar to clean that surviving endpoint; never skip it.
        for parent, name in zip(parents, sidecars):
            if parent not in running:
                print("SKIP stopped endpoint", parent)
                continue
            prefix = ["exec", "-T", name] if name in running else ["run", "--rm", "--no-deps", "--entrypoint", "python3", name]
            tail = ["python3", "/opt/netem/controller.py", "off"] if name in running else ["/opt/netem/controller.py", "off"]
            command(compose + prefix + tail, env)
        active = [name for name in sidecars if name in running]
        if active and not args.keep_probes:
            command(compose + ["stop", *active], env)
    elif args.action == "status":
        for name in sidecars:
            if name in running:
                print(name, command(compose + ["exec", "-T", name, "python3", "/opt/netem/controller.py", "status"], env, True).strip())
            else:
                print(name, "not running; use off to clear any surviving namespace qdisc")
    elif args.action == "measure":
        missing = set(sidecars) - running
        if missing:
            parser.error("measurement needs existing sidecars; on then off --keep-probes to leave neutral probes running. Missing: " + ", ".join(sorted(missing)))
        results = []
        for name in sidecars:
            output = command(compose + ["exec", "-T", name, "python3", "/opt/netem/tcp_probe.py", "--samples", str(args.samples)], env, True)
            results.extend(json.loads(output))
        result = {"measurements": results, "description": "TCP connect elapsed time, including both egress legs; no raw sockets"}
        rendered = json.dumps(result, indent=2) + "\n"
        if args.out:
            args.out.write_text(rendered, encoding="utf-8")
        print(rendered, end="")


if __name__ == "__main__":
    main()
