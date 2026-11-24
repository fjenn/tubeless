#!/usr/bin/env python3
"""Writes the AltStore / SideStore source for one release.

    altstore/make-source.py --repo owner/name --tag v1.9.0 --ipa Tubeless-1.9.0-ios.ipa \
        --notes "what changed" [--previous old-altstore.json] [--branch trunk] -o altstore.json

The source is attached to every GitHub release as `altstore.json`, so its stable address is
https://github.com/<repo>/releases/latest/download/altstore.json (no commit back to the repo).
--previous (the source from the release before, if any) keeps older versions listed.
Reads only the files it is given; no network.
"""
import argparse, datetime, json, os, pathlib, re, sys

HERE = pathlib.Path(__file__).resolve().parent


def main(argv=None):
    ap = argparse.ArgumentParser()
    ap.add_argument("--repo", required=True)
    ap.add_argument("--tag", required=True)
    ap.add_argument("--ipa", required=True)
    ap.add_argument("--notes", default="")
    ap.add_argument("--build", default="", help="buildVersion (app.versionCode)")
    ap.add_argument("--previous", help="the source published with the release before")
    ap.add_argument("--branch", default="trunk")
    ap.add_argument("--min-os", default="16.0")
    ap.add_argument("-o", "--out", required=True)
    a = ap.parse_args(argv)

    if not re.fullmatch(r"[\w.-]+/[\w.-]+", a.repo):
        sys.exit(f"--repo must be owner/name, got {a.repo!r}")
    version = a.tag[1:] if a.tag.startswith("v") else a.tag
    ipa = pathlib.Path(a.ipa)
    text = (HERE / "source.template.json").read_text()
    text = text.replace("@REPO@", a.repo).replace("@BRANCH@", a.branch)
    src = json.loads(text)
    app = src["apps"][0]

    entry = {
        "version": version,
        "buildVersion": a.build or version,
        "date": datetime.datetime.now(datetime.timezone.utc).replace(microsecond=0).isoformat(),
        "localizedDescription": a.notes or f"Tubeless {version}",
        "downloadURL": f"https://github.com/{a.repo}/releases/download/{a.tag}/{ipa.name}",
        "size": ipa.stat().st_size,
        "minOSVersion": a.min_os,
    }
    older = []
    if a.previous and os.path.exists(a.previous):
        try:
            prev = json.load(open(a.previous))
            older = [v for v in prev["apps"][0].get("versions", []) if v.get("version") != version]
        except (ValueError, KeyError, IndexError) as e:
            print(f"previous source unreadable, starting fresh: {e}", file=sys.stderr)
    app["versions"] = [entry] + older[:29]
    # the flat fields older AltStore versions read
    app.update(version=entry["version"], versionDate=entry["date"],
               versionDescription=entry["localizedDescription"],
               downloadURL=entry["downloadURL"], size=entry["size"])
    pathlib.Path(a.out).write_text(json.dumps(src, indent=2, ensure_ascii=False) + "\n")
    print(f"{a.out}: {app['name']} {version}, {len(app['versions'])} version(s)")


if __name__ == "__main__":
    main()
