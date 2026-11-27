#!/usr/bin/env bash
# SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
# SPDX-License-Identifier: EUPL-1.2

# Regression harness for scripts/verify-jackson-declarations.sh.
#
# Each case materializes a synthetic Maven module tree under a temporary
# directory and invokes the checker against that fixture root. A case declares
# the outcome the checker is contractually required to produce; the harness
# fails when the observed outcome differs.
#
# The real repository is deliberately NOT a case here: the release contract
# test runs the checker against the real tree as its own assertion.

set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
verifier="$script_dir/verify-jackson-declarations.sh"

if [[ ! -f "$verifier" ]]; then
    echo "Checker under test is missing: $verifier" >&2
    exit 1
fi

work_dir="$(mktemp -d "${TMPDIR:-/tmp}/vertique-jackson-declarations-tests-XXXXXXXX")"
trap 'rm -rf "$work_dir"' EXIT

unexpected_outcomes=0
executed_cases=0
fixture_root=""

# Starts a new fixture named $1.
fixture_reset() {
    fixture_root="$work_dir/$1"
    mkdir -p "$fixture_root"
}

# Writes module $1's pom.xml with the dependency blocks read from standard input.
fixture_pom() {
    mkdir -p "$fixture_root/$1"
    {
        echo '<project>'
        cat
        echo '</project>'
    } > "$fixture_root/$1/pom.xml"
}

# Writes a Java source at path $2 under module $1 importing the type $3.
fixture_java() {
    local destination="$fixture_root/$1/$2"
    mkdir -p "$(dirname "$destination")"
    printf 'package p;\n\nimport %s;\n\nclass C {}\n' "$3" > "$destination"
}

# Writes a Java source at path $2 under module $1 with the literal content from standard input.
fixture_java_raw() {
    local destination="$fixture_root/$1/$2"
    mkdir -p "$(dirname "$destination")"
    cat > "$destination"
}

# Writes a <dependencies> block with one dependency on artifact $1, in scope $2 (optional).
dependency() {
    local scope=""
    [[ -n "${2:-}" ]] && scope="<scope>$2</scope>"
    printf '<dependencies><dependency><groupId>com.fasterxml.jackson.core</groupId><artifactId>%s</artifactId>%s</dependency></dependencies>\n' "$1" "$scope"
}

# Runs the checker on the current fixture and compares the exit outcome with $2 (pass|fail).
expect() {
    local name="$1" expected="$2" observed
    executed_cases=$((executed_cases + 1))
    if bash "$verifier" "$fixture_root" > "$work_dir/out.txt" 2>&1; then observed=pass; else observed=fail; fi
    if [[ "$observed" != "$expected" ]]; then
        echo "UNEXPECTED: $name: expected $expected, observed $observed" >&2
        sed 's/^/    /' "$work_dir/out.txt" >&2
        unexpected_outcomes=$((unexpected_outcomes + 1))
    fi
}

databind_import='com.fasterxml.jackson.databind.ObjectMapper'
core_import='com.fasterxml.jackson.core.JsonParser'
main_file='src/main/java/p/C.java'

fixture_reset databind-undeclared
fixture_pom m </dev/null
fixture_java m "$main_file" "$databind_import"
expect "databind import without a declaration" fail

fixture_reset databind-compile
dependency jackson-databind | fixture_pom m
fixture_java m "$main_file" "$databind_import"
expect "databind import with a compile declaration" pass

fixture_reset databind-runtime
dependency jackson-databind runtime | fixture_pom m
fixture_java m "$main_file" "$databind_import"
expect "databind import with a runtime declaration" pass

fixture_reset databind-test-scope
dependency jackson-databind test | fixture_pom m
fixture_java m "$main_file" "$databind_import"
expect "databind import with only a test-scope declaration" fail

fixture_reset databind-managed-only
printf '<dependencyManagement>%s</dependencyManagement>\n' "$(dependency jackson-databind)" | fixture_pom m
fixture_java m "$main_file" "$databind_import"
expect "databind declared only under dependencyManagement" fail

fixture_reset databind-plugin-only
printf '<build><plugins><plugin><artifactId>x</artifactId>%s</plugin></plugins></build>\n' "$(dependency jackson-databind)" | fixture_pom m
fixture_java m "$main_file" "$databind_import"
expect "databind declared only as a plugin dependency" fail

