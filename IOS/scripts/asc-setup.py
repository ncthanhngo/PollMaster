#!/usr/bin/env python3
"""One-time App Store Connect setup for PollMaster, through the API key.

Registers the bundle id, creates the "PollMaster App Store" provisioning profile
against the Apple Distribution certificate that is in the local keychain, installs
it, and checks that the app record exists (the API cannot create app records —
that one step is done once on appstoreconnect.apple.com).

Safe to re-run: everything that already exists is reused.

    ASC_ISSUER_ID=<uuid> [ASC_KEY_ID=M3H6VX3N58] python3 scripts/asc-setup.py

Needs PyJWT with the cryptography backend (ES256).
"""
import json, os, pathlib, subprocess, sys, time, urllib.error, urllib.request, base64

import jwt

BUNDLE_ID = "com.evselab.pollmaster"
APP_NAME = "PollMaster"
PROFILE_NAME = "PollMaster App Store"
API = "https://api.appstoreconnect.apple.com/v1"

key_id = os.environ.get("ASC_KEY_ID", "M3H6VX3N58")
issuer = os.environ.get("ASC_ISSUER_ID") or sys.exit("ASC_ISSUER_ID is required (App Store Connect → Users and Access → Integrations → Keys)")
key_path = pathlib.Path.home() / ".appstoreconnect/private_keys" / f"AuthKey_{key_id}.p8"
private_key = key_path.read_text()


def token():
    now = int(time.time())
    return jwt.encode({"iss": issuer, "iat": now, "exp": now + 15 * 60, "aud": "appstoreconnect-v1"},
                      private_key, algorithm="ES256", headers={"kid": key_id, "typ": "JWT"})


def call(method, path, body=None):
    req = urllib.request.Request(API + path, method=method, data=json.dumps(body).encode() if body else None,
                                 headers={"Authorization": f"Bearer {token()}", "Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(req) as r:
            return json.loads(r.read() or b"{}")
    except urllib.error.HTTPError as e:
        sys.exit(f"{method} {path} → {e.code}\n{e.read().decode()}")


def local_distribution_serials():
    """Serial numbers of the Apple Distribution certificates whose private key is in this keychain."""
    pem = subprocess.run(["security", "find-certificate", "-a", "-c", "Apple Distribution", "-p"],
                         capture_output=True, text=True).stdout
    serials, block = set(), []
    for line in pem.splitlines():
        block.append(line)
        if line.startswith("-----END"):
            out = subprocess.run(["openssl", "x509", "-noout", "-serial"], input="\n".join(block),
                                 capture_output=True, text=True).stdout
            serials.add(out.strip().split("=")[-1].lstrip("0").upper())
            block = []
    usable = subprocess.run(["security", "find-identity", "-v", "-p", "codesigning"], capture_output=True, text=True).stdout
    return serials if "Apple Distribution" in usable else set()


# 1. Bundle id
found = call("GET", f"/bundleIds?filter[identifier]={BUNDLE_ID}&filter[platform]=IOS")["data"]
bundle = next((b for b in found if b["attributes"]["identifier"] == BUNDLE_ID), None)
if not bundle:
    bundle = call("POST", "/bundleIds", {"data": {"type": "bundleIds", "attributes": {
        "identifier": BUNDLE_ID, "name": APP_NAME, "platform": "IOS"}}})["data"]
    print(f"registered bundle id {BUNDLE_ID}")
else:
    print(f"bundle id {BUNDLE_ID} already registered")

# 2. Distribution certificate that this Mac can sign with
serials = local_distribution_serials()
certs = call("GET", "/certificates?filter[certificateType]=DISTRIBUTION,IOS_DISTRIBUTION&limit=200")["data"]
cert = next((c for c in certs if c["attributes"]["serialNumber"].lstrip("0").upper() in serials), None)
if not cert:
    sys.exit("no Apple Distribution certificate on App Store Connect matches one in this keychain")
print(f"certificate {cert['attributes']['name']} (expires {cert['attributes']['expirationDate'][:10]})")

# 3. App Store profile (recreated when it points at another certificate or is no longer active)
profiles = call("GET", f"/profiles?filter[name]={urllib.request.quote(PROFILE_NAME)}&include=certificates")
profile = None
for p in profiles["data"]:
    linked = {c["id"] for c in p["relationships"]["certificates"].get("data", [])}
    if p["attributes"]["profileState"] == "ACTIVE" and cert["id"] in linked:
        profile = p
    else:
        call("DELETE", f"/profiles/{p['id']}")
        print(f"removed stale profile {p['id']}")
if not profile:
    profile = call("POST", "/profiles", {"data": {"type": "profiles",
        "attributes": {"name": PROFILE_NAME, "profileType": "IOS_APP_STORE"},
        "relationships": {"bundleId": {"data": {"type": "bundleIds", "id": bundle["id"]}},
                          "certificates": {"data": [{"type": "certificates", "id": cert["id"]}]}}}})["data"]
    print(f"created profile \"{PROFILE_NAME}\"")
content = base64.b64decode(profile["attributes"]["profileContent"])
uuid = profile["attributes"]["uuid"]
for folder in ("Library/MobileDevice/Provisioning Profiles", "Library/Developer/Xcode/UserData/Provisioning Profiles"):
    d = pathlib.Path.home() / folder
    d.mkdir(parents=True, exist_ok=True)
    (d / f"{uuid}.mobileprovision").write_bytes(content)
print(f"installed profile {uuid}")

# 4. App record
apps = call("GET", f"/apps?filter[bundleId]={BUNDLE_ID}")["data"]
if apps:
    print(f"app record: {apps[0]['attributes']['name']} (Apple ID {apps[0]['id']}) — ready to upload")
else:
    print(f"\nNo app record yet. Create it once on appstoreconnect.apple.com → Apps → + → New App:\n"
          f"  Platform iOS · Name {APP_NAME} · Primary language Vietnamese · Bundle ID {BUNDLE_ID} · SKU pollmaster")
    sys.exit(2)
