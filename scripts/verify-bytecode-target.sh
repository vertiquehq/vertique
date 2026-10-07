#!/usr/bin/env bash
# SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
# SPDX-License-Identifier: EUPL-1.2

# Proves that a build on a newer JDK did not raise the class-file version of what
# the framework ships. Every compiled class under a module's target/classes must
# carry the class-file major version of the root pom's <java.version> (major =
# 44 + feature release, so Java 21 is 65). A class with any other major means a
# module compiled without --release, and would fail to load on the minimum
# supported runtime.
#
# Scope: *.class files under */target/classes/. Two trees are skipped on purpose:
# target/it holds invoker fixture projects that are built by their own Maven
# invocation, and META-INF/versions holds multi-release overlays that
# legitimately target a newer release.
#
# The check fails closed: finding no compiled classes is a failure, because a
# scan over nothing would pass for the wrong reason.
#
# Usage: verify-bytecode-target.sh [repository-root]
# The repository root defaults to the script's parent directory; an explicit
# root exists so scripts/test-verify-bytecode-target.sh can drive synthetic
# fixtures. A fixture root supplies its own pom.xml with <java.version>.

set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

if (( $# > 1 )); then
    echo "Usage: $0 [repository-root]" >&2
    exit 2
fi

repository_root_input="${1:-$script_dir/..}"
if [[ ! -d "$repository_root_input" ]]; then
    echo "Repository root does not exist: $repository_root_input" >&2
    exit 1
fi
repository_root="$(cd "$repository_root_input" && pwd)"

pom="$repository_root/pom.xml"
if [[ ! -f "$pom" ]]; then
    echo "No pom.xml at the repository root: $repository_root" >&2
    exit 1
fi

java_version="$(sed -n 's|.*<java\.version>\([0-9][0-9]*\)</java\.version>.*|\1|p' "$pom" | head -n 1)"
if [[ -z "$java_version" ]]; then
    echo "Could not read <java.version> from $pom" >&2
    exit 1
fi
expected_major=$((44 + java_version))

python3 -I - "$repository_root" "$expected_major" "$java_version" <<'PYTHON'
import os
import sys

root, expected, release = sys.argv[1], int(sys.argv[2]), sys.argv[3]
seen = 0
bad = []
for directory, subdirectories, files in os.walk(root):
    relative = os.path.relpath(directory, root).replace(os.sep, "/")
    # Do not descend into fixture projects, VCS metadata, or multi-release overlays.
    subdirectories[:] = [
        name for name in subdirectories
        if not (
            name == ".git"
            or (name == "it" and relative.endswith("/target"))
            or (name == "versions" and relative.endswith("META-INF"))
        )
    ]
    if "/target/classes" not in "/" + relative + "/":
        continue
    for name in files:
        if not name.endswith(".class"):
            continue
        path = os.path.join(directory, name)
        with open(path, "rb") as handle:
            head = handle.read(8)
        seen += 1
        major = int.from_bytes(head[6:8], "big")
        if major != expected:
            bad.append((os.path.relpath(path, root), major))

if seen == 0:
    sys.exit("FAIL: no compiled classes found under */target/classes; the check would be vacuous")
for path, major in bad[:20]:
    print(f"FAIL: class-file major {major}, expected {expected} (Java {release}): {path}", file=sys.stderr)
print(f"{seen} classes checked, {len(bad)} off the Java {release} target (major {expected})")
sys.exit(1 if bad else 0)
PYTHON
