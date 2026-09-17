#!/usr/bin/env python3
"""Project Dogwood -- opening the Maven Central bundle and grading it against the Portal's rules.

`./gradlew assembleMavenCentralBundle` writes, with no credentials and no network, the exact
directory tree the Central Portal validates: every artifact, its Project Object Model (POM), its
checksums and its Pretty Good Privacy (PGP) signature, under `io/github/teegarcs/...`. This opens
that tree and checks it against the requirements Sonatype publishes.

**Why a script instead of an upload.** The Portal cannot be asked without an account, and this
project does not have one yet (`OPEN-DECISIONS.md` section 5). The choice was between leaving the
bundle unchecked until somebody has an account -- which puts the discovery of a missing sources jar
after the decision to publish rather than before it -- and checking the bundle here. This is the
second. It is a proxy for the Portal's own validation and it says so: the requirements below are
transcribed from Sonatype's published list, and a rule they change is a rule this file will not
know about until somebody reads it again.

What it grades:

  M1  every coordinate has a POM carrying the metadata Central requires: group, artifact, version,
      name, description, url, a licence, a developer, and source-control information.
  M2  every coordinate whose packaging is not `pom` carries a sources jar.
  M3  ...and a javadoc jar.
  M4  every deployable file carries a detached PGP signature.
  M5  every deployable file carries md5 and sha1 checksums, and they are CORRECT -- recomputed
      here, because a checksum that is present and wrong fails validation exactly like a missing
      one and looks fine in a listing.
  M6  the bundle carries nothing that does not belong in one.

Usage:

    ./gradlew assembleMavenCentralBundle          # in engine/
    python3 tools/reference-server/portal-bundle-check.py [--bundle DIR] [--out FILE]
"""
import argparse
import hashlib
import pathlib
import sys
import tempfile
import xml.etree.ElementTree as ElementTree
import zipfile

POM_NAMESPACE = "{http://maven.apache.org/POM/4.0.0}"

# Every field Central refuses a deployment without. `parent` is not consulted: this build writes
# every field on every POM rather than inheriting, so an inherited field would be a surprise.
REQUIRED_POM_FIELDS = [
    ("groupId", "the group coordinate"),
    ("artifactId", "the artifact coordinate"),
    ("version", "the version"),
    ("name", "a human-readable project name"),
    ("description", "a description"),
    ("url", "a project URL"),
    ("licenses/license/name", "a licence name"),
    ("licenses/license/url", "a licence URL"),
    ("developers/developer/name", "a named developer"),
    ("scm/url", "a source-control URL"),
    ("scm/connection", "a source-control connection"),
    ("scm/developerConnection", "a source-control developer connection"),
]

CHECKSUM_SUFFIXES = (".md5", ".sha1", ".sha256", ".sha512")
SIGNATURE_SUFFIX = ".asc"
# Gradle writes these for a file repository; a Portal bundle is artifacts only, and the Zip task in
# `engine/build.gradle.kts` excludes them. M6 is what notices if that exclusion is ever dropped.
NOT_IN_A_BUNDLE = ("maven-metadata.xml",)


def find(element, path):
    """A POM field by slash-separated path, namespace-aware, first match, stripped."""
    node = element
    for part in path.split("/"):
        if node is None:
            return None
        node = node.find(POM_NAMESPACE + part)
    if node is None or node.text is None:
        return None
    return node.text.strip() or None


