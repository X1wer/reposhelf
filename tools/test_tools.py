# SPDX-FileCopyrightText: 2026 RepoShelf contributors
# SPDX-License-Identifier: GPL-3.0-or-later
"""A kiadási és függőség-ellenőrző eszközök tesztjei (hálózat nélkül). Futtatás: python3 -I -m unittest discover -s tools"""

from __future__ import annotations

import pathlib
import sys
import tempfile
import unittest

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))

import osv_check  # noqa: E402
import release_tools as rt  # noqa: E402

CHANGELOG = """# Changelog

## [Unreleased]

- semmi

## [0.2.0] - 2026-12-01

### Added
- Új funkció.

## [0.1.0] - 2026-11-20

- Első kiadás.
"""


class ReleaseToolsTest(unittest.TestCase):
    def test_version_is_read_and_validated(self):
        self.assertEqual(("0.1.0", 1), rt.read_version("# x\nreposhelf.versionName=0.1.0\nreposhelf.versionCode=1\n"))
        for bad in ("reposhelf.versionName=1.0\nreposhelf.versionCode=1", "reposhelf.versionName=1.0.0\nreposhelf.versionCode=0",
                    "reposhelf.versionName=1.0.0 ; rm\nreposhelf.versionCode=1", "reposhelf.versionCode=1"):
            with self.assertRaises(rt.ReleaseError):
                rt.read_version(bad)

    def test_tag_must_match_version(self):
        rt.check_tag("v0.1.0", "0.1.0")
        for tag in ("0.1.0", "v0.1.1", "v0.1.0-rc1", "refs/tags/v0.1.0"):
            with self.assertRaises(rt.ReleaseError):
                rt.check_tag(tag, "0.1.0")

    def test_changelog_section_is_extracted_exactly(self):
        self.assertEqual("### Added\n- Új funkció.\n", rt.changelog_section(CHANGELOG, "0.2.0"))
        self.assertEqual("- Első kiadás.\n", rt.changelog_section(CHANGELOG, "0.1.0"))
        with self.assertRaises(rt.ReleaseError):
            rt.changelog_section(CHANGELOG, "0.3.0")
        with self.assertRaises(rt.ReleaseError):
            rt.changelog_section("## [1.0.0]\n\n## [0.9.0]\n- x\n", "1.0.0")
        with self.assertRaises(rt.ReleaseError):
            rt.changelog_section("## [1.0.0]\n- a\n## [1.0.0]\n- b\n", "1.0.0")

    def test_sums_use_sha256sum_format(self):
        with tempfile.TemporaryDirectory() as tmp:
            b = pathlib.Path(tmp, "b.apk")
            a = pathlib.Path(tmp, "a.apk")
            b.write_bytes(b"")
            a.write_bytes(b"abc")
            self.assertEqual(
                "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad  a.apk\n"
                "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855  b.apk\n",
                rt.sha256sums([b, a]),
            )


class OsvCheckTest(unittest.TestCase):
    TREE = """
githubReleaseRuntimeClasspath - Resolved configuration for runtime for variant: githubRelease
+--- project :core:model
|    \\--- org.jetbrains.kotlinx:kotlinx-serialization-json:1.12.0
|         \\--- org.jetbrains.kotlinx:kotlinx-serialization-core:1.12.0 (*)
+--- androidx.core:core-ktx:1.19.1
|    +--- androidx.annotation:annotation:1.8.1 -> 1.9.1 (*)
|    \\--- org.jetbrains.kotlin:kotlin-stdlib:{strictly 2.4.21} -> 2.4.21 (c)
+--- com.squareup.okhttp3:okhttp:5.5.1
\\--- androidx.compose:compose-bom:2026.09.00 (n)
"""

    def test_dependency_tree_uses_resolved_versions(self):
        self.assertEqual({
            "org.jetbrains.kotlinx:kotlinx-serialization-json:1.12.0",
            "org.jetbrains.kotlinx:kotlinx-serialization-core:1.12.0",
            "androidx.core:core-ktx:1.19.1",
            "androidx.annotation:annotation:1.9.1",
            "org.jetbrains.kotlin:kotlin-stdlib:2.4.21",
            "com.squareup.okhttp3:okhttp:5.5.1",
        }, osv_check.parse_dependency_tree(self.TREE))

    def test_verification_metadata_components(self):
        xml = """<?xml version="1.0"?>
<verification-metadata xmlns="https://schema.gradle.org/dependency-verification">
  <components>
    <component group="junit" name="junit" version="4.13.2"><artifact name="junit-4.13.2.jar"/></component>
    <component group="com.android.tools.build" name="aapt2" version="9.4.1-14500000"/>
  </components>
</verification-metadata>"""
        self.assertEqual({"junit:junit:4.13.2", "com.android.tools.build:aapt2:9.4.1-14500000"},
                         osv_check.parse_verification_metadata(xml))


if __name__ == "__main__":
    unittest.main()
