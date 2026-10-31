#!/usr/bin/env python3
"""Compare an unshaped TCP baseline with the same endpoint matrix under netem."""
import argparse
import json
from pathlib import Path


def key(row):
    return row["source"], row["target"], row["port"]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("baseline", type=Path)
    parser.add_argument("delayed", type=Path)
    parser.add_argument("--rtt-ms", type=float, default=100)
    args = parser.parse_args()
    if not 0 < args.rtt_ms <= 10000:
        parser.error("expected added RTT must be greater than 0 and no more than 10000 ms")
    baseline = {key(row): row for row in json.loads(args.baseline.read_text())["measurements"]}
    delayed = {key(row): row for row in json.loads(args.delayed.read_text())["measurements"]}
    if not baseline or baseline.keys() != delayed.keys():
        raise SystemExit("FAIL: endpoint matrices differ or are empty")
    failures = 0
    for pair, row in delayed.items():
        change = row["median_ms"] - baseline[pair]["median_ms"]
        remote = row["source_region"] != row["target_region"]
        ok = (0.6 * args.rtt_ms <= change <= 1.5 * args.rtt_ms + 20) if remote else abs(change) <= max(20, 0.2 * args.rtt_ms)
        print(("PASS" if ok else "FAIL"), pair[0], "->", pair[1], f"added median {change:.2f} ms", "cross-region" if remote else "same-region")
        failures += not ok
    if failures:
        raise SystemExit(f"FAIL: {failures} paths outside broad timing tolerance; inspect CPU load, filters, packet drops and endpoint recreation")
    print("All measured paths match the expected differential latency within tolerance.")


if __name__ == "__main__":
    main()
