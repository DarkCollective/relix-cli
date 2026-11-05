"""Tests of release.py and of the release workflow's rule for the tap and the bucket.

Run with: python3 -m unittest discover -s packaging -p 'test_*.py'
"""
import json
import pathlib
import re
import tempfile
import unittest

import release

ROOT = pathlib.Path(__file__).resolve().parent.parent
WORKFLOW = (ROOT / ".github" / "workflows" / "release.yml").read_text(encoding="utf-8")


def archives(version: str, directory: pathlib.Path) -> None:
    for platform in release.PLATFORMS:
        (directory / release.archive_name(version, platform)).write_bytes(platform.encode())


class ReleaseFiles(unittest.TestCase):
    def render(self, version: str):
        with tempfile.TemporaryDirectory() as tmp:
            dist, out = pathlib.Path(tmp, "dist"), pathlib.Path(tmp, "out")
            dist.mkdir()
            archives(version, dist)
            import sys
            argv, sys.argv = sys.argv, ["release.py", version, str(dist), str(out)]
            try:
                release.main()
            finally:
                sys.argv = argv
            return ((out / "relix.rb").read_text(encoding="utf-8"),
                    json.loads((out / "relix.json").read_text(encoding="utf-8")),
                    (out / "checksums.txt").read_text(encoding="utf-8"))

    def test_a_release_candidate_fills_the_formula_and_the_manifest(self):
        formula, manifest, sums = self.render("1.0.0-rc3")
        self.assertIn('version "1.0.0-rc3"', formula)
        self.assertEqual("1.0.0-rc3", manifest["version"])
        self.assertEqual("relix-1.0.0-rc3-windows-x86_64.zip",
                         manifest["architecture"]["64bit"]["url"].rsplit("/", 1)[1])
        self.assertEqual("relix-1.0.0-rc3", manifest["architecture"]["64bit"]["extract_dir"])
        self.assertEqual(5, len(sums.splitlines()))

    def test_a_stable_release_fills_them_too(self):
        formula, manifest, _ = self.render("1.0.0")
        self.assertIn('version "1.0.0"', formula)
        self.assertEqual("1.0.0", manifest["version"])

    def test_the_formula_names_the_archives_release_py_checks(self):
        formula, _, _ = self.render("1.0.0-rc3")
        self.assertEqual(
            {release.archive_name("1.0.0-rc3", p) for p in release.PLATFORMS if "windows" not in p},
            set(release.formula_archives(formula, "1.0.0-rc3")))

    def test_a_missing_archive_fails(self):
        with tempfile.TemporaryDirectory() as tmp:
            import sys
            argv, sys.argv = sys.argv, ["release.py", "1.0.0-rc3", tmp, tmp + "/out"]
            try:
                with self.assertRaises(SystemExit):
                    release.main()
            finally:
                sys.argv = argv

    def test_a_version_that_is_not_one_fails(self):
        import sys
        argv, sys.argv = sys.argv, ["release.py", "rc3", ".", "out"]
        try:
            with self.assertRaises(SystemExit):
                release.main()
        finally:
            sys.argv = argv


class TapWorkflow(unittest.TestCase):
    def job(self, name: str) -> str:
        match = re.search(rf"^  {name}:\n(.*?)(?=^  \w[\w-]*:\n|\Z)", WORKFLOW, re.S | re.M)
        self.assertIsNotNone(match, f"no job {name}")
        return match.group(1)

    def test_the_tap_job_runs_for_a_release_candidate(self):
        tap = self.job("tap")
        self.assertNotIn("stable", tap, "the tap job must not be skipped for a pre-release")
        self.assertNotRegex(tap, r"(?m)^    if:")
        self.assertIn("publish", re.search(r"needs: (.*)", tap).group(1))

    def test_both_package_managers_are_pushed(self):
        tap = self.job("tap")
        self.assertIn("homebrew-relix", tap)
        self.assertIn("scoop-relix", tap)

    def test_a_candidate_is_still_a_github_pre_release(self):
        self.assertIn("--prerelease", self.job("publish"))


if __name__ == "__main__":
    unittest.main()
