"""Publish two checked bundles using the workflow's short-lived GITHUB_TOKEN."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import urllib.error
import urllib.parse
import urllib.request


def verified_bundles(directory, version):
    expected = {f"ttwr-sqlite-{version}-{flavor}.zip" for flavor in ("system-java", "windows-x64")}
    if {p.name for p in directory.glob("*.zip")} != expected:
        raise RuntimeError("Expected exactly two versioned ZIP bundles")
    checksums = {}
    for line in (directory / "SHA256SUMS.txt").read_text(encoding="utf-8-sig").splitlines():
        if not line.strip():
            continue
        digest, name = line.split()
        if name in checksums:
            raise RuntimeError("Duplicate checksum entry")
        checksums[name] = digest
    result = []
    for name in sorted(expected):
        path = directory / name
        digest = hashlib.sha256(path.read_bytes()).hexdigest()
        if digest != checksums.get(name):
            raise RuntimeError(f"ZIP checksum mismatch: {name}")
        result.append((path, digest))
    return result


def publish(tag, directory, dry_run=False):
    version = Path("VERSION").read_text().strip()
    if not re.fullmatch(r"(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)", version) or tag != "v" + version:
        raise RuntimeError("Release tag must match VERSION exactly")
    bundles = verified_bundles(directory, version)
    repository = os.environ["GITHUB_REPOSITORY"]
    if not re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", repository):
        raise RuntimeError("Invalid repository name")
    token = os.environ["GH_TOKEN"]
    base = "https://api.github.com/repos/" + repository

    def api(url, method="GET", data=None, content_type="application/json", missing=False):
        if not (url.startswith(base + "/") or url.startswith("https://uploads.github.com/repos/" + repository + "/")):
            raise RuntimeError("Unexpected GitHub API URL")
        payload = json.dumps(data).encode() if data is not None and content_type == "application/json" else data
        request = urllib.request.Request(url, method=method, data=payload, headers={
            "Authorization": "Bearer " + token,
            "Accept": "application/vnd.github+json",
            "User-Agent": "TTWR-SQLite-release-workflow",
            "Content-Type": content_type,
            "X-GitHub-Api-Version": "2022-11-28",
        })
        try:
            with urllib.request.urlopen(request, timeout=180) as response:
                return json.load(response)
        except urllib.error.HTTPError as error:
            if missing and error.code == 404:
                return None
            raise RuntimeError(f"GitHub API HTTP {error.code}") from None

    ref = api(base + "/git/ref/tags/" + urllib.parse.quote(tag, safe=""))["object"]
    for _ in range(5):
        if ref["type"] == "commit":
            break
        if ref["type"] != "tag":
            raise RuntimeError("Tag does not reference a commit")
        ref = api(base + "/git/tags/" + ref["sha"])["object"]
    if ref["type"] != "commit":
        raise RuntimeError("Too many annotated tag indirections")
    expected_commit = os.environ.get("GITHUB_SHA")
    if expected_commit and ref["sha"] != expected_commit:
        raise RuntimeError("Tag moved since the workflow started")
    if dry_run:
        print("PUBLISH_DRY_RUN_OK: tag, commit, filenames, checksums and API authentication; no writes")
        return

    notes = (directory / "release-notes.md").read_text(encoding="utf-8-sig")
    notes += "\n\n## SHA-256\n\n```text\n" + "\n".join(digest + "  " + path.name for path, digest in bundles) + "\n```\n"
    release = api(base + "/releases/tags/" + urllib.parse.quote(tag, safe=""), missing=True)
    if release is None:
        release = api(base + "/releases", "POST", {
            "tag_name": tag, "target_commitish": ref["sha"], "name": "TTWR SQLite " + version,
            "body": notes, "draft": True, "prerelease": False,
        })
    existing = {asset["name"]: asset for asset in release["assets"]}
    if set(existing) - {path.name for path, _ in bundles}:
        raise RuntimeError("Unexpected assets in existing release; nothing was overwritten")
    for path, digest in bundles:
        asset = existing.get(path.name)
        if asset is None:
            if not release["draft"]:
                raise RuntimeError("Published release is incomplete; refusing to change it")
            url = release["upload_url"].split("{")[0] + "?name=" + urllib.parse.quote(path.name)
            asset = api(url, "POST", path.read_bytes(), "application/zip")
        if asset["size"] != path.stat().st_size or asset.get("digest") != "sha256:" + digest:
            raise RuntimeError("GitHub asset checksum mismatch; refusing to publish or replace it")
        print("ASSET_VERIFIED " + path.name, flush=True)
    if release["draft"]:
        latest = api(base + "/releases/latest", missing=True)
        latest_tag = latest["tag_name"].lstrip("v") if latest else "0.0.0"
        newer = not re.fullmatch(r"\d+\.\d+\.\d+", latest_tag) or tuple(map(int, version.split("."))) >= tuple(map(int, latest_tag.split(".")))
        release = api(base + "/releases/" + str(release["id"]), "PATCH", {
            "body": notes, "draft": False, "make_latest": "true" if newer else "false",
        })
    print("RELEASE_PUBLISHED " + release["html_url"], flush=True)


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--tag", required=True)
    parser.add_argument("--directory", type=Path, required=True)
    parser.add_argument("--dry-run", action="store_true")
    args = parser.parse_args()
    publish(args.tag, args.directory, args.dry_run)