fixture_reset databind-commented-out
printf '<!-- %s -->\n' "$(dependency jackson-databind)" | fixture_pom m
fixture_java m "$main_file" "$databind_import"
expect "databind declaration commented out" fail

fixture_reset databind-static-import
fixture_pom m </dev/null
fixture_java m "$main_file" "static com.fasterxml.jackson.databind.SerializationFeature.INDENT_OUTPUT"
expect "databind static import without a declaration" fail

fixture_reset core-covered-by-databind
dependency jackson-databind | fixture_pom m
fixture_java m "$main_file" "$core_import"
expect "jackson-core import covered by a databind declaration" pass

fixture_reset core-declared
dependency jackson-core | fixture_pom m
fixture_java m "$main_file" "$core_import"
expect "jackson-core import with a jackson-core declaration" pass

fixture_reset core-undeclared
fixture_pom m </dev/null
fixture_java m "$main_file" "$core_import"
expect "jackson-core import without any declaration" fail

fixture_reset databind-import-core-declared
dependency jackson-core | fixture_pom m
fixture_java m "$main_file" "$databind_import"
expect "databind import with only jackson-core declared" fail

fixture_reset test-only-import
fixture_pom m </dev/null
fixture_java m 'src/test/java/p/C.java' "$databind_import"
expect "databind import only in test sources" pass

fixture_reset annotations-only
fixture_pom m </dev/null
fixture_java m "$main_file" 'com.fasterxml.jackson.annotation.JsonProperty'
expect "jackson-annotations import needs no databind" pass

fixture_reset it-application-ignored
fixture_pom m </dev/null
fixture_pom m/src/it/app </dev/null
fixture_java m/src/it/app "$main_file" "$databind_import"
expect "an application under src/it is not a module" pass

fixture_reset target-ignored
fixture_pom m </dev/null
fixture_pom m/target/copy </dev/null
fixture_java m/target/copy "$main_file" "$databind_import"
expect "build output under target/ is not a module" pass

fixture_reset nested-module
fixture_pom parent </dev/null
fixture_pom parent/child </dev/null
fixture_java parent/child "$main_file" "$databind_import"
expect "a nested module is checked against its own POM" fail

fixture_reset databind-provided
dependency jackson-databind provided | fixture_pom m
fixture_java m "$main_file" "$databind_import"
expect "databind import with a provided declaration" pass

fixture_reset databind-profile
printf '<profiles><profile><id>p</id>%s</profile></profiles>\n' "$(dependency jackson-databind)" | fixture_pom m
fixture_java m "$main_file" "$databind_import"
expect "databind declared in a profile dependencies block" pass

fixture_reset databind-profile-plugin-only
printf '<profiles><profile><id>p</id><build><plugins><plugin><artifactId>x</artifactId>%s</plugin></plugins></build></profile></profiles>\n' "$(dependency jackson-databind)" | fixture_pom m
fixture_java m "$main_file" "$databind_import"
expect "databind declared only as a profile plugin dependency" fail

fixture_reset databind-import-in-comment
fixture_pom m </dev/null
printf 'package p;\n\n// import %s;\n/*\n import %s;\n */\nclass C {}\n' "$databind_import" "$databind_import" | fixture_java_raw m "$main_file"
expect "databind import only inside comments" pass

fixture_reset core-annotations-declared-only
dependency jackson-annotations | fixture_pom m
fixture_java m "$main_file" "$core_import"
expect "jackson-core import with only jackson-annotations declared" fail

fixture_reset core-test-scope
dependency jackson-core test | fixture_pom m
fixture_java m "$main_file" "$core_import"
expect "jackson-core import with only a test-scope declaration" fail

fixture_reset root-module-undeclared
fixture_root="$work_dir/root-module-undeclared"
mkdir -p "$fixture_root"
printf '<project></project>\n' > "$fixture_root/pom.xml"
printf 'package p;\n\nimport %s;\n\nclass C {}\n' "$databind_import" > "$fixture_root/x.java"
mkdir -p "$fixture_root/src/main/java/p"
cp "$fixture_root/x.java" "$fixture_root/$main_file"
rm "$fixture_root/x.java"
expect "a root-level module is checked against the root POM" fail

if [[ "$unexpected_outcomes" -gt 0 ]]; then
    echo "verify-jackson-declarations self-test: $unexpected_outcomes of $executed_cases case(s) unexpected" >&2
    exit 1
fi
echo "verify-jackson-declarations self-test: $executed_cases case(s) passed"
