"""Puts the ObsiLearn release key into the GitHub repo's secrets. Run it once.

It uses the GitHub sign-in git already has on this PC. The key and password
stay in %USERPROFILE%/.obsilearn. Nothing secret is printed.
"""
import base64
import json
import os
import subprocess
import urllib.request

from nacl import encoding, public

REPO = "Virus7976/Obsi-Learn"
DIR = os.path.join(os.path.expanduser("~"), ".obsilearn")

out = subprocess.run(
    ["git", "credential", "fill"],
    input="protocol=https\nhost=github.com\nusername=Virus7976\n\n",
    capture_output=True, text=True,
).stdout
token = dict(l.split("=", 1) for l in out.splitlines() if "=" in l).get("password")
if not token:
    raise SystemExit("Git has no GitHub sign-in for Virus7976 on this PC.")


def call(method, path, body=None):
    req = urllib.request.Request(
        "https://api.github.com" + path, method=method,
        data=json.dumps(body).encode() if body else None,
        headers={"Authorization": "Bearer " + token, "Accept": "application/vnd.github+json"},
    )
    with urllib.request.urlopen(req) as r:
        text = r.read()
        return r.status, json.loads(text) if text else {}


_, key = call("GET", f"/repos/{REPO}/actions/secrets/public-key")
box = public.SealedBox(public.PublicKey(key["key"].encode(), encoding.Base64Encoder()))

values = {
    "ANDROID_KEYSTORE_B64": base64.b64encode(open(os.path.join(DIR, "release.jks"), "rb").read()).decode(),
    "ANDROID_KEYSTORE_PASSWORD": open(os.path.join(DIR, "password.txt")).read().strip(),
}
for name, value in values.items():
    sealed = base64.b64encode(box.encrypt(value.encode())).decode()
    status, _ = call("PUT", f"/repos/{REPO}/actions/secrets/{name}", {"encrypted_value": sealed, "key_id": key["key_id"]})
    print(name, "saved" if status in (201, 204) else f"failed ({status})")

print("Done. Back up", DIR)
