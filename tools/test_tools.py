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
import third_party_notices as tpn  # noqa: E402

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

    def test_notes_are_unwrapped_but_structure_and_code_stay(self):
        text = (
            "First release.\n\n### Added\n\n- One item that is\n  wrapped by hand.\n- Second item\n  - nested item\n\n"
            "Paragraph line one\nline two.\n\n```\nkeep  this\nas is\n```\n| a | b |\n| - | - |\n"
        )
        self.assertEqual(
            "First release.\n\n### Added\n\n- One item that is wrapped by hand.\n- Second item\n  - nested item\n\n"
            "Paragraph line one line two.\n\n```\nkeep  this\nas is\n```\n| a | b |\n| - | - |\n",
            rt.unwrap(text),
        )

    def test_relative_links_become_absolute_for_the_tag(self):
        text = "See [SECURITY.md](SECURITY.md#known-limitations), [guide](./USER_GUIDE.md), [site](https://x.example/a), [top](#added)."
        self.assertEqual(
            "See [SECURITY.md](https://github.com/X1wer/reposhelf/blob/v0.2.0/SECURITY.md#known-limitations), "
            "[guide](https://github.com/X1wer/reposhelf/blob/v0.2.0/USER_GUIDE.md), [site](https://x.example/a), [top](#added).",
            rt.absolute_links(text, "X1wer/reposhelf", "v0.2.0"),
        )
        with self.assertRaises(rt.ReleaseError):
            rt.absolute_links(text, "not a repo", "v0.2.0")

    def test_verification_section_lists_signer_and_sums_in_a_code_block(self):
        fp = "7aa8f53bc27116f891e8539e8f0914918356bbb918e6a0261360552e0d67b3d7"
        self.assertEqual([fp], rt.release_signers(f"# x\nreposhelf.selfUpdate.signerSha256={fp}\n"))
        for bad in ("reposhelf.selfUpdate.signerSha256=\n", "reposhelf.selfUpdate.signerSha256=ABC\n", "other=1\n"):
            with self.assertRaises(rt.ReleaseError):
                rt.release_signers(bad)
        sums = "a" * 64 + "  RepoShelf-v0.2.0.apk\n"
        section = rt.verification_section(sums, [fp])
        self.assertIn("### Verification", section)
        self.assertIn(f"`{fp}`", section)
        self.assertIn("```\n" + "a" * 64 + "  RepoShelf-v0.2.0.apk\n```\n", section)
        with self.assertRaises(rt.ReleaseError):
            rt.verification_section("not a sum line\n", [fp])

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


def _pom(licenses: str = "", parent: str = "", url: str = "") -> str:
    return (f'<project xmlns="http://maven.apache.org/POM/4.0.0">{parent}'
            f'{"<url>" + url + "</url>" if url else ""}{licenses}</project>')


class ThirdPartyNoticesTest(unittest.TestCase):
    def _cache(self, root: pathlib.Path, coord: str, pom: str) -> None:
        group, artifact, version = coord.split(":")
        folder = root / group / artifact / version / "abc123"
        folder.mkdir(parents=True)
        (folder / f"{artifact}-{version}.pom").write_text(pom, encoding="utf-8")

    def test_licenses_are_read_from_pom_or_parent_and_rendered(self):
        with tempfile.TemporaryDirectory() as tmp:
            cache = pathlib.Path(tmp)
            self._cache(cache, "org.example:lib:1.0", _pom(
                "<licenses><license><name>The Apache Software License, Version 2.0</name></license></licenses>",
                url="https://example.org/lib"))
            self._cache(cache, "org.example:parent:2", _pom(
                "<licenses><license><name>BSD-2-Clause</name></license></licenses>", url="https://example.org/p"))
            self._cache(cache, "org.example:child:2", _pom(
                parent="<parent><groupId>org.example</groupId><artifactId>parent</artifactId><version>2</version></parent>"))
            rows = tpn.collect({"org.example:lib:1.0", "org.example:child:2"}, cache)
            self.assertEqual([("child", "BSD-2-Clause", "https://example.org/p"), ("lib", "Apache-2.0", "https://example.org/lib")],
                             [(r["artifact"], r["license"], r["url"]) for r in rows])
            block = tpn.render(rows)
            self.assertIn("2 components (Apache-2.0: 1, BSD-2-Clause: 1).", block)
            self.assertIn("| `org.example:lib` | 1.0 | Apache-2.0 | <https://example.org/lib> |", block)
            doc = f"# Notices\n\n{tpn.BEGIN}\nold\n{tpn.END}\n\n## Tail\n"
            self.assertEqual(f"# Notices\n\n{block}\n\n## Tail\n", tpn.replace_block(doc, block))

    def test_unknown_or_missing_license_is_rejected(self):
        with tempfile.TemporaryDirectory() as tmp:
            cache = pathlib.Path(tmp)
            self._cache(cache, "org.example:gpl2:1", _pom("<licenses><license><name>GPL-2.0-only</name></license></licenses>"))
            self._cache(cache, "org.example:none:1", _pom())
            with self.assertRaises(tpn.NoticeError) as error:
                tpn.collect({"org.example:gpl2:1", "org.example:none:1"}, cache)
            self.assertIn("UNKNOWN(GPL-2.0-only)", str(error.exception))
            self.assertIn("MISSING", str(error.exception))
            with self.assertRaises(tpn.NoticeError):
                tpn.collect({"org.example:absent:1"}, cache)
            with self.assertRaises(tpn.NoticeError):
                tpn.replace_block("no markers", "x")


if __name__ == "__main__":
    unittest.main()
