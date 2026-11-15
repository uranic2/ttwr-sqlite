"""Keep the newest stable releases after verified publication; remove their old tags too."""
import argparse
import json
import os
import re
import urllib.error
import urllib.parse
import urllib.request

STABLE = re.compile(r"v(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)")


def version(tag):
    match = STABLE.fullmatch(tag)
    return tuple(map(int, match.groups())) if match else None


def plan(releases, published_tag, keep):
    if keep < 1:
        raise ValueError("Keep count must be positive")
    stable = [r for r in releases if r.get("draft") is False and r.get("prerelease") is False
              and r.get("published_at") and version(r.get("tag_name", "")) is not None]
    if len({r["tag_name"] for r in stable}) != len(stable):
        raise RuntimeError("Duplicate published tags; nothing was deleted")
    stable.sort(key=lambda r: version(r["tag_name"]), reverse=True)
    retained, obsolete = stable[:keep], stable[keep:]
    current = next((r for r in retained if r["tag_name"] == published_tag), None)
    if current is None:
        raise RuntimeError("Published tag must be among retained stable versions; nothing was deleted")
    expected = {f"ttwr-sqlite-{published_tag[1:]}-{flavor}.zip" for flavor in ("system-java", "windows-x64")}
    assets = current.get("assets", [])
    if len(assets) != 2 or {a["name"] for a in assets} != expected:
        raise RuntimeError("Published release must contain both ZIP bundles; nothing was deleted")
    for asset in assets:
        if asset.get("size", 0) <= 0 or not re.fullmatch(r"sha256:[0-9a-fA-F]{64}", asset.get("digest", "")):
            raise RuntimeError("Published release assets lack size/digest; nothing was deleted")
    protected = {r["tag_name"] for r in releases if r.get("draft") or r.get("prerelease")}
    return retained, [r for r in obsolete if r["tag_name"] not in protected]


def list_releases(api):
    releases = []
    page = 1
    while True:
        batch = api(f"/releases?per_page=100&page={page}")
        if not isinstance(batch, list):
            raise RuntimeError("Unexpected release listing")
        releases.extend(batch)
        if len(batch) < 100:
            return releases
        page += 1


def prune(api, published_tag, keep=3, dry_run=False):
    retained, obsolete = plan(list_releases(api), published_tag, keep)
    print("RETAIN_RELEASES " + ", ".join(r["tag_name"] for r in retained), flush=True)
    for release in reversed(obsolete):
        tag, release_id = release["tag_name"], release["id"]
        print("PRUNE_CANDIDATE " + tag, flush=True)
        if dry_run:
            continue
        # Re-evaluate before each deletion, including externally published releases.
        _, fresh_obsolete = plan(list_releases(api), published_tag, keep)
        if not any(r["id"] == release_id and r["tag_name"] == tag for r in fresh_obsolete):
            continue
        fresh = api("/releases/" + str(release_id), missing=True)
        if fresh is None:
            continue
        if fresh["tag_name"] != tag or fresh.get("draft") is not False or fresh.get("prerelease") is not False:
            raise RuntimeError("Release changed during cleanup; stopped")
        encoded = urllib.parse.quote(tag, safe="")
        ref = api("/git/ref/tags/" + encoded, missing=True)
        api("/releases/" + str(release_id), method="DELETE", missing=True)
        print("DELETED_RELEASE_AND_ASSETS " + tag, flush=True)
        if ref is not None:
            latest_ref = api("/git/ref/tags/" + encoded, missing=True)
            if latest_ref is not None:
                if latest_ref["object"] != ref["object"]:
                    raise RuntimeError("Tag moved after release deletion; tag retained: " + tag)
                api("/git/refs/tags/" + encoded, method="DELETE", missing=True)
            print("DELETED_TAG " + tag, flush=True)
    print("RETENTION_DRY_RUN_OK" if dry_run else "RETENTION_OK", flush=True)


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request, fp, code, msg, headers, newurl):
        return None


class GitHub:
    def __init__(self, repository, token):
        if not re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", repository):
            raise ValueError("Invalid repository name")
        self.base = "https://api.github.com/repos/" + repository
        self.token = token
        self.opener = urllib.request.build_opener(NoRedirect())

    def __call__(self, path, method="GET", missing=False):
        if not path.startswith("/"):
            raise ValueError("Expected repository API path")
        request = urllib.request.Request(self.base + path, method=method, headers={
            "Authorization": "Bearer " + self.token,
            "Accept": "application/vnd.github+json",
            "User-Agent": "TTWR-SQLite-release-retention",
            "X-GitHub-Api-Version": "2022-11-28",
        })
        try:
            with self.opener.open(request, timeout=60) as response:
                data = response.read()
                return json.loads(data) if data else None
        except urllib.error.HTTPError as error:
            if missing and error.code == 404:
                return None
            raise RuntimeError(f"GitHub API HTTP {error.code}: {method} {path}") from None


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--published-tag", required=True)
    parser.add_argument("--keep", type=int, default=3)
    parser.add_argument("--dry-run", action="store_true")
    args = parser.parse_args()
    prune(GitHub(os.environ["GITHUB_REPOSITORY"], os.environ["GH_TOKEN"]),
          args.published_tag, args.keep, args.dry_run)
