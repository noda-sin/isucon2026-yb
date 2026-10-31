#!/usr/bin/env python3
"""Synthetic API smoke only. Creates test users, leaves them for persistence checks.

Only loopback targets are accepted; no official seed or credentials are used.
"""
import argparse
import concurrent.futures
import json
import urllib.error
import urllib.parse
import urllib.request
import uuid


def request(base, method, path, data=None, token=None, expected=200):
    headers = {"Content-Type": "application/json"}
    if token:
        headers["Authorization"] = "Bearer " + token
    req = urllib.request.Request(base + path, data=None if data is None else json.dumps(data).encode(), headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=60) as response:
            code, body = response.status, response.read()
    except urllib.error.HTTPError as exc:
        code, body = exc.code, exc.read()
    assert code == expected, f"{method} {path}: expected {expected}, received {code}"
    return json.loads(body) if body else None


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--asia", default="http://127.0.0.1:18080")
    parser.add_argument("--europe", default="http://127.0.0.1:18081")
    parser.add_argument("--america", default="http://127.0.0.1:18082")
    args = parser.parse_args()
    targets = vars(args)
    for base in targets.values():
        url = urllib.parse.urlparse(base)
        assert url.hostname in ("127.0.0.1", "localhost", "::1") and url.scheme == "http", "local HTTP targets only"
        request(base, "GET", "/healthz")
        request(base, "POST", "/api/initialize", {}, expected=503)
    for region, base in targets.items():
        username = "smoke_" + uuid.uuid4().hex
        registration = {"username": username, "displayName": "Synthetic smoke", "region": region}
        user = request(base, "POST", "/api/auth/register", registration, expected=201)
        session = request(base, "POST", "/api/auth/login", {"username": username, "secretKey": user["secretKey"]})
        token = session["token"]
        current = request(base, "GET", "/api/me", token=token)
        assert current["id"] == user["user"]["id"] and current["region"] == region
        destination = next(r for r in targets if r != region)
        remote = targets[destination]
        conflict = request(remote, "POST", "/api/auth/register", {**registration, "region": destination}, expected=409)
        assert conflict["error"] == "username_taken"
        request(remote, "GET", "/api/me", token=token, expected=403)
        moved = request(base, "PATCH", "/api/me", {"region": destination}, token)
        assert moved["id"] == current["id"] and moved["region"] == destination
        request(base, "GET", "/api/me", token=token, expected=403)
        current = request(remote, "GET", "/api/me", token=token)
        assert current["id"] == moved["id"]
        request(remote, "POST", "/api/auth/logout", {}, token, expected=204)
        request(remote, "GET", "/api/me", token=token, expected=401)
        print(f"PASS {region}: registration, login, global username, region routing, move, logout")

    # Race the original table-lock-based registration across regions.
    username = "smoke_race_" + uuid.uuid4().hex
    def register(item):
        region, base = item
        try:
            request(base, "POST", "/api/auth/register", {"username": username, "displayName": "Synthetic race", "region": region}, expected=201)
            return "created"
        except AssertionError as exc:
            assert "received 409" in str(exc), str(exc)
            return "conflict"
    with concurrent.futures.ThreadPoolExecutor(max_workers=3) as pool:
        results = list(pool.map(register, targets.items()))
    assert results.count("created") == 1 and results.count("conflict") == 2, results
    print("PASS concurrent global username registration: one created, two conflicts")
    print("Synthetic users retained. No credentials printed; official seed was not used.")


if __name__ == "__main__":
    main()
