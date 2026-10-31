#!/usr/bin/env python3
"""Local synthetic black-box parity suite for Go and Java; Python stdlib only.

Writes normalized observations and a route coverage table. Never follows HTTP
redirects or accepts a non-loopback endpoint. Tests mutate only synthetic API
accounts and advance posting windows; use isolated smoke-seeded databases.
"""
import argparse
import base64
import datetime as dt
import hashlib
import hmac
import io
import json
import pathlib
import re
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
import zipfile

ROOT = pathlib.Path(__file__).resolve().parents[3]
HERE = pathlib.Path(__file__).resolve().parent
REGIONS = ("asia", "europe", "america")
NIL = "ffffffff-ffff-4fff-8fff-ffffffffffff"
ROUTES = re.findall(r'e\.(GET|POST|PUT|PATCH|DELETE)\("(/api/[^\"]+)"', (ROOT / "webapp/go/server.go").read_text())
ROUTES.sort(key=lambda r: (r[1].count(":"), r))
UUID_RE = re.compile(r"^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
STAMP_RE = re.compile(r"^\d{4}-\d{2}-\d{2}T")
IMAGE = (HERE / "contract-fixture.jpg").read_bytes()

class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *args, **kwargs):
        return None

OPENER = urllib.request.build_opener(NoRedirect)

def transport(base, method, path, body=None, token=None, headers=None, stream=False):
    parsed = urllib.parse.urlsplit(base)
    assert parsed.scheme == "http" and parsed.hostname in ("localhost", "127.0.0.1", "::1"), "loopback HTTP endpoints only"
    assert path.startswith("/") and not path.startswith("//"), "relative API paths only"
    hdr = dict(headers or {})
    if token is not None:
        hdr["Authorization"] = "Bearer " + token
    if isinstance(body, (dict, list)):
        body = json.dumps(body, ensure_ascii=False).encode()
        hdr.setdefault("Content-Type", "application/json")
    req = urllib.request.Request(base + path, method=method, data=body, headers=hdr)
    try:
        resp = OPENER.open(req, timeout=35)
    except urllib.error.HTTPError as exc:
        resp = exc
    if stream:
        return resp
    with resp:
        return resp.status, dict(resp.headers.items()), resp.read()

def multipart(fields, image=IMAGE):
    boundary = "contract-boundary-fixed-16384"
    pieces = []
    for key, value in fields.items():
        pieces.append((f'--{boundary}\r\nContent-Disposition: form-data; name="{key}"\r\n\r\n{value}\r\n').encode())
    pieces += [(f'--{boundary}\r\nContent-Disposition: form-data; name="image"; filename="synthetic.jpg"\r\nContent-Type: image/jpeg\r\n\r\n').encode(), image, f'\r\n--{boundary}--\r\n'.encode()]
    return b"".join(pieces), {"Content-Type": "multipart/form-data; boundary=" + boundary}

def instant(value):
    # datetime handles differing RFC3339 subsecond precision without dropping the
    # semantic relationship tests on deadlines and signed URL validity.
    value = re.sub(r"\.(\d+)", lambda m: "." + (m.group(1) + "000000")[:6], value)
    return dt.datetime.fromisoformat(value.replace("Z", "+00:00"))

