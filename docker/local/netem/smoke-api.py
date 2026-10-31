#!/usr/bin/env python3
"""Synthetic Java API writes across all local regions; retains test accounts."""
import json
import time
import urllib.request
import uuid

REGIONS = ('asia', 'europe', 'america')


def request(region, method, path, body=None, token=None, expected=200):
    url = f'http://127.0.0.1:{28080 + REGIONS.index(region)}{path}'
    headers = {'Content-Type': 'application/json'}
    if token:
        headers['Authorization'] = 'Bearer ' + token
    data = json.dumps(body).encode() if body is not None else None
    start = time.monotonic()
    with urllib.request.urlopen(urllib.request.Request(url, data=data, headers=headers, method=method), timeout=120) as response:
        assert response.status == expected, (method, path, response.status)
        raw = response.read()
    print('PASS', region, method, path, expected, f'{time.monotonic() - start:.3f}s', flush=True)
    return json.loads(raw) if raw else None


for index, region in enumerate(REGIONS):
    username = 'netem_' + uuid.uuid4().hex[:16]
    registered = request(region, 'POST', '/api/auth/register',
                         {'username': username, 'displayName': 'Synthetic netem', 'region': region}, expected=201)
    logged = request(region, 'POST', '/api/auth/login',
                     {'username': username, 'secretKey': registered['secretKey']})
    token = logged['token']
    me = request(region, 'GET', '/api/me', token=token)
    assert me['region'] == region
    destination = REGIONS[(index + 1) % len(REGIONS)]
    request(region, 'PATCH', '/api/me', {'region': destination}, token)
    me = request(destination, 'GET', '/api/me', token=token)
    assert me['region'] == destination
    request(destination, 'POST', '/api/auth/logout', token=token, expected=204)
