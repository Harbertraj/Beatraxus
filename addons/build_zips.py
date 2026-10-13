#!/usr/bin/env python3
"""
Builds the installable Beatraxus add-on ZIPs (addons/dist/*.zip) from addons/*/manifest.json and
verifies every ZIP against the app's real install rules, which are read straight from the Kotlin
sources (AddonInstaller.kt, AddonManager.kt) so this check can't drift from the app.

Usage:  python3 addons/build_zips.py        (stdlib only; exits non-zero on any problem)
"""
import json
import pathlib
import re
import sys
import zipfile

ROOT = pathlib.Path(__file__).resolve().parent
SRC = ROOT.parent / "app/src/main/java/com/beatraxus/app/addons"
DIST = ROOT / "dist"

REQUIRED_KEYS = ["id", "displayName", "mediaType", "protocol", "authType"]  # AddonInstaller reads all five
# authTypes the sign-in screen knows how to handle (StremioAddon is chosen by protocol).
KNOWN_AUTH = {"none", "server_credentials", "api_key", "manifest_url"}
NOT_WORKING = {"plex"}  # PlexAddon deliberately refuses to sign in until plex.tv auth exists
FIXED_DATE = (2026, 10, 12, 0, 0, 0)  # fixed timestamp => byte-identical ZIPs on every build


def read_rules():
    installer = (SRC / "AddonInstaller.kt").read_text()
    manager = (SRC / "AddonManager.kt").read_text()
    valid = set(re.findall(r'"(\w+)"', re.search(r"val validProtocols = listOf\((.*?)\)", installer).group(1)))
    factory = set(re.findall(r'"(\w+)" -> \w+Addon\(', manager))
    max_size = eval(re.search(r"MAX_FILE_SIZE = ([\d\s*]+)", installer).group(1))  # e.g. 10 * 1024 * 1024
    max_entries = int(re.search(r"MAX_ENTRIES = (\d+)", installer).group(1))
    return valid, factory, max_size, max_entries


def verify_zip(path, valid, factory, max_size, max_entries):
    problems = []
    with zipfile.ZipFile(path) as z:
        names = z.namelist()
        if len(names) > max_entries:
            problems.append(f"{len(names)} entries > {max_entries}")
        for n in names:
            if ".." in n:
                problems.append(f"zip-slip name: {n}")
        for i in z.infolist():
            if i.file_size > max_size:
                problems.append(f"{i.filename} too large")
        if "manifest.json" not in names:
            return problems + ["manifest.json is not at the ZIP root"]
        try:
            m = json.loads(z.read("manifest.json"))
        except Exception as e:  # noqa: BLE001
            return problems + [f"manifest.json is not valid JSON: {e}"]
    for k in REQUIRED_KEYS:
        if not isinstance(m.get(k), str) or not m[k].strip():
            problems.append(f"missing/empty key: {k}")
    if problems:
        return problems
    if not re.fullmatch(r"[A-Za-z0-9_]+", m["id"]):
        problems.append("id must be letters/digits/underscore (it becomes a folder name)")
    if m["protocol"] not in valid:
        problems.append(f"protocol '{m['protocol']}' rejected by AddonInstaller")
    if m["protocol"] not in factory:
        problems.append(f"protocol '{m['protocol']}' has no add-on class in AddonManager")
    if m["protocol"] in NOT_WORKING:
        problems.append(f"protocol '{m['protocol']}' is not functional yet")
    if m["authType"] not in KNOWN_AUTH:
        problems.append(f"authType '{m['authType']}' is not handled by the sign-in screen")
    if m["protocol"] == "archive_org" and m["authType"] != "none":
        problems.append("Internet Archive needs authType 'none' (it signs in automatically)")
    return problems


def main():
    valid, factory, max_size, max_entries = read_rules()
    print(f"app rules: protocols={sorted(valid)} max_entries={max_entries} max_file={max_size}")
    DIST.mkdir(exist_ok=True)
    seen_ids, failed = {}, False
    for folder in sorted(p for p in ROOT.iterdir() if (p / "manifest.json").exists()):
        out = DIST / f"{folder.name}.zip"
        info = zipfile.ZipInfo("manifest.json", date_time=FIXED_DATE)
        info.compress_type = zipfile.ZIP_DEFLATED
        info.external_attr = 0o644 << 16
        with zipfile.ZipFile(out, "w") as z:
            z.writestr(info, (folder / "manifest.json").read_bytes())
        problems = verify_zip(out, valid, factory, max_size, max_entries)
        mid = json.loads((folder / "manifest.json").read_text()).get("id")
        if mid in seen_ids:
            problems.append(f"duplicate id '{mid}' (also in {seen_ids[mid]})")
        seen_ids[mid] = folder.name
        print(f"{'OK  ' if not problems else 'FAIL'} {out.name:28s} id={mid}" + ("" if not problems else "  <- " + "; ".join(problems)))
        failed |= bool(problems)
    sys.exit(1 if failed else 0)


if __name__ == "__main__":
    main()
