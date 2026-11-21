#!/usr/bin/env python3
"""Download a GitHub Actions artifact zip via the stored custom.github credential.

The API returns 302 to a blob host; the Authorization (surrogate) header must
NOT be forwarded to the blob host (401). Usage:
  dl_artifact.py <artifact_id> <repo_slug> <dest_zip>
"""
import sys, urllib.request, urllib.error

sys.path.insert(0, "/opt/muse/skills/custom-connector-setup/bin")
from dynamic_credentials import add_surrogate_to_request, read_response_body

API = "https://api.github.com"
HOSTS = ["api.github.com"]
CRED = "custom.github"

artifact_id, slug, dest = sys.argv[1], sys.argv[2], sys.argv[3]
url = f"{API}/repos/{slug}/actions/artifacts/{artifact_id}/zip"

req = urllib.request.Request(url, method="GET")
req.add_header("Accept", "application/vnd.github+json")
req.add_header("X-GitHub-Api-Version", "2022-11-28")
add_surrogate_to_request(req, CRED, allowed_hosts=HOSTS)

# no auto-redirect: catch the 302 and follow WITHOUT auth headers
class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None

opener = urllib.request.build_opener(NoRedirect)
try:
    resp = opener.open(req, timeout=120)
    print("unexpected status:", resp.status)
    raise SystemExit(1)
except urllib.error.HTTPError as e:
    if e.code not in (301, 302, 303, 307, 308):
        print("artifact download failed:", e.code, e.read()[:500])
        raise SystemExit(1)
    loc = e.headers.get("Location")
    print("redirect ->", loc[:80], "...")
    # follow to blob host with NO auth headers
    req2 = urllib.request.Request(loc, method="GET")
    with urllib.request.urlopen(req2, timeout=300) as r2:
        print("blob status:", r2.status, "| content-length:", r2.headers.get("Content-Length"))
        with open(dest, "wb") as f:
            while True:
                chunk = r2.read(1 << 20)
                if not chunk:
                    break
                f.write(chunk)
    print("saved:", dest)
