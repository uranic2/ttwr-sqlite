import copy
import importlib.util
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[3]
spec = importlib.util.spec_from_file_location("prune_releases", ROOT / "scripts/prune-releases.py")
retention = importlib.util.module_from_spec(spec)
spec.loader.exec_module(retention)


def release(n, tag=None, **changes):
    tag = tag or f"v0.1.{n}"
    result = dict(id=n, tag_name=tag, draft=False, prerelease=False, published_at="2026-11-14T00:00:00Z",
                  assets=[dict(name=f"ttwr-sqlite-{tag[1:]}-{f}.zip", size=10, digest="sha256:" + "a" * 64)
                          for f in ("system-java", "windows-x64")])
    result.update(changes)
    return result


class FakeAPI:
    def __init__(self, releases):
        self.releases = copy.deepcopy(releases)
        self.deleted = []
        self.calls = []
        self.refs = {r["tag_name"]: {"object": {"type": "commit", "sha": str(r["id"])}} for r in releases}
        self.move_tag = None
        self.ref_reads = 0
        self.fail_delete = False

    def __call__(self, path, method="GET", missing=False):
        self.calls.append((method, path))
        if method == "DELETE":
            if self.fail_delete:
                raise RuntimeError("Simulated deletion failure")
            self.deleted.append(path)
            if path.startswith("/releases/"):
                self.releases = [r for r in self.releases if r["id"] != int(path.rsplit("/", 1)[1])]
            else:
                self.refs.pop(path.rsplit("/", 1)[1], None)
            return None
        if path.startswith("/releases?"):
            page = int(path.rsplit("=", 1)[1])
            return copy.deepcopy(self.releases[(page-1)*100:page*100])
        if path.startswith("/releases/"):
            return next((copy.deepcopy(r) for r in self.releases if r["id"] == int(path.rsplit("/", 1)[1])), None)
        if path.startswith("/git/ref/tags/"):
            tag = path.rsplit("/", 1)[1]
            self.ref_reads += 1
            if self.move_tag == tag and self.ref_reads == 2:
                return {"object": {"type": "commit", "sha": "changed"}}
            return copy.deepcopy(self.refs.get(tag))
        raise AssertionError(path)


class RetentionChecks(unittest.TestCase):
    def test_numeric_order_and_exact_three(self):
        kept, old = retention.plan([release(n) for n in (6, 10, 8, 7, 9)], "v0.1.10", 3)
        self.assertEqual([r["tag_name"] for r in kept], ["v0.1.10", "v0.1.9", "v0.1.8"])
        self.assertEqual({r["tag_name"] for r in old}, {"v0.1.6", "v0.1.7"})

    def test_delete_releases_assets_then_only_corresponding_tags(self):
        api = FakeAPI([release(n) for n in range(1, 7)])
        retention.prune(api, "v0.1.6")
        self.assertEqual(api.deleted, ["/releases/1", "/git/refs/tags/v0.1.1", "/releases/2", "/git/refs/tags/v0.1.2", "/releases/3", "/git/refs/tags/v0.1.3"])
        self.assertEqual(set(api.refs), {"v0.1.4", "v0.1.5", "v0.1.6"})
        self.assertEqual(len(api.releases), 3)
        retention.prune(api, "v0.1.6")
        self.assertEqual(len(api.deleted), 6)

    def test_drafts_prereleases_and_unmanaged_tags_preserved(self):
        releases = [release(n) for n in range(1, 5)] + [release(99, draft=True), release(98, prerelease=True), release(97, "preview")]
        api = FakeAPI(releases)
        retention.prune(api, "v0.1.4")
        self.assertEqual(api.deleted, ["/releases/1", "/git/refs/tags/v0.1.1"])
        self.assertTrue({"v0.1.99", "v0.1.98", "preview"}.issubset(api.refs))

    def test_incomplete_or_unpublished_release_never_deletes(self):
        for change in [dict(draft=True), dict(prerelease=True), dict(assets=[]), dict(assets=[dict(name="broken", size=0, digest="bad")])]:
            api = FakeAPI([release(n) for n in range(1, 5)] + [release(5, **change)])
            with self.assertRaises(RuntimeError):
                retention.prune(api, "v0.1.5")
            self.assertEqual(api.deleted, [])

    def test_old_published_tag_and_invalid_keep_never_delete(self):
        api = FakeAPI([release(n) for n in range(1, 6)])
        with self.assertRaises(RuntimeError):
            retention.prune(api, "v0.1.1")
        with self.assertRaises(ValueError):
            retention.prune(api, "v0.1.5", keep=0)
        self.assertFalse(api.deleted)

    def test_dry_run_and_fewer_than_three(self):
        api = FakeAPI([release(n) for n in range(1, 6)])
        retention.prune(api, "v0.1.5", dry_run=True)
        self.assertFalse(api.deleted)
        api = FakeAPI([release(1), release(2)])
        retention.prune(api, "v0.1.2")
        self.assertFalse(api.deleted)

    def test_all_api_pages_are_read(self):
        api = FakeAPI([release(n) for n in range(1, 172)])
        listed = retention.list_releases(api)
        self.assertEqual(len(listed), 171)
        self.assertEqual(len(api.calls), 2)

    def test_moved_tag_is_preserved_and_failure_reported(self):
        api = FakeAPI([release(n) for n in range(1, 5)])
        api.move_tag = "v0.1.1"
        with self.assertRaisesRegex(RuntimeError, "Tag moved"):
            retention.prune(api, "v0.1.4")
        self.assertEqual(api.deleted, ["/releases/1"])
        self.assertIn("v0.1.1", api.refs)

    def test_failed_release_deletion_never_deletes_tag(self):
        api = FakeAPI([release(n) for n in range(1, 5)])
        api.fail_delete = True
        with self.assertRaises(RuntimeError):
            retention.prune(api, "v0.1.4")
        self.assertFalse(api.deleted)
        self.assertIn("v0.1.1", api.refs)

    def test_already_missing_tag_does_not_block_release_cleanup(self):
        api = FakeAPI([release(n) for n in range(1, 5)])
        api.refs.pop("v0.1.1")
        retention.prune(api, "v0.1.4")
        self.assertEqual(api.deleted, ["/releases/1"])


if __name__ == "__main__":
    unittest.main()
