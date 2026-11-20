#!/usr/bin/env bash
# SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
# SPDX-License-Identifier: EUPL-1.2

# Proves that vertique-http-client adds no third-party artifact beyond what
# vertique-core and vertique-resilience already resolve, plus io.vertx:vertx-web-client
# and the artifacts it brings itself.
#
# Method: one Maven run executes `dependency:tree -am` (verbose, runtime scope, which
# is compile plus runtime and excludes test and provided) for both modules. The
# script reads the two trees and checks that every third-party artifact (anything not
# in the dev.vertique group) of the http-client tree is either in the
# vertique-resilience tree or is io.vertx:vertx-web-client or one of its own
# transitive dependencies. Artifacts compare by groupId:artifactId.
#
# It fails closed: a module missing from the reactor, a Maven failure, an empty tree or
# a tree without any third-party artifact is a failure, because a comparison over
# nothing would pass for the wrong reason. Every offending artifact is named on stderr.
#
# Usage: verify-http-client-dependency-tree.sh [repository-root]
#        verify-http-client-dependency-tree.sh --trees <core-tree-file> <resilience-tree-file>
# The second form compares two verbose dependency-tree text files without running
# Maven. It exists so the comparison itself can be exercised against synthetic trees.

set -euo pipefail

core_module="vertique-http-client"
reference_module="vertique-resilience"
allowed_root="io.vertx:vertx-web-client"
tree_name="http-client-dependency-tree.txt"

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

compare_trees() {
    python3 -I - "$1" "$2" "$allowed_root" <<'PYTHON'
import re
import sys

core_file, reference_file, allowed_root = sys.argv[1], sys.argv[2], sys.argv[3]
FIRST_PARTY = "dev.vertique"
ARTIFACT = re.compile(r"^(?P<prefix>(?:[|+\\ -]{3})*[+\\]- )\(?(?P<gav>[^\s()]+)(?P<rest>.*)$")


def read(path):
    """Return (depth, 'group:artifact', omitted) for every dependency line, root excluded."""
    entries = []
    with open(path, encoding="utf-8") as handle:
        for index, line in enumerate(handle):
            line = line.rstrip("\n")
            if not line.strip():
                continue
            match = ARTIFACT.match(line)
            if index == 0 or match is None:
                continue
            fields = match.group("gav").split(":")
            if len(fields) < 4:
                continue
            depth = len(match.group("prefix")) // 3
            omitted = "omitted for" in match.group("rest") or line.lstrip("|+\\- ").startswith("(")
            entries.append((depth, fields[0] + ":" + fields[1], omitted))
    return entries


def resolved_third_party(entries):
    return {key for _, key, omitted in entries if not omitted and key.split(":")[0] != FIRST_PARTY}


core = read(core_file)
reference = read(reference_file)
core_third_party = resolved_third_party(core)
reference_third_party = resolved_third_party(reference)

if not core_third_party:
    sys.exit("FAIL: no third-party artifact in the %s tree; the comparison would be vacuous" % core_file)
if not reference_third_party:
    sys.exit("FAIL: no third-party artifact in the %s tree; the comparison would be vacuous" % reference_file)

# The allowed root and everything below it in the core tree (omitted duplicates included).
allowed = set()
for position, (depth, key, omitted) in enumerate(core):
    if key == allowed_root and not omitted:
        allowed.add(key)
        for child_depth, child_key, _ in core[position + 1:]:
            if child_depth <= depth:
                break
            allowed.add(child_key)
if allowed_root not in allowed:
    sys.exit("FAIL: %s is not in the core tree; the allowance has nothing to anchor to" % allowed_root)

extra = sorted(core_third_party - reference_third_party - allowed)
for artifact in extra:
    print("FAIL: third-party artifact not in the vertique-resilience tree and not from vertx-web-client: " + artifact,
          file=sys.stderr)
print("%d third-party artifacts in the core tree, %d in the resilience tree, %d allowed through vertx-web-client, %d extra"
      % (len(core_third_party), len(reference_third_party), len(allowed), len(extra)))
sys.exit(1 if extra else 0)
PYTHON
}

if [[ "${1:-}" == "--trees" ]]; then
    if (( $# != 3 )); then
        echo "Usage: $0 --trees <core-tree-file> <resilience-tree-file>" >&2
        exit 2
    fi
    for tree in "$2" "$3"; do
        if [[ ! -s "$tree" ]]; then
            echo "Tree file is missing or empty: $tree" >&2
            exit 1
        fi
    done
    compare_trees "$2" "$3"
    exit $?
fi

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

for module in "$core_module" "$reference_module"; do
    if [[ ! -f "$repository_root/$module/pom.xml" ]]; then
        echo "FAIL: module $module does not exist (no $module/pom.xml)" >&2
        exit 1
    fi
done

log="$(mktemp "${TMPDIR:-/tmp}/verify-http-client-dependency-tree-XXXXXXXX")"
trap 'rm -f "$log"' EXIT

echo "Resolving dependency trees for $core_module and $reference_module"
if ! (cd "$repository_root" && ./mvnw -ntp -B \
        -pl "$core_module,$reference_module" -am \
        dependency:tree -Dscope=runtime -Dverbose=true \
        -DoutputType=text "-DoutputFile=target/$tree_name" >"$log" 2>&1); then
    tail -n 40 "$log" >&2
    echo "FAIL: dependency:tree failed for $core_module and $reference_module" >&2
    exit 1
fi

core_tree="$repository_root/$core_module/target/$tree_name"
reference_tree="$repository_root/$reference_module/target/$tree_name"
for tree in "$core_tree" "$reference_tree"; do
    if [[ ! -s "$tree" ]]; then
        echo "FAIL: dependency:tree wrote no tree at $tree" >&2
        exit 1
    fi
done

compare_trees "$core_tree" "$reference_tree"
