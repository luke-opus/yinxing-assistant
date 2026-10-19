"""Copy pinned community subscriptions and notices into the APK's offline assets.

SPDX-License-Identifier: GPL-3.0-only
"""

from pathlib import Path
import argparse
import hashlib
import json
from zipfile import ZipFile

ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / "gkd-app/src/main/assets/xadskip/community"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true", help="Verify committed assets without writing")
    parser.add_argument("--apk-dir", type=Path, help="Also verify the single APK in this directory")
    args = parser.parse_args()
    origins = json.loads((ROOT / "UPSTREAM.json").read_text(encoding="utf-8"))
    upstream = origins["gkdSubscriptions"]
    snapshots = json.loads((ROOT / "rules/community-snapshots.json").read_text(encoding="utf-8"))["sources"]
    expected = {}
    notice = ["XAdSkip 内置社区规则 / Bundled community subscriptions", "",
              "原始规则与署名按各来源许可证保留。导入后使用独立订阅标识，关闭网络更新。",
              "Original rules and notices are preserved. Imported copies use separate IDs and offline updates.", ""]
    for name in ("ganlinte", "aoguai"):
        source = upstream[name]
        snapshot = snapshots[name]
        assert source["commit"] == snapshot["commit"]
        folder = ROOT / source["path"]
        paths = ["dist/" + Path(source["subscriptionFile"]).name, "LICENSE", "README.md"]
        if name == "aoguai":
            paths.append("LEGAL.md")
        for relative in paths:
            data = (folder / relative).read_bytes()
            assert hashlib.sha256(data).hexdigest() == snapshot["files"][relative], relative
            expected[f"{name}/{Path(relative).name}"] = data
        notice.extend([f"{name}: {source['url']}", f"Commit: {source['commit']}",
                       f"Subscription version: {source['subscriptionVersion']}",
                       f"License: {source['license']} ({name}/LICENSE)", ""])
    ltt = origins["lttRules"]
    ltt_license = (ROOT / "third_party/ltt/LICENSE").read_bytes()
    assert hashlib.sha256(ltt_license).hexdigest() == ltt["files"]["LICENSE"], "ltt/LICENSE"
    expected["ltt/LICENSE"] = ltt_license
    notice.extend([
        f"ltt migration drafts: {ltt['url']}",
        f"Commit: {ltt['commit']}",
        f"License: {ltt['license']}",
        "Bundled license: ltt/LICENSE (unmodified upstream text)",
        "Draft asset: assets/xadskip/ltt-drafts.json",
        "XAdSkip 迁移草稿由上述社区规则转换，默认关闭；不是李跳跳应用源码或官方续作。",
        "XAdSkip migration drafts are converted from these community rules and disabled by default;",
        "they are not the LiTiaotiao application's source code or an official continuation.",
        "",
    ])
    expected["NOTICE.txt"] = ("\n".join(notice) + "\n").encode("utf-8")
    existing = {p.relative_to(ASSETS).as_posix() for p in ASSETS.rglob("*") if p.is_file()}
    assert not existing - expected.keys(), f"Unexpected asset files: {existing - expected.keys()}"
    for relative, data in expected.items():
        destination = ASSETS / relative
        if args.check:
            assert destination.read_bytes() == data, f"Bundled asset differs: {relative}"
        else:
            destination.parent.mkdir(parents=True, exist_ok=True)
            destination.write_bytes(data)
    print(f"{'Verified' if args.check else 'Bundled'} {len(expected)} community rule and notice assets")
    if args.apk_dir:
        apks = list(args.apk_dir.glob("*.apk"))
        assert len(apks) == 1, f"Expected one APK, found {len(apks)}"
        with ZipFile(apks[0]) as apk:
            assert apk.testzip() is None, "APK ZIP integrity check failed"
            for relative, data in expected.items():
                assert apk.read(f"assets/xadskip/community/{relative}") == data, relative
            assert apk.read("assets/xadskip/ltt-drafts.json") == (ASSETS.parent / "ltt-drafts.json").read_bytes()
        print(f"Verified all three bundled subscriptions and community notices inside {apks[0].name}")


if __name__ == "__main__":
    main()
