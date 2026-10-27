#!/usr/bin/env python3
"""Waits until App Store Connect has processed a build, so testers told about it can install it right away.

    ASC_ISSUER_ID=<uuid> [ASC_KEY_ID=M3H6VX3N58] python3 scripts/asc-wait-build.py <build-number>

Exit 0 when the build is VALID, 1 when processing failed, 2 after 30 minutes without an answer.
"""
import json, os, pathlib, sys, time, urllib.error, urllib.request

import jwt

BUNDLE_ID = "com.evselab.pollmaster"
API = "https://api.appstoreconnect.apple.com/v1"

build = sys.argv[1] if len(sys.argv) > 1 else sys.exit("usage: asc-wait-build.py <build-number>")
key_id = os.environ.get("ASC_KEY_ID", "M3H6VX3N58")
issuer = os.environ.get("ASC_ISSUER_ID") or sys.exit("ASC_ISSUER_ID is required")
private_key = (pathlib.Path.home() / ".appstoreconnect/private_keys" / f"AuthKey_{key_id}.p8").read_text()


def get(path):
    now = int(time.time())
    token = jwt.encode({"iss": issuer, "iat": now, "exp": now + 15 * 60, "aud": "appstoreconnect-v1"},
                       private_key, algorithm="ES256", headers={"kid": key_id, "typ": "JWT"})
    req = urllib.request.Request(API + path, headers={"Authorization": f"Bearer {token}"})
    with urllib.request.urlopen(req, timeout=30) as r:
        return json.loads(r.read())


app = get(f"/apps?filter[bundleId]={BUNDLE_ID}")["data"][0]["id"]
deadline = time.time() + 30 * 60
while time.time() < deadline:
    try:
        builds = get(f"/builds?filter[app]={app}&filter[version]={build}")["data"]
    except (urllib.error.URLError, TimeoutError) as e:
        builds = []
        print(f"  (App Store Connect not reachable: {e})")
    state = builds[0]["attributes"]["processingState"] if builds else "NOT YET LISTED"
    if state == "VALID":
        print(f"build {build} processed")
        sys.exit(0)
    if state in ("FAILED", "INVALID"):
        sys.exit(f"build {build} processing {state}")
    print(f"  build {build}: {state}, checking again in 30 s")
    time.sleep(30)
print(f"build {build} still not processed after 30 minutes")
sys.exit(2)
