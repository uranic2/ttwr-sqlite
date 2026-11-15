"""Opt-in Windows GUI test of an original released EXE upgrading from public GitHub.

Requires local original source-version release ZIPs, a JDK and Java 8 x32.
The probe clicks Swing controls through a startup agent, without transforming code.
Never runs against an existing user profile; all files belong to a new .local directory.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import sqlite3
import subprocess
import time
import uuid
import xml.etree.ElementTree as ET
import zipfile

ROOT = Path(__file__).resolve().parent.parent
OLD_HASHES = {"0.1.2": {
    "system-java": "f0f6f38187d709e21be675ef4add9e6751331d55715d89f22697e2302163596b",
    "windows-x64": "023e00a76e89965548992997a9f635ff21cad4c21a300555ff078754d1029eee",
}, "0.1.4": {
    "system-java": "d446a144bb9591595230967e5d1bc5b131c0a5c24319b1984b93836503cb3c43",
    "windows-x64": "a1d79d28689c26051b4bf71f94dd9f092511649d3bc38a78e0d3b4dab951f297",
}, "0.1.5": {
    "system-java": "8e1b9d67fe7643ac0e268b4788c6cb2f5718c89c0ab67bf0104c026f095e40c8",
    "windows-x64": "67cd7cc2af10608fe7f4467004a38cde7d59fa5734436d8fd67d203b87e6865b",
}, "0.1.6": {
    "system-java": "0e97389a6bf0ae54c373136599581ab9726677c6d95f83fab89d30a527a82e08",
    "windows-x64": "8623b9102eef5f10d4648a883fd59a58245ec534485c490eb2aa222caaa9a25b",
}, "0.1.7": {
    "system-java": "b6666ed3086baefb2620917ba59a4d01988e73d493c94846eaeeb44fee76735b",
    "windows-x64": "695b4cd08225b9dd8666c9b454d048e6b03b774b59a7827dfcdb757e2cc92b20",
}, "0.1.8": {
    "system-java": "374142963daf1417212d23b3d4c9ed7ffd48c5459b4cda883ed3d6fbf0c0cd8b",
    "windows-x64": "1cfd9a66a92e4c8be4e27405d9169ce0839b4ffc8b07d9d8ac13dc7b60565c01",
}}


def sha(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def tree_hash(folder):
    if not folder.exists():
        return None
    digest = hashlib.sha256()
    for path in sorted(folder.rglob("*")):
        if path.is_file():
            digest.update(path.relative_to(folder).as_posix().encode())
            digest.update(sha(path).encode())
    return digest.hexdigest()


def snapshot(home):
    data = home / "data"
    # Called only while the application is closed. A plain read-only SQLite connection
    # can itself create WAL/SHM; immutable mode keeps the observer from changing the profile.
    assert not (data / "DB/Players.sqlite-wal").exists(), "WAL remains before closed-profile snapshot"
    assert not (data / "DB/Players.sqlite-shm").exists(), "SHM remains before closed-profile snapshot"
    db = sqlite3.connect((data / "DB/Players.sqlite").as_uri() + "?immutable=1", uri=True)
    try:
        tables = {}
        for (table,) in db.execute("select name from sqlite_master where type='table' and name not like 'sqlite_%' order by name"):
            assert table in ("players", "metadata")
            tables[table] = db.execute('select * from "' + table + '" order by ' + ("scope,id" if table == "players" else "key")).fetchall()
        payload = json.dumps(tables, ensure_ascii=False, separators=(",", ":"), allow_nan=False).encode()
        groups = dict(db.execute("select scope,count(*) from players group by scope"))
        integrity = db.execute("pragma integrity_check").fetchone()[0]
    finally:
        db.close()
    cfg = data / "ttwr.cfg"
    element = ET.parse(cfg).getroot()
    for item in element.iter():
        if item.text is not None and not item.text.strip():
            item.text = None
        item.tail = None
    return {
        "databaseLogicalSha256": hashlib.sha256(payload).hexdigest(),
        "playersByScope": groups,
        "integrity": integrity,
        "settingsFileSha256": sha(cfg),
        "settingsXmlSha256": hashlib.sha256(ET.tostring(element)).hexdigest(),
        "runtimeSha256": tree_hash(home / "runtime"),
        "markerFiles": {name: sha(data / name) for name in ("Tours/preserve-marker.txt", "Backup/preserve-marker.txt")},
    }


def stop_own_processes(home):
    # All callers use a newly created test directory, never an existing program folder.
    assert home.is_relative_to(ROOT / ".local")
    env = os.environ.copy()
    env["TTWR_UPGRADE_CLEAN_HOME"] = str(home)
    command = "$target=$env:TTWR_UPGRADE_CLEAN_HOME; Get-CimInstance Win32_Process | Where-Object {$_.Name -in @('java.exe','javaw.exe','ttwr-sqlite.exe') -and $_.CommandLine -and $_.CommandLine.Contains($target)} | ForEach-Object {Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue}"
    subprocess.run(["powershell.exe", "-NoProfile", "-NonInteractive", "-Command", command], env=env, capture_output=True)


def verify_manifest(folder, backup_without_runtime=False):
    for item in json.loads((folder / "checksums.json").read_text(encoding="utf-8-sig")):
        if backup_without_runtime and item["path"].startswith("runtime/"):
            continue
        assert sha(folder / item["path"]) == item["sha256"], "Fixture manifest differs: " + item["path"]


def prepare_update_storage(home, source_version):
    updates = home / "data/updates"
    updates.mkdir(parents=True, exist_ok=True)
    previous = ROOT / "releases/ttwr-sqlite-0.1.5-system-java.zip"
    assert sha(previous) == OLD_HASHES["0.1.5"]["system-java"]
    obsolete = []
    for i, version in enumerate(("0.1.5", source_version)):
        package = ROOT / "releases" / f"ttwr-sqlite-{version}-system-java.zip"
        assert sha(package) == OLD_HASHES[version]["system-java"]
        stage = updates / f"update-history-{i}"
        stage.mkdir()
        full = ROOT / "releases/ttwr-sqlite-0.1.6-windows-x64.zip"
        assert sha(full) == OLD_HASHES["0.1.6"]["windows-x64"]
        for folder, archive in (("image", package), ("backup", previous if i == 0 else full)):
            with zipfile.ZipFile(archive) as z:
                for entry in z.infolist():
                    if folder == "backup" and entry.filename.startswith("runtime/"):
                        continue
                    z.extract(entry, stage / folder)
            verify_manifest(stage / folder, backup_without_runtime=(folder == "backup"))
        shutil.copy2(package, stage / "release.zip")
        with zipfile.ZipFile(previous) as z:
            (stage / "updater.dat").write_bytes(z.read("app/ttwr.dat"))
        status = stage / "status.txt"
        status.write_text("installed " + version + "\n", encoding="utf-8")
        os.utime(status, (time.time() - 200 + i * 50,) * 2)
        obsolete.append(stage)
    protected = {}
    for name, text in (("update-preserve-failed/status.txt", "failed; rollback attempted"),
                       ("update-preserve-failed/backup/keep.txt", "failed backup"),
                       ("update-preserve-pending/status.txt", "installing 9.9.9"),
                       ("update-preserve-unknown/notes.txt", "user notes"),
                       ("retired-files/keep.txt", "retired backup")):
        path = updates / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text, encoding="utf-8")
    for name in ("update-preserve-failed", "update-preserve-pending", "update-preserve-unknown", "retired-files"):
        protected[name] = tree_hash(updates / name)
    return obsolete, protected


def check_update_storage(home, obsolete, protected, target, source_version):
    assert all(not p.exists() for p in obsolete), "Old successful update folders remain"
    for name, digest in protected.items():
        assert tree_hash(home / "data/updates" / name) == digest, "Protected update changed: " + name
    installed = [p.parent for p in (home / "data/updates").glob("*/status.txt")
                 if p.read_text().strip() == "installed " + target]
    assert len(installed) == 1, "Latest installed update missing"
    latest = installed[0]
    assert (latest / "confirmed-start.txt").read_text().strip() == target, "Startup not confirmed"
    verify_manifest(latest / "image")
    # The installer backs up replaced application files. A bundled runtime stays in place.
    with zipfile.ZipFile(ROOT / "releases" / f"ttwr-sqlite-{source_version}-system-java.zip") as z:
        for name in z.namelist():
            if name.endswith("/") or name == "checksums.json":
                continue
            assert sha(latest / "backup" / name) == hashlib.sha256(z.read(name)).hexdigest(), "Latest backup changed: " + name
    return {"obsoleteSuccessfulFoldersRemoved": len(obsolete), "latestBackup": str(latest / "backup"),
            "latestBackupFilesVerified": len(json.loads((latest / "image/checksums.json").read_text(encoding="utf-8-sig"))),
            "failedPendingUnknownAndRetiredPreserved": True}


def run_case(work, flavor, java32, agent, classes, jdk, from_version, target, require_cleanup, unicode_path=False, require_storage=False):
    label = flavor + ("-cyrillic" if unicode_path else "-ascii")
    report = work / "reports" / label
    report.mkdir(parents=True)
    home = work / ("Проверка обновления" if unicode_path else label)
    archive = ROOT / "releases" / f"ttwr-sqlite-{from_version}-{flavor}.zip"
    assert sha(archive) == OLD_HASHES[from_version][flavor], "Original release archive changed"
    with zipfile.ZipFile(archive) as source:
        source.extractall(home)
    retired = home / "docs/source-provenance.md"
    if require_cleanup and not retired.exists():
        # Synthetic legacy document; no dependency on deleted older release ZIPs.
        retired.parent.mkdir(parents=True, exist_ok=True)
        retired.write_text("Synthetic retired-document fixture for live upgrade verification.\n", encoding="utf-8")
    retired_hash = sha(retired) if retired.exists() else None
    original_manifest = sha(home / "checksums.json")
    original_app = sha(home / "app/ttwr.dat")
    original_exe = sha(home / "ttwr-sqlite.exe")
    cp = str(classes) + ";" + str(home / "app/ttwr.dat") + ";" + str(home / "app/lib/*")
    fixture = [str(jdk / "bin/java.exe"), "--enable-native-access=ALL-UNNAMED", "-Dttwr.home=" + str(home), "-Dttwr.seed=" + str(home / "app/seed"), "-cp", cp, "ttr.PrepareUpgradeProfile", str(report)]
    subprocess.run(fixture, check=True)
    obsolete, protected = prepare_update_storage(home, from_version) if require_storage else ([], {})
    before = snapshot(home)
    shutil.copy2(home / "data/ttwr.cfg", report / "fixture-ttwr.cfg")
    assert before["playersByScope"] == {"current": 301, "history-upgrade-check": 1}
    assert sha(home / "app/ttwr.dat") == original_app and sha(home / "ttwr-sqlite.exe") == original_exe
    env = os.environ.copy()
    env["JAVA_HOME"] = str(java32)
    env["JAVA_TOOL_OPTIONS"] = '-javaagent:"' + str(agent) + '"'
    env["TTWR_UPGRADE_TEST_REPORT"] = str(report)
    env["TTWR_UPGRADE_TEST_FROM"] = from_version
    env["TTWR_UPGRADE_TEST_TO"] = target
    env["TTWR_UPGRADE_TEST_STORAGE"] = "true" if require_storage else "false"
    result = {"case": label, "from": from_version, "target": target, "before": before, "originalZipSha256": OLD_HASHES[from_version][flavor]}
    print("START_LIVE_UPGRADE " + label, flush=True)
    process = subprocess.Popen([str(home / "ttwr-sqlite.exe")], cwd=home, env=env, creationflags=subprocess.CREATE_NO_WINDOW)
    try:
        deadline = time.monotonic() + 240
        last_events = ""
        finished_at = None
        storage_started = None
        storage_error = None
        while time.monotonic() < deadline:
            events_file = report / "events.log"
            events = events_file.read_text(encoding="utf-8") if events_file.exists() else ""
            if events != last_events:
                print(events[len(last_events):].strip(), flush=True)
                last_events = events
            failure = report / "failure.txt"
            if failure.exists():
                raise AssertionError(failure.read_text(encoding="utf-8"))
            if require_storage and (report / f"boot-{target}.properties").exists() and not (report / "storage-verified.txt").exists():
                if storage_started is None:
                    storage_started = time.monotonic()
                try:
                    result["updateStorage"] = check_update_storage(home, obsolete, protected, target, from_version)
                    (report / "storage-verified.txt").write_text("cleanup and latest backup verified", encoding="utf-8")
                    print("LIVE_STORAGE_OK " + label, flush=True)
                except (AssertionError, OSError) as error:
                    if time.monotonic() - storage_started > 45:
                        storage_error = str(error)
                        (report / "storage-verified.txt").write_text("cleanup failed; closing normally", encoding="utf-8")
            if (report / "finished.txt").exists():
                if finished_at is None:
                    finished_at = time.monotonic()
                log = (home / "data/launcher.log").read_text(encoding="utf-8-sig", errors="replace")
                if process.poll() == 0 and log.count("Java process exit=0") >= 2:
                    result["processRemainedAfterWindowClose"] = False
                    break
                if process.poll() == 0 and time.monotonic() - finished_at >= 15:
                    # Window and SQLite are closed; retain a thread dump before stopping a background-only JVM.
                    result["processRemainedAfterWindowClose"] = True
                    break
            # Installer errors can otherwise leave an error dialog waiting for a human.
            for log in (home / "data/updates").glob("*/installer.log"):
                text = log.read_text(encoding="utf-8", errors="replace")
                if "Exception" in text or "Error:" in text:
                    raise AssertionError("Source-version installer failed: " + text)
            time.sleep(1)
        else:
            raise TimeoutError("The real GUI update or restart did not finish")
        after = snapshot(home)
        result["after"] = after
        for field in ("databaseLogicalSha256", "playersByScope", "integrity", "runtimeSha256", "markerFiles"):
            assert before[field] == after[field], "Changed after GUI update: " + field
        closed_old = report / f"closed-{from_version}-ttwr.cfg"
        boot_new = report / f"boot-{target}-ttwr.cfg"
        assert sha(closed_old) == sha(boot_new), "Installer changed the saved settings file"
        result["savedSettingsByteIdenticalAtRestart"] = True
        result["savedSettingsSha256"] = sha(closed_old)
        assert after["integrity"] == "ok"
        assert not (home / "data/DB/Players.sqlite-wal").exists()
        assert not (home / "data/DB/Players.sqlite-shm").exists()
        with zipfile.ZipFile(home / "app/ttwr.dat") as jar:
            assert jar.read("ttr/build-version.txt").decode().strip() == target
        installs = list((home / "data/updates").glob("*/status.txt"))
        installs = [p for p in installs if p.read_text().strip() == "installed " + target]
        assert len(installs) == 1
        backup = installs[0].parent / "backup"
        assert sha(backup / "checksums.json") == original_manifest, "Original backup manifest changed"
        result["originalBackupManifestUnchanged"] = True
        assert sha(backup / "app/ttwr.dat") == original_app
        assert sha(backup / "ttwr-sqlite.exe") == original_exe
        manifest = json.loads((home / "checksums.json").read_text(encoding="utf-8-sig"))
        for item in manifest:
            assert sha(home / item["path"]) == item["sha256"], "Installed file differs from release: " + item["path"]
        result["installedFilesVerified"] = len(manifest)
        result["savedSettingsStillIdenticalAfterNewClose"] = sha(closed_old) == sha(report / f"closed-{target}-ttwr.cfg")
        assert result["savedSettingsStillIdenticalAfterNewClose"], "New program changed saved settings on exit"
        result["obsoleteDocumentRetained"] = retired.exists()
        if require_cleanup:
            assert not retired.exists(), "Retired document remains after update"
            archived = home / "data/updates/retired-files" / (retired_hash + ".md")
            assert sha(archived) == retired_hash, "Retired document backup missing or changed"
            result["retiredDocumentRemovedAndBackedUp"] = True
        for field in ("runningVersion=" + from_version, "players=301"):
            assert field in (report / f"boot-{from_version}.properties").read_text(encoding="utf-8")
        assert "runningVersion=" + target in (report / f"boot-{target}.properties").read_text(encoding="utf-8")
        if require_storage:
            assert storage_error is None, storage_error
            result["updateStorage"] = check_update_storage(home, obsolete, protected, target, from_version)
        result["success"] = True
        result["settingsByteIdentical"] = before["settingsFileSha256"] == after["settingsFileSha256"]
        print("LIVE_UPGRADE_OK " + label + " players=301 history=1 settings/runtime/data retained; normal restart", flush=True)
        if result["processRemainedAfterWindowClose"]:
            print("BACKGROUND_JVM_REMAINED " + label + "; thread dump captured; SQLite already closed", flush=True)
            stop_own_processes(home)
    except Exception as error:
        result["success"] = False
        result["error"] = str(error)
        stop_own_processes(home)
        # Preserve all diagnostics and the synthetic profile; never delete WAL/SHM.
        print("LIVE_UPGRADE_FAILED " + label + " " + str(error), flush=True)
    finally:
        (report / "result.json").write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
    return result


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--jdk", type=Path, required=True)
    parser.add_argument("--java32", type=Path, required=True)
    parser.add_argument("--cyrillic", action="store_true")
    parser.add_argument("--from-version", choices=sorted(OLD_HASHES), default="0.1.8")
    parser.add_argument("--target-version", default="0.1.9")
    parser.add_argument("--require-cleanup", action="store_true")
    parser.add_argument("--require-storage-cleanup", action="store_true")
    args = parser.parse_args()
    if os.name != "nt":
        raise SystemExit("Windows required")
    work = ROOT / ".local" / ("live-upgrade-" + args.from_version.replace(".", "") + "-" + args.target_version.replace(".", "") + "-" + uuid.uuid4().hex)
    work.mkdir(parents=True)
    build = ROOT / "build" / ("live-upgrade-agent-" + uuid.uuid4().hex)
    classes = build / "classes"
    classes.mkdir(parents=True)
    # Compile only against the selected original release ABI, never the current build.
    old = build / "original"
    old_zip = ROOT / "releases" / f"ttwr-sqlite-{args.from_version}-system-java.zip"
    assert sha(old_zip) == OLD_HASHES[args.from_version]["system-java"]
    with zipfile.ZipFile(old_zip) as archive:
        archive.extractall(old)
    cp = str(old / "app/ttwr.dat") + ";" + str(old / "app/lib/*")
    subprocess.run([str(args.jdk / "bin/javac.exe"), "--release", "8", "-Xlint:-options", "-encoding", "UTF-8", "-cp", cp, "-d", str(classes), str(ROOT / "src/test/java/ttr/PrepareUpgradeProfile.java"), str(ROOT / "src/test/java/ttr/updates/LiveUpgradeAgent.java")], check=True)
    manifest = build / "MANIFEST.MF"
    manifest.write_text("Manifest-Version: 1.0\nPremain-Class: ttr.updates.LiveUpgradeAgent\n\n", encoding="ascii")
    agent = build / "probe.jar"
    subprocess.run([str(args.jdk / "bin/jar.exe"), "--create", "--file", str(agent), "--manifest", str(manifest), "-C", str(classes), "."], check=True)
    results = [run_case(work, flavor, args.java32, agent, classes, args.jdk, args.from_version, args.target_version, args.require_cleanup, require_storage=args.require_storage_cleanup) for flavor in ("windows-x64", "system-java")]
    if args.cyrillic:
        results.append(run_case(work, "windows-x64", args.java32, agent, classes, args.jdk, args.from_version, args.target_version, args.require_cleanup, True, args.require_storage_cleanup))
    (work / "summary.json").write_text(json.dumps({"results": results}, ensure_ascii=False, indent=2), encoding="utf-8")
    print("LIVE_UPGRADE_REPORT " + str(work), flush=True)
    raise SystemExit(0 if all(result["success"] for result in results) else 1)


if __name__ == "__main__":
    main()
