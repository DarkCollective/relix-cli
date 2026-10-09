#!/usr/bin/env python3
"""Write a release's checksums, Homebrew formula and Scoop manifest, and check them.

Usage:
    release.py VERSION DIST OUT

DIST holds the five platform archives the release workflow built. OUT receives:

    checksums.txt   the archives' SHA-256 sums, as `sha256sum` prints them
    relix.rb        packaging/homebrew/relix.rb with the version and checksums filled in
    relix.json      packaging/scoop/relix.json, likewise

Then it reads the formula and the manifest back and fails unless every archive each
one names is in DIST under that name with that checksum, which is what a dry-run
release proves before anything is published.
"""
import hashlib
import json
import pathlib
import re
import sys

PLATFORMS = ["macos-aarch64", "macos-x86_64", "linux-aarch64", "linux-x86_64", "windows-x86_64"]
PACKAGING = pathlib.Path(__file__).resolve().parent


def archive_name(version: str, platform: str) -> str:
    extension = "zip" if platform.startswith("windows") else "tar.gz"
    return f"relix-{version}-{platform}.{extension}"


def sha256(path: pathlib.Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as f:
        for block in iter(lambda: f.read(1 << 20), b""):
            digest.update(block)
    return digest.hexdigest()


def fill(template: str, version: str, sums: dict) -> str:
    text = template.replace("@VERSION@", version)
    for platform, digest in sums.items():
        text = text.replace(f"@SHA256:{platform}@", digest)
    left = re.findall(r"@[A-Z0-9:_-]+@", text)
    if left:
        sys.exit(f"placeholders left unfilled: {', '.join(sorted(set(left)))}")
    return text


def formula_archives(formula: str, version: str) -> dict:
    """The archive name and checksum of each url/sha256 pair in the formula."""
    pairs = re.findall(r'url "([^"]+)"\s*\n\s*sha256 "([0-9a-f]{64})"', formula)
    return {url.replace("#{version}", version).rsplit("/", 1)[1]: digest for url, digest in pairs}


def manifest_archives(manifest: dict) -> dict:
    """The archive name and checksum of each architecture in the manifest."""
    return {entry["url"].rsplit("/", 1)[1]: entry["hash"]
            for entry in manifest["architecture"].values()}


def check(name: str, named: dict, expected: set, dist: pathlib.Path) -> list:
    problems = []
    if set(named) != expected:
        problems.append(f"{name} names {sorted(named)}, not {sorted(expected)}")
    for archive, digest in named.items():
        path = dist / archive
        if not path.is_file():
            problems.append(f"{name} names {archive}, which is not in {dist}")
        elif sha256(path) != digest:
            problems.append(f"{name} gives {archive} the checksum {digest}, not {sha256(path)}")
    return problems


def main() -> None:
    if len(sys.argv) != 4:
        sys.exit(__doc__)
    version, dist, out = sys.argv[1], pathlib.Path(sys.argv[2]), pathlib.Path(sys.argv[3])
    if not re.fullmatch(r"\d+\.\d+\.\d+(-[0-9A-Za-z.]+)?", version):
        sys.exit(f"'{version}' is not a version such as 1.2.0 or 1.2.0-rc1")

    archives = {platform: dist / archive_name(version, platform) for platform in PLATFORMS}
    missing = [str(path) for path in archives.values() if not path.is_file()]
    if missing:
        sys.exit("missing archives:\n  " + "\n  ".join(missing))
    sums = {platform: sha256(path) for platform, path in archives.items()}

    out.mkdir(parents=True, exist_ok=True)
    (out / "checksums.txt").write_text(
        "".join(f"{sums[p]}  {archives[p].name}\n" for p in PLATFORMS), encoding="utf-8")
    formula = fill((PACKAGING / "homebrew" / "relix.rb").read_text(encoding="utf-8"), version, sums)
    (out / "relix.rb").write_text(formula, encoding="utf-8")
    manifest = fill((PACKAGING / "scoop" / "relix.json").read_text(encoding="utf-8"), version, sums)
    (out / "relix.json").write_text(manifest, encoding="utf-8")

    unix = {archive_name(version, p) for p in PLATFORMS if not p.startswith("windows")}
    windows = {archive_name(version, p) for p in PLATFORMS if p.startswith("windows")}
    problems = (check("relix.rb", formula_archives(formula, version), unix, dist)
                + check("relix.json", manifest_archives(json.loads(manifest)), windows, dist))
    if problems:
        sys.exit("\n".join(problems))
    print(f"relix {version}: checksums.txt, relix.rb and relix.json in {out}; "
          f"every archive they name matches its checksum")


if __name__ == "__main__":
    main()
