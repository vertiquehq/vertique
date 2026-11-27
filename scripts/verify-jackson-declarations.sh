#!/usr/bin/env bash
# SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
# SPDX-License-Identifier: EUPL-1.2

# Proves that every module declares the Jackson artifacts its production code
# imports, instead of relying on another dependency to export them.
#
# For each Maven module (a directory with a pom.xml and a src/main tree):
#
#   - a src/main import of com.fasterxml.jackson.databind.* requires a
#     jackson-databind dependency in the module's own POM; so does an import of
#     Lombok's lombok.extern.jackson.Jacksonized, whose generated code carries
#     databind's @JsonDeserialize;
#   - a src/main import of com.fasterxml.jackson.core.* requires jackson-databind
#     or jackson-core in the module's own POM. Declaring jackson-databind covers
#     jackson-core imports, because databind's API exposes those types.
#
# A declaration counts in any scope except test. Only the module's own
# <dependencies> count: entries under <dependencyManagement> and plugin
# <dependencies> do not. Modules that need databind only at run time, through
# Vert.x JsonObject.mapFrom/mapTo or Json.encode on plain objects, import
# nothing and are not checked.
#
# Scope: every pom.xml outside build output (target/) and outside any src/
# tree, so codegen integration-test applications (src/it) and archetype
# templates (src/main/resources/archetype-resources) are not modules here.
#
# Usage: verify-jackson-declarations.sh [repository-root]
# The repository root defaults to the script's parent directory; an explicit
# root exists so scripts/test-verify-jackson-declarations.sh can drive
# synthetic fixtures.

set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd "${1:-"$script_dir/.."}" && pwd)"

# Prints the artifactIds of the POM's own non-test dependencies, one per line.
declared_artifacts() {
    perl -0777 -ne '
        s{<!--.*?-->}{}gs;
        s{<dependencyManagement>.*?</dependencyManagement>}{}gs;
        s{<build>.*?</build>}{}gs;
        s{<reporting>.*?</reporting>}{}gs;
        while (m{<dependency>(.*?)</dependency>}gs) {
            my $dep = $1;
            next if $dep =~ m{<scope>\s*test\s*</scope>};
            print "$1\n" if $dep =~ m{<artifactId>\s*([^<\s]+)\s*</artifactId>};
        }
    ' "$1"
}

# Succeeds when a Java file under $1 imports a type from package prefix $2.
imports_package() {
    local main_dir="$1" prefix="$2"
    grep -rqsE --include='*.java' "^import (static )?${prefix//./\\.}\\." "$main_dir"
}

violations=0
checked=0

while IFS= read -r relative_pom; do
    pom="$repo_root/${relative_pom#./}"
    module_dir="$(dirname "$pom")"
    main_dir="$module_dir/src/main"
    [[ -d "$main_dir" ]] || continue
    checked=$((checked + 1))
    relative="${module_dir#"$repo_root"/}"
    [[ "$module_dir" == "$repo_root" ]] && relative="."

    imports_databind=false
    imports_core=false
    imports_package "$main_dir" "com.fasterxml.jackson.databind" && imports_databind=true
    grep -rqsE --include='*.java' '^import lombok\.extern\.jackson\.Jacksonized;' "$main_dir" && imports_databind=true
    imports_package "$main_dir" "com.fasterxml.jackson.core" && imports_core=true
    [[ "$imports_databind" == true || "$imports_core" == true ]] || continue

    declared="$(declared_artifacts "$pom")"
    has_databind=false
    has_core=false
    grep -qx 'jackson-databind' <<<"$declared" && has_databind=true
    grep -qx 'jackson-core' <<<"$declared" && has_core=true

    if [[ "$imports_databind" == true && "$has_databind" == false ]]; then
        echo "$relative: src/main imports com.fasterxml.jackson.databind (or Lombok's @Jacksonized) but the POM does not declare jackson-databind" >&2
        violations=$((violations + 1))
    fi
    if [[ "$imports_core" == true && "$has_databind" == false && "$has_core" == false ]]; then
        echo "$relative: src/main imports com.fasterxml.jackson.core but the POM declares neither jackson-databind nor jackson-core" >&2
        violations=$((violations + 1))
    fi
done < <(cd "$repo_root" && find . -name pom.xml -not -path '*/target/*' -not -path '*/src/*' -not -path '*/node_modules/*' | sort)

if [[ "$violations" -gt 0 ]]; then
    echo "Jackson declarations: $violations violation(s) in $checked module(s). Declare the artifact each module imports." >&2
    exit 1
fi
echo "Jackson declarations: $checked module(s) checked, all declare the Jackson artifacts they import."