def deployable(path: pathlib.Path) -> bool:
    """A file the Portal expects a signature and checksums for: not a checksum, not a signature."""
    if path.name.endswith(CHECKSUM_SUFFIXES):
        return False
    if path.name.endswith(SIGNATURE_SUFFIX):
        return False
    return True


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    parser.add_argument(
        "--bundle",
        type=pathlib.Path,
        # The ZIP, not the directory beside it, and the difference is the point: the archive is
        # what a person uploads, so it is what gets graded. The directory holds files the Zip task
        # deliberately leaves out, and grading those would grade something nobody sends.
        default=pathlib.Path(__file__).resolve().parents[2]
        / "engine/build/publishing/dogwood-central-bundle.zip",
    )
    parser.add_argument("--out", type=pathlib.Path)
    args = parser.parse_args()

    extracted = None
    if args.bundle.is_file() and zipfile.is_zipfile(args.bundle):
        extracted = tempfile.TemporaryDirectory(prefix="dogwood-bundle-")
        with zipfile.ZipFile(args.bundle) as archive:
            archive.extractall(extracted.name)
        opened = args.bundle
        args.bundle = pathlib.Path(extracted.name)
    elif args.bundle.is_dir():
        opened = args.bundle
    else:
        print(
            f"no bundle at {args.bundle}\n"
            "  run:  cd engine && ./gradlew assembleMavenCentralBundle",
            file=sys.stderr,
        )
        return 1

    files = sorted(p for p in args.bundle.rglob("*") if p.is_file())
    poms = [p for p in files if p.suffix == ".pom"]
    if not poms:
        print(f"no POM anywhere under {args.bundle}; the bundle is empty", file=sys.stderr)
        return 1

    lines = []
    passed = failed = 0

    def conform(claim, ok, detail):
        nonlocal passed, failed
        if ok:
            passed += 1
            line = f"CONF {claim} PASS -- {detail}"
        else:
            failed += 1
            line = f"CONF {claim} FAIL -- {detail}"
        lines.append(line)
        print(line)

    # ---- M1: POM completeness -----------------------------------------------------------------
    incomplete = []
    for pom in poms:
        root = ElementTree.parse(pom).getroot()
        for path, description in REQUIRED_POM_FIELDS:
            if find(root, path) is None:
                incomplete.append(f"{pom.relative_to(args.bundle)} is missing {description} ({path})")
    conform(
        "M1",
        not incomplete,
        f"{len(poms)} POMs carry every field Central requires"
        if not incomplete
        else f"{len(incomplete)} missing field(s): " + "; ".join(incomplete[:4]),
    )

    # ---- M2, M3: sources and javadoc ----------------------------------------------------------
    missing_sources, missing_javadoc, pom_only = [], [], []
    for pom in poms:
        root = ElementTree.parse(pom).getroot()
        packaging = find(root, "packaging") or "jar"
        stem = pom.name[: -len(".pom")]
        if packaging == "pom":
            # A Gradle plugin marker, or a platform. It is a POM and nothing else; giving it a jar
            # is an error, and Central does not ask for one.
            pom_only.append(stem)
            continue
        if not (pom.parent / f"{stem}-sources.jar").exists():
            missing_sources.append(stem)
        if not (pom.parent / f"{stem}-javadoc.jar").exists():
            missing_javadoc.append(stem)
    jar_coordinates = len(poms) - len(pom_only)
    conform(
        "M2",
        not missing_sources,
        f"{jar_coordinates} jar-packaged coordinates carry a sources jar "
        f"({len(pom_only)} POM-only coordinates correctly have none)"
        if not missing_sources
        else f"{len(missing_sources)} without one: " + ", ".join(sorted(missing_sources)[:6]),
    )
    conform(
        "M3",
        not missing_javadoc,
        f"{jar_coordinates} jar-packaged coordinates carry a javadoc jar"
        if not missing_javadoc
        else f"{len(missing_javadoc)} without one: " + ", ".join(sorted(missing_javadoc)[:6]),
    )

    # ---- M4: a signature beside every deployable file -----------------------------------------
    deployables = [p for p in files if deployable(p) and p.name not in NOT_IN_A_BUNDLE]
    unsigned = [
        p.relative_to(args.bundle)
        for p in deployables
        if not p.with_name(p.name + SIGNATURE_SUFFIX).exists()
    ]
    empty_signatures = [
        p.relative_to(args.bundle)
        for p in files
        if p.name.endswith(SIGNATURE_SUFFIX)
        and "BEGIN PGP SIGNATURE" not in p.read_text(errors="replace")
    ]
    conform(
        "M4",
        not unsigned and not empty_signatures,
        f"all {len(deployables)} deployable files carry a PGP signature"
        if not unsigned and not empty_signatures
        else (
            f"{len(unsigned)} unsigned: " + ", ".join(str(p) for p in unsigned[:4])
            + (" -- is DOGWOOD_GPG_KEY set?" if len(unsigned) == len(deployables) else "")
            + (f"; {len(empty_signatures)} signature files are not PGP blocks" if empty_signatures else "")
        ),
    )

    # ---- M5: checksums, present AND correct ---------------------------------------------------
    missing_checksums, wrong_checksums = [], []
    for target in deployables:
        body = target.read_bytes()
        for suffix, algorithm in ((".md5", hashlib.md5), (".sha1", hashlib.sha1)):
            beside = target.with_name(target.name + suffix)
            if not beside.exists():
                missing_checksums.append(f"{target.relative_to(args.bundle)}{suffix}")
                continue
            if beside.read_text().strip().lower() != algorithm(body).hexdigest():
                wrong_checksums.append(f"{target.relative_to(args.bundle)}{suffix}")
    conform(
        "M5",
        not missing_checksums and not wrong_checksums,
        f"md5 and sha1 are present and correct for all {len(deployables)} files"
        if not missing_checksums and not wrong_checksums
        else f"{len(missing_checksums)} missing, {len(wrong_checksums)} wrong: "
        + ", ".join((missing_checksums + wrong_checksums)[:4]),
    )

    # ---- M6: nothing that does not belong ------------------------------------------------------
    strays = [p.relative_to(args.bundle) for p in files if p.name in NOT_IN_A_BUNDLE]
    conform(
        "M6",
        not strays,
        f"{len(files)} files, all of them artifacts, checksums or signatures"
        if not strays
        else f"{len(strays)} file(s) a Portal bundle should not carry: "
        + ", ".join(str(p) for p in strays[:4]),
    )

    lines.append(f"CONF RESULT client=central-bundle passed={passed} failed={failed} skipped=0")
    if args.out:
        args.out.parent.mkdir(parents=True, exist_ok=True)
        args.out.write_text("\n".join(lines) + "\n")

    print()
    print(f"{len(poms)} coordinates, {len(files)} files, in {opened}")
    # Said every run, because the one thing this cannot do is the thing it is standing in for.
    print(
        "This grades the bundle against Sonatype's published requirements. It does NOT ask the\n"
        "Portal, which needs an account (OPEN-DECISIONS.md section 5). A green run here means the\n"
        "bundle is complete by those rules, not that Central accepted it."
    )
    print()
    if failed:
        print(f"FAIL -- {failed} of {passed + failed}", file=sys.stderr)
        return 1
    print(f"PASS -- {passed} requirements met")
    return 0


if __name__ == "__main__":
    sys.exit(main())