class Suite:
    def __init__(self, label, bases, run_id):
        self.label, self.bases = label, bases
        self.prefix = "ct_" + run_id + "_" + label + "_"
        self.ids, self.actors, self.image_keys = {}, {}, {}
        self.observations, self.coverage = [], {}
        self.admin = None

    def alias(self, raw, name):
        assert UUID_RE.fullmatch(raw), f"{name} is not a UUID"
        previous = self.ids.get(raw)
        assert previous in (None, name), f"ID unexpectedly reused: {previous}, {name}"
        self.ids[raw] = name
        return raw

    def normalize(self, value):
        if isinstance(value, dict):
            return {key: self.normalize(val) for key, val in sorted(value.items())}
        if isinstance(value, list):
            return [self.normalize(v) for v in value]
        if isinstance(value, str):
            if value.startswith(self.prefix):
                return "ct_<run>_<implementation>_" + value[len(self.prefix):]
            if UUID_RE.fullmatch(value):
                if value not in self.ids:
                    self.ids[value] = "generated-id-" + str(len(self.ids))
                return "<" + self.ids[value] + ">"
            if STAMP_RE.match(value):
                instant(value)  # Reject malformed timestamps instead of hiding them.
                return "<RFC3339 timestamp>"
            if value.startswith("/api/images/"):
                parts = urllib.parse.urlsplit(value)
                query = urllib.parse.parse_qs(parts.query, keep_blank_values=True)
                assert set(query) == {"exp", "sig"} and all(len(v) == 1 for v in query.values()), "image URL query differs"
                image_id = parts.path.rsplit("/", 1)[1]
                exp, sig = query["exp"][0], query["sig"][0]
                assert exp == str(int(exp)) and 86000 <= int(exp) - time.time() <= 86500, "signed URL lifetime must be 24h"
                decoded = base64.urlsafe_b64decode(sig + "=" * (-len(sig) % 4))
                assert len(decoded) == 32 and base64.urlsafe_b64encode(decoded).decode().rstrip("=") == sig, "noncanonical signature"
                return "/api/images/" + self.normalize(image_id) + "?exp=<24h-expiry>&sig=<32-byte-signature>"
        return value

    def record(self, name, method, path, status, headers, body):
        route_path = urllib.parse.urlsplit(path).path
        route = next((r for r in ROUTES if r[0] == method and re.fullmatch(re.sub(r":[^/]+", "[^/]+", r[1]), route_path)), None)
        if route:
            self.coverage.setdefault(" ".join(route), []).append({"case": name, "status": status})
        lower = {k.lower(): v for k, v in headers.items()}
        selected = {key: lower[key].split(";", 1)[0].lower() if key == "content-type" else lower[key]
                    for key in ("content-type", "cache-control", "content-disposition") if key in lower}
        normalized = self.normalize(body)
        self.observations.append({"case": name, "status": status, "headers": selected, "body": normalized})
        return body

    def call(self, name, method, path, body=None, actor=None, region="asia", expected=200, error=None, headers=None, token=None):
        if actor:
            token = self.actors[actor]["token"]
        status, hdr, raw = transport(self.bases[region], method, path, body, token, headers)
        content_type = next((v for k, v in hdr.items() if k.lower() == "content-type"), "")
        if raw and "json" in content_type:
            decoded = json.loads(raw)
        elif raw:
            decoded = {"binaryLength": len(raw), "sha256": hashlib.sha256(raw).hexdigest()}
        else:
            decoded = None
        result = self.record(name, method, path, status, hdr, decoded)
        assert status == expected, f"{name}: expected {expected}, received {status}, body={decoded}"
        if error:
            assert decoded == {"error": error}, f"{name}: wrong error contract {decoded}"
        return result

    def register(self, who, region):
        # Bind creation IDs before recording so later references cannot be
        # normalized independently and conceal wrong-author/wrong-target bugs.
        body = {"username": self.prefix + who, "displayName": "Contract " + who, "region": region}
        status, hdr, raw = transport(self.bases[region], "POST", "/api/auth/register", body)
        result = json.loads(raw)
        assert status == 201, ("register", who, status, result)
        self.alias(result["user"]["id"], "user:" + who)
        self.alias(result["secretKey"], "secret:" + who)
        self.record("register " + who, "POST", "/api/auth/register", status, hdr, result)
        session_status, hdr, raw = transport(self.bases[region], "POST", "/api/auth/login", {"username": body["username"], "secretKey": result["secretKey"]})
        session = json.loads(raw)
        assert session_status == 200 and session["user"] == result["user"]
        self.alias(session["token"], "token:" + who)
        self.record("login " + who, "POST", "/api/auth/login", session_status, hdr, session)
        self.actors[who] = {"id": result["user"]["id"], "username": body["username"], "secret": result["secretKey"], "token": session["token"], "region": region}

    def image(self, name, image_url, owner, region="asia", expected=200):
        parts = urllib.parse.urlsplit(image_url)
        image_id = parts.path.rsplit("/", 1)[1]
        params = urllib.parse.parse_qs(parts.query)
        exp = int(params["exp"][0])
        key = hashlib.sha256(self.actors[owner]["secret"].encode()).digest()
        signature = f"{exp}:{image_id}".encode()
        for _ in range(1000):
            signature = hmac.new(key, signature, hashlib.sha256).digest()
        assert hmac.compare_digest(base64.urlsafe_b64encode(signature).decode().rstrip("="), params["sig"][0]), "image signature algorithm mismatch"
        result = self.call(name, "GET", image_url, region=region, expected=expected, error="image_forbidden" if expected == 403 else None)
        if expected == 200:
            assert result == {"binaryLength": len(IMAGE), "sha256": hashlib.sha256(IMAGE).hexdigest()}, "image bytes modified"
        return result

    def window(self, region, date, suffix=""):
        result = self.call("open " + region + suffix, "POST", "/api/admin/posting-window", {"region": region, "localDate": date, "onTimeSeconds": 3600}, token=self.admin, expected=201)
        assert result["region"] == region and result["localDate"] == date
        assert (instant(result["onTimeDeadline"]) - instant(result["startsAt"])).total_seconds() == 3600
        return result

    def post(self, who, visibility="public", suffix="", location=None):
        fields = {"caption": "Synthetic " + who + suffix, "visibility": visibility, "location_kind": "none"}
        if location:
            fields.update(location)
        body, headers = multipart(fields)
        result = self.call("post " + who + suffix, "POST", "/api/posts", body, actor=who, region=self.actors[who]["region"], headers=headers, expected=201)
        assert result["author"]["id"] == self.actors[who]["id"]
        assert result["onTime"] is True and result["lateSeconds"] == 0
        return result

    def setup(self):
        for region in REGIONS:
            self.call("health " + region, "GET", "/healthz", region=region)
            self.call("initialize disabled " + region, "POST", "/api/initialize", {}, region=region, expected=503, error="local_initialize_disabled")
        # Every protected route is exercised without credentials, independently
        # of the happy flow. Successful route coverage is reported separately.
        public = {"/api/initialize", "/api/auth/register", "/api/auth/login", "/api/admin/auth/login", "/api/images/:imageId"}
        for method, route in ROUTES:
            if route in public:
                continue
            path = re.sub(r":[^/]+", NIL, route)
            code = "invalid_administrator_session" if route.startswith("/api/admin/") else "invalid_session"
            self.call("unauthenticated " + method + " " + route, method, path, {} if method in ("POST", "PUT", "PATCH") else None, expected=401, error=code)
        self.call("invalid registration", "POST", "/api/auth/register", {"username": "INVALID NAME", "displayName": "x", "region": "asia"}, expected=400, error="invalid_registration")
        self.call("invalid login", "POST", "/api/auth/login", {"username": "INVALID", "secretKey": "x"}, expected=401, error="invalid_credentials")
        self.call("invalid admin login", "POST", "/api/admin/auth/login", {"username": "contract_admin", "password": "wrong"}, expected=401, error="invalid_credentials")
        admin = self.call("admin login", "POST", "/api/admin/auth/login", {"username": "contract_admin", "password": "local-contract-admin"})
        self.admin = admin["token"]
        for who, region in (("a", "asia"), ("b", "asia"), ("c", "asia"), ("e", "europe"), ("m", "america"), ("d", "asia")):
            self.register(who, region)
        dates = []
        for who in ("a", "e", "m"):
            region = self.actors[who]["region"]
            # Setup state depends on previous runs, so it is inspected but not
            # compared. All subsequent scenario windows use the common date.
            status, _, raw = transport(self.bases[region], "GET", "/api/posting-window/current", token=self.actors[who]["token"])
            assert status == 200, ("preflight posting window", status, raw)
            current = json.loads(raw)
            if current["window"]:
                dates.append(dt.date.fromisoformat(current["window"]["localDate"]))
        return max(dates, default=dt.datetime.now(dt.timezone.utc).date())

    def scenario(self, date):
        a, b, c, e = (self.actors[x]["id"] for x in ("a", "b", "c", "e"))
        for region in REGIONS:
            self.window(region, date)
        self.call("duplicate global username", "POST", "/api/auth/register", {"username": self.actors["a"]["username"], "displayName": "Duplicate", "region": "europe"}, region="europe", expected=409, error="username_taken")
        self.call("wrong region", "GET", "/api/me", actor="a", region="europe", expected=403, error="region_mismatch")
        self.call("invalid bearer format", "GET", "/api/me", headers={"Authorization": "Basic invalid"}, expected=401, error="invalid_session")
        self.call("profile invalid", "PATCH", "/api/me", {}, actor="a", expected=400, error="invalid_profile")
        self.call("JSON wrong content type rejected", "PATCH", "/api/me", b'{"bio":"must not be applied"}', actor="a", headers={"Content-Type": "text/plain"}, expected=415)
        for missing_bytes in (1, 2, 10, 50, 100):
            self.call(f"profile truncated JPEG minus {missing_bytes}", "PUT", "/api/me/profile-image", IMAGE[:-missing_bytes], actor="a", headers={"Content-Type": "image/jpeg"}, expected=400, error="invalid_profile_image")
        self.call("profile unicode", "PATCH", "/api/me", {"displayName": self.prefix + " École", "bio": "private biography"}, actor="a")
        self.call("me", "GET", "/api/me", actor="a")
        self.call("profile wrong media", "PUT", "/api/me/profile-image", b"x", actor="a", headers={"Content-Type": "text/plain"}, expected=415, error="unsupported_media_type")
        self.call("profile malformed jpeg", "PUT", "/api/me/profile-image", b"not jpeg", actor="a", headers={"Content-Type": "image/jpeg"}, expected=400, error="invalid_profile_image")
        self.call("profile upload", "PUT", "/api/me/profile-image", IMAGE, actor="a", headers={"Content-Type": "image/jpeg"}, expected=204)
        me = self.call("profile visible", "GET", "/api/me", actor="a")
        image_url = me["profileImageUrl"]
        self.image("profile signed image", image_url, "a")
        self.image("profile signed image cross-region", image_url, "a", "europe")
        self.call("image unsigned", "GET", image_url.split("?")[0], expected=403, error="image_forbidden")
        self.call("image duplicate expiration", "GET", image_url + "&exp=1", expected=403, error="image_forbidden")
        parts = urllib.parse.urlsplit(image_url)
        query = urllib.parse.parse_qs(parts.query)
        self.call("image expired", "GET", parts.path + "?exp=1&sig=" + query["sig"][0], expected=403, error="image_forbidden")
        self.call("image bad signature", "GET", parts.path + "?exp=" + query["exp"][0] + "&sig=" + "A" * 43, expected=403, error="image_forbidden")
        self.call("profile delete", "DELETE", "/api/me/profile-image", actor="a", expected=204)
        self.image("deleted profile image denied", image_url, "a", expected=403)
        self.call("profile delete missing", "DELETE", "/api/me/profile-image", actor="a", expected=404, error="account_not_found")
        self.call("user search", "GET", "/api/users?q=" + urllib.parse.quote(self.prefix), actor="a")
        self.call("user search unicode fold", "GET", "/api/users?q=" + urllib.parse.quote(self.prefix + " ÉCOLE"), actor="b")
        self.call("user search invalid pagination", "GET", "/api/users?q=x&beforeId=" + NIL, actor="a", expected=400, error="invalid_pagination")
        self.call("user before friendship private bio", "GET", "/api/users/" + a, actor="b")
        self.call("unknown user", "GET", "/api/users/" + NIL, actor="a", expected=404, error="relationship_not_found")
        self.call("current window empty", "GET", "/api/posting-window/current", actor="a")
        self.call("current post absent", "GET", "/api/posts/me/current", actor="a", expected=404, error="post_not_found")
        body, hdr = multipart({"caption": "invalid", "visibility": "secret", "location_kind": "none"})
        self.call("invalid post", "POST", "/api/posts", body, actor="a", headers=hdr, expected=400, error="invalid_post")
        for missing_bytes in (1, 2, 10, 50, 100):
            damaged, damaged_headers = multipart({"caption": "invalid truncated", "visibility": "public", "location_kind": "none"}, image=IMAGE[:-missing_bytes])
            self.call(f"post truncated JPEG minus {missing_bytes}", "POST", "/api/posts", damaged, actor="a", headers=damaged_headers, expected=400, error="invalid_post")
        pa = self.post("a", location={"location_kind": "exact", "latitude": "35.5", "longitude": "139.25"})
        pid = pa["id"]
        self.image("post signed bytes", pa["imageUrl"], "a")
        self.call("not posted cannot view", "GET", "/api/posts/" + pid, actor="b", expected=404, error="post_not_found")
        pb = self.post("b", "friends", location={"location_kind": "approximate", "locality": "Tokyo"})
        pc = self.post("c", "friends")
        self.post("e")
        self.post("m")
        self.call("current post", "GET", "/api/posts/me/current", actor="a")
        self.call("current window occupied", "GET", "/api/posting-window/current", actor="a")
        self.call("public post visible", "GET", "/api/posts/" + pid, actor="b")
        self.call("friend post hidden", "GET", "/api/posts/" + pb["id"], actor="a", expected=404, error="post_not_found")
        self.call("post edit invalid", "PATCH", "/api/posts/" + pid, {}, actor="a", expected=400, error="invalid_post")
        self.call("post edit nonowner", "PATCH", "/api/posts/" + pid, {"caption": "no"}, actor="b", expected=403, error="post_not_editable")
        self.call("post edit", "PATCH", "/api/posts/" + pid, {"caption": "edited", "visibility": "public"}, actor="a")
        self.call("post delete nonowner", "DELETE", "/api/posts/" + pid, actor="b", expected=403, error="post_not_editable")
        self.call("friend self forbidden", "POST", "/api/friend-requests", {"recipientId": a}, actor="a", expected=400, error="invalid_relationship")
        req = self.call("request a to b", "POST", "/api/friend-requests", {"recipientId": b}, actor="a", expected=201)
        rid = req["relationId"]
        self.call("duplicate friend request", "POST", "/api/friend-requests", {"recipientId": b}, actor="a", expected=409, error="relationship_conflict")
        self.call("outgoing requests", "GET", "/api/relationships/outgoing-requests", actor="a")
        self.call("incoming requests", "GET", "/api/relationships/incoming-requests", actor="b")
        self.call("request wrong actor", "POST", "/api/friend-requests/" + rid + "/accept", {}, actor="a", expected=403, error="relationship_action_forbidden")
        self.call("accept request", "POST", "/api/friend-requests/" + rid + "/accept", {}, actor="b")
        self.call("repeat accept", "POST", "/api/friend-requests/" + rid + "/accept", {}, actor="b", expected=403, error="relationship_action_forbidden")
        self.call("friends", "GET", "/api/relationships/friends", actor="a")
        self.call("friend bio visible", "GET", "/api/users/" + a, actor="b")
        self.call("friend post visible", "GET", "/api/posts/" + pb["id"], actor="a")
        req2 = self.call("request b to c", "POST", "/api/friend-requests", {"recipientId": c}, actor="b", expected=201)
        self.call("accept b c", "POST", "/api/friend-requests/" + req2["relationId"] + "/accept", {}, actor="c")
        self.call("fof post edit", "PATCH", "/api/posts/" + pc["id"], {"visibility": "friends_of_friends"}, actor="c")
        self.call("fof post visible", "GET", "/api/posts/" + pc["id"], actor="a")
        self.call("fof comment denied", "POST", "/api/posts/" + pc["id"] + "/comments", {"body": "no"}, actor="a", expected=403, error="interaction_forbidden")
        for feed in ("friends", "friends-of-friends", "public", "following"):
            self.call("feed " + feed, "GET", "/api/feeds/" + feed, actor="a")
        self.call("feed invalid", "GET", "/api/feeds/invalid", actor="a", expected=400, error="invalid_post")
        self.call("feed pagination invalid", "GET", "/api/feeds/public?beforeId=" + NIL, actor="a", expected=400, error="invalid_pagination")
        comment = self.call("comment create", "POST", "/api/posts/" + pid + "/comments", {"body": "Hello 日本語"}, actor="b", expected=201)
        self.call("comment empty", "POST", "/api/posts/" + pid + "/comments", {"body": ""}, actor="b", expected=400, error="invalid_interaction")
        self.call("comments list", "GET", "/api/posts/" + pid + "/comments", actor="a")
        cursor = urllib.parse.urlencode({"afterCreatedAt": comment["createdAt"], "afterId": comment["id"]})
        self.call("comments cursor", "GET", "/api/posts/" + pid + "/comments?" + cursor, actor="a")
        self.call("comment delete third-party denied", "DELETE", "/api/comments/" + comment["id"], actor="c", expected=403, error="interaction_forbidden")
        body, hdr = multipart({"reactionType": "heart"})
        self.call("self reaction denied", "POST", "/api/posts/" + pid + "/realmojis", body, actor="a", headers=hdr, expected=403, error="interaction_forbidden")
        for missing_bytes in (1, 2, 10, 50, 100):
            damaged, damaged_headers = multipart({"reactionType": "heart"}, image=IMAGE[:-missing_bytes])
            self.call(f"realmoji truncated JPEG minus {missing_bytes}", "POST", "/api/posts/" + pid + "/realmojis", damaged, actor="b", headers=damaged_headers, expected=400, error="invalid_realmoji")
        reaction = self.call("reaction create", "POST", "/api/posts/" + pid + "/realmojis", body, actor="b", headers=hdr, expected=201)
        self.image("reaction signed bytes", reaction["imageUrl"], "b")
        self.call("reactions list", "GET", "/api/posts/" + pid + "/realmojis", actor="a")
        replaced = self.call("reaction replace", "POST", "/api/posts/" + pid + "/realmojis", body, actor="b", headers=hdr, expected=201)
        assert replaced["id"] != reaction["id"]
        self.image("replaced reaction image denied", reaction["imageUrl"], "b", expected=403)
        self.call("reactions replacement list", "GET", "/api/posts/" + pid + "/realmojis", actor="a")
        self.call("reaction delete third-party denied", "DELETE", "/api/realmojis/" + replaced["id"], actor="c", expected=403, error="interaction_forbidden")
        self.call("memories month", "GET", "/api/memories?month=" + date[:7], actor="a")
        self.call("memories date", "GET", "/api/memories/" + date, actor="a")
        self.call("memories invalid month", "GET", "/api/memories?month=invalid", actor="a", expected=400, error="invalid_memory_period")
        self.call("memories invalid date", "GET", "/api/memories/invalid", actor="a", expected=400, error="invalid_memory_period")
        self.export("a", pid)
        self.call("comment delete by post author", "DELETE", "/api/comments/" + comment["id"], actor="a", expected=204)
        self.call("comment delete missing", "DELETE", "/api/comments/" + comment["id"], actor="a", expected=404, error="interaction_not_found")
        self.call("reaction delete by post author", "DELETE", "/api/realmojis/" + replaced["id"], actor="a", expected=204)
        self.image("deleted reaction image denied", replaced["imageUrl"], "b", expected=403)
        self.call("certify", "PUT", "/api/admin/users/" + c + "/certification", {}, token=self.admin, expected=204)
        self.call("certify duplicate", "PUT", "/api/admin/users/" + c + "/certification", {}, token=self.admin, expected=409, error="certification_conflict")
        self.call("certification irreversible", "DELETE", "/api/admin/users/" + c + "/certification", token=self.admin, expected=409, error="certification_irreversible")
        self.call("follow regular denied", "PUT", "/api/follows/" + b, {}, actor="a", expected=409, error="relationship_conflict")
        self.call("follow certified", "PUT", "/api/follows/" + c, {}, actor="a", expected=204)
        self.call("follow duplicate", "PUT", "/api/follows/" + c, {}, actor="a", expected=409, error="relationship_conflict")
        self.call("following list", "GET", "/api/relationships/following", actor="a")
        self.call("followers list", "GET", "/api/relationships/followers", actor="c")
        self.call("following feed populated", "GET", "/api/feeds/following", actor="a")
        self.call("unfollow", "DELETE", "/api/follows/" + c, actor="a", expected=204)
        self.call("unfollow duplicate", "DELETE", "/api/follows/" + c, actor="a", expected=409, error="relationship_conflict")
        self.call("relationship invalid kind", "GET", "/api/relationships/invalid", actor="a", expected=400, error="invalid_relationship")
        self.call("relationship invalid cursor", "GET", "/api/relationships/friends?beforeId=" + NIL, actor="a", expected=400, error="invalid_pagination")
        self.call("unfriend", "DELETE", "/api/friendships/" + b, actor="a", expected=204)
        self.call("unfriend duplicate", "DELETE", "/api/friendships/" + b, actor="a", expected=409, error="relationship_conflict")
        req3 = self.call("request for reject", "POST", "/api/friend-requests", {"recipientId": b}, actor="a", expected=201)
        self.call("reject request", "POST", "/api/friend-requests/" + req3["relationId"] + "/reject", {}, actor="b")
        req4 = self.call("request for cancel", "POST", "/api/friend-requests", {"recipientId": b}, actor="a", expected=201)
        self.call("cancel request", "POST", "/api/friend-requests/" + req4["relationId"] + "/cancel", {}, actor="a")
        self.call("block", "PUT", "/api/blocks/" + b, {}, actor="a", expected=204)
        self.call("block duplicate", "PUT", "/api/blocks/" + b, {}, actor="a", expected=409, error="relationship_conflict")
        self.call("blocked user hidden", "GET", "/api/users/" + b, actor="a", expected=404, error="relationship_not_found")
        self.call("blocked post hidden", "GET", "/api/posts/" + pid, actor="b", expected=404, error="post_not_found")
        self.call("blocked request denied", "POST", "/api/friend-requests", {"recipientId": b}, actor="a", expected=409, error="relationship_conflict")
        self.call("unblock", "DELETE", "/api/blocks/" + b, actor="a", expected=204)
        self.call("unblock duplicate", "DELETE", "/api/blocks/" + b, actor="a", expected=403, error="relationship_action_forbidden")
        # Move an author after posts, image, and sessions already exist. This
        # catches stale routing and a common regional-schema FK migration bug.
        self.call("move author to europe", "PATCH", "/api/me", {"region": "europe"}, actor="a")
        self.actors["a"]["region"] = "europe"
        self.call("old region now denied", "GET", "/api/me", actor="a", expected=403, error="region_mismatch")
        self.call("new region session preserved", "GET", "/api/me", actor="a", region="europe")
        self.call("moved author post preserved", "GET", "/api/posts/" + pid, actor="a", region="europe")
        self.image("moved author image preserved", pa["imageUrl"], "a", "europe")
        self.call("moved author current slot preserved", "GET", "/api/posting-window/current", actor="a", region="europe")
        self.call("move author back", "PATCH", "/api/me", {"region": "asia"}, actor="a", region="europe")
        self.actors["a"]["region"] = "asia"
        self.call("delete first post b", "DELETE", "/api/posts/" + pb["id"], actor="b", expected=204)
        self.image("deleted post image denied", pb["imageUrl"], "b", expected=403)
        pb2 = self.post("b", suffix=" replacement")
        self.call("delete second post b", "DELETE", "/api/posts/" + pb2["id"], actor="b", expected=204)
        body, hdr = multipart({"caption": "third", "visibility": "public", "location_kind": "none"})
        self.call("daily deletion quota", "POST", "/api/posts", body, actor="b", headers=hdr, expected=409, error="post_slot_unavailable")
        self.call("deletion quota current window", "GET", "/api/posting-window/current", actor="b")
        self.call("window invalid", "POST", "/api/admin/posting-window", {"region": "moon", "localDate": date}, token=self.admin, expected=400, error="invalid_posting_window")
        self.call("window date conflict", "POST", "/api/admin/posting-window", {"region": "asia", "localDate": date}, token=self.admin, expected=409, error="posting_window_conflict")
        self.sse(date)
        self.call("ended window post owner readable", "GET", "/api/posts/" + pid, actor="a")
        self.call("ended window post edit forbidden", "PATCH", "/api/posts/" + pid, {"caption": "too late"}, actor="a", expected=403, error="post_not_editable")
        self.call("delete account", "POST", "/api/me/account-deletion", {}, actor="d", expected=204)
        self.call("deleted account session denied", "GET", "/api/me", actor="d", expected=401, error="invalid_session")
        self.call("deleted account login denied", "POST", "/api/auth/login", {"username": self.actors["d"]["username"], "secretKey": self.actors["d"]["secret"]}, expected=401, error="invalid_credentials")
        self.call("logout", "POST", "/api/auth/logout", {}, actor="a", expected=204)
        self.call("logged out session denied", "GET", "/api/me", actor="a", expected=401, error="invalid_session")
        self.call("admin logout", "POST", "/api/admin/auth/logout", {}, token=self.admin, expected=204)
        self.call("logged out admin denied", "POST", "/api/admin/auth/logout", {}, token=self.admin, expected=401, error="invalid_administrator_session")

    def export(self, who, expected_post_id):
        status, hdr, raw = transport(self.bases["asia"], "GET", "/api/me/export", token=self.actors[who]["token"])
        assert status == 200, ("export", status)
        with zipfile.ZipFile(io.BytesIO(raw)) as archive:
            assert archive.testzip() is None
            assert sorted(archive.namelist()) == ["images/000001.jpg", "posts.json"]
            posts = json.loads(archive.read("posts.json"))
            assert len(posts) == 1 and posts[0]["id"] == expected_post_id
            assert posts[0]["imagePath"] == "images/000001.jpg"
            assert archive.read(posts[0]["imagePath"]) == IMAGE
            result = {"entries": sorted(archive.namelist()), "posts": posts, "imageSHA256": hashlib.sha256(IMAGE).hexdigest()}
        self.record("export ZIP manifest and bytes", "GET", "/api/me/export", status, hdr, result)

    def sse(self, date):
        resp = transport(self.bases["asia"], "GET", "/api/notifications", token=self.actors["c"]["token"], stream=True)
        assert resp.status == 200 and resp.headers.get_content_type() == "text/event-stream"
        result, failures = [], []
        def read_event():
            try:
                while True:
                    line = resp.readline()
                    if not line:
                        break
                    if line.startswith(b"data: "):
                        result.append(json.loads(line[6:]))
                        break
            except Exception as exc:
                failures.append(str(exc))
            finally:
                resp.close()
        thread = threading.Thread(target=read_event, daemon=True)
        thread.start()
        next_date = (dt.date.fromisoformat(date) + dt.timedelta(days=1)).isoformat()
        window = self.window("asia", next_date, " SSE")
        thread.join(20)
        assert not thread.is_alive() and not failures and len(result) == 1, ("SSE delivery failed", failures, result)
        event = result[0]
        assert event["postingWindowId"] == window["id"] and event["region"] == "asia"
        assert instant(event["createdAt"]) == instant(window["startsAt"])
        self.record("SSE posting-window event", "GET", "/api/notifications", 200, dict(resp.headers.items()), event)

    def coverage_report(self):
        missing = [" ".join(route) for route in ROUTES if " ".join(route) not in self.coverage]
        assert not missing, "Uncovered routes: " + ", ".join(missing)
        # Disabled initialization and irreversible certification intentionally
        # have no successful response. Every other route needs a 2xx case.
        expected_error_only = {"POST /api/initialize", "DELETE /api/admin/users/:userId/certification"}
        no_success = [route for route, cases in self.coverage.items() if route not in expected_error_only and not any(200 <= c["status"] < 300 for c in cases)]
        assert not no_success, "Routes without happy path: " + ", ".join(no_success)
        return self.coverage


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--only", choices=("go", "java", "both"), default="both")
    parser.add_argument("--go-base-port", type=int, default=18080)
    parser.add_argument("--java-base-port", type=int, default=28080)
    parser.add_argument("--output", type=pathlib.Path, default=pathlib.Path("/tmp/isureal-java-contract"))
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    run_id = uuid.uuid4().hex[:10]
    suites = [Suite(label, {r: f"http://127.0.0.1:{port+i}" for i, r in enumerate(REGIONS)}, run_id)
              for label, port in (("go", args.go_base_port), ("java", args.java_base_port)) if args.only in (label, "both")]
    success = False
    try:
        last_dates = [suite.setup() for suite in suites]
        date = (max(last_dates) + dt.timedelta(days=1)).isoformat()
        for suite in suites:
            suite.scenario(date)
            suite.coverage_report()
            print(f"PASS {suite.label}: {len(suite.observations)} cases; {len(suite.coverage)}/{len(ROUTES)} routes; every enabled route has a happy path", flush=True)
        if len(suites) == 2:
            left, right = (s.observations for s in suites)
            differences = [{"case": a["case"], "go": a, "java": b} for a, b in zip(left, right) if a != b]
            if len(left) != len(right):
                differences.append({"observationCounts": [len(left), len(right)]})
            (args.output / "differences.json").write_text(json.dumps(differences, indent=2, ensure_ascii=False))
            assert not differences, f"{len(differences)} Go/Java differences; inspect {args.output / 'differences.json'}"
            print("PASS normalized Go/Java contract parity", flush=True)
        success = True
    finally:
        for suite in suites:
            report = {"implementation": suite.label, "complete": success, "cases": suite.observations, "coverage": suite.coverage}
            (args.output / (suite.label + ".json")).write_text(json.dumps(report, indent=2, ensure_ascii=False))
        lines = ["# API contract route coverage", "", "| Route | " + " | ".join(s.label for s in suites) + " |", "|---|" + "---|" * len(suites)]
        for route in sorted(" ".join(r) for r in ROUTES):
            lines.append("| " + route + " | " + " | ".join(", ".join(map(str, sorted({c['status'] for c in s.coverage.get(route, [])}))) or "NOT RUN" for s in suites) + " |")
        (args.output / "coverage.md").write_text("\n".join(lines) + "\n")
        print("Evidence:", args.output)

if __name__ == "__main__":
    main()
