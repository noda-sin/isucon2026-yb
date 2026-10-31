#!/usr/bin/env python3
"""Own only this lab's qdisc in the explicitly shared endpoint namespace."""
import argparse
import contextlib
import decimal
import fcntl
import ipaddress
import json
import os
from pathlib import Path
import signal
import subprocess
import threading

REGIONS = {"asia": "172.29.1.0/24", "europe": "172.29.2.0/24", "america": "172.29.3.0/24"}
HANDLE = "7e00:"
STATE = Path("/run/netem-state.json")


def run(args):
    return subprocess.run(args, check=True, capture_output=True, text=True, timeout=10).stdout


def delay(rtt):
    value = decimal.Decimal(str(rtt))
    if not value.is_finite() or not 0 <= value <= 10000:
        raise ValueError("RTT must be a finite number between 0 and 10000 ms")
    return format(value / 2, "f") + "ms"


def plan(interface, region, rtt):
    """Pure command construction; no commands run by static tests."""
    if region not in REGIONS:
        raise ValueError("unknown region")
    commands = [
        ["tc", "qdisc", "add", "dev", interface, "root", "handle", HANDLE, "prio", "bands", "3", "priomap", *(["0"] * 16)],
        ["tc", "qdisc", "add", "dev", interface, "parent", "7e00:3", "handle", "7e30:", "netem", "delay", delay(rtt), "limit", "10000"],
    ]
    for number, destination in enumerate(cidr for name, cidr in REGIONS.items() if name != region):
        commands.append(["tc", "filter", "add", "dev", interface, "protocol", "ip", "parent", HANDLE,
                         "prio", str(10 + number), "u32", "match", "ip", "dst", destination, "flowid", "7e00:3"])
    return commands


def endpoint_interface():
    source = ipaddress.ip_address(os.environ["NETEM_SOURCE_IP"])
    region = os.environ["NETEM_REGION"]
    if source not in ipaddress.ip_network(REGIONS[region]):
        raise RuntimeError("configured source IP is outside its region CIDR")
    interfaces = json.loads(run(["ip", "-json", "-4", "address", "show"]))
    candidates = []
    for interface in interfaces:
        if interface["ifname"] == "lo":
            continue
        ips = [entry["local"] for entry in interface.get("addr_info", []) if entry.get("family") == "inet"]
        if ips:
            candidates.append((interface["ifname"], ips))
    if len(candidates) != 1 or candidates[0][1] != [str(source)]:
        raise RuntimeError("namespace network does not match the single expected lab endpoint; refusing tc changes")
    return candidates[0][0]


def qdiscs(interface):
    return json.loads(run(["tc", "-json", "qdisc", "show", "dev", interface]))


def own_root(interface):
    return any(q.get("root") and q.get("handle") == HANDLE and q.get("kind") == "prio" for q in qdiscs(interface))


def remove_owned(interface):
    if own_root(interface):
        run(["tc", "qdisc", "del", "dev", interface, "root", "handle", HANDLE])


@contextlib.contextmanager
def lock():
    with open("/run/netem.lock", "a", encoding="utf-8") as output:
        fcntl.flock(output, fcntl.LOCK_EX)
        yield


def state(mode, interface, rtt=None):
    data = {"mode": mode, "interface": interface, "region": os.environ["NETEM_REGION"],
            "source_ip": os.environ["NETEM_SOURCE_IP"], "rtt_ms": str(rtt) if rtt is not None else None}
    temp = STATE.with_suffix(".tmp")
    temp.write_text(json.dumps(data) + "\n", encoding="utf-8")
    temp.replace(STATE)
    return data


def enable(rtt):
    delay(rtt)  # Validate before any namespace mutation.
    with lock():
        interface = endpoint_interface()
        for q in qdiscs(interface):
            if q.get("root") and not (q.get("kind") == "noqueue" or (q.get("kind") == "prio" and q.get("handle") == HANDLE)):
                raise RuntimeError("existing root qdisc is not owned by this lab; refusing to overwrite it")
        remove_owned(interface)
        try:
            for command in plan(interface, os.environ["NETEM_REGION"], rtt):
                run(command)
            return state("on", interface, rtt)
        except BaseException:
            remove_owned(interface)
            state("off", interface)
            raise


def disable():
    with lock():
        interface = endpoint_interface()
        remove_owned(interface)
        return state("off", interface)


def status():
    with lock():
        interface = endpoint_interface()
        result = json.loads(STATE.read_text()) if STATE.exists() else {"mode": "unknown"}
        result["qdiscs"] = qdiscs(interface)
        result["filters"] = json.loads(run(["tc", "-json", "filter", "show", "dev", interface, "parent", HANDLE])) if own_root(interface) else []
        if result["mode"] == "on" and not own_root(interface):
            raise RuntimeError("state says on but owned qdisc is absent; rerun on after endpoint recreation")
        if result["mode"] == "off" and own_root(interface):
            raise RuntimeError("state says off but owned qdisc remains")
        return result


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("action", choices=["serve", "on", "off", "status"])
    parser.add_argument("--rtt-ms", default=os.environ.get("NETEM_RTT_MS", "100"))
    args = parser.parse_args()
    if args.action != "status" and os.environ.get("NETEM_APPROVED") != "yes":
        raise SystemExit("NET_ADMIN capability approval is required before running netem; see README.md")
    if args.action == "serve":
        stopped = threading.Event()
        for sig in (signal.SIGTERM, signal.SIGINT):
            signal.signal(sig, lambda *_: stopped.set())
        try:
            print(json.dumps(enable(args.rtt_ms)), flush=True)
            stopped.wait()
        finally:
            # SIGTERM from `compose stop` removes the qdisc before PID 1 exits.
            # SIGKILL/VM loss cannot run handlers; explicit host `off` repairs a
            # surviving endpoint namespace using a fresh cleanup sidecar.
            print(json.dumps(disable()), flush=True)
    else:
        result = enable(args.rtt_ms) if args.action == "on" else disable() if args.action == "off" else status()
        print(json.dumps(result), flush=True)


if __name__ == "__main__":
    main()
