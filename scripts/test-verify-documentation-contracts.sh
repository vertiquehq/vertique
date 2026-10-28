#!/usr/bin/env bash
# SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
# SPDX-License-Identifier: EUPL-1.2
set -euo pipefail
cd "$(dirname "$0")/.."
fixture=$(mktemp -d)
trap 'rm -rf "$fixture"' EXIT
classes=(StarterFamilyDocumentationContractTest CodegenDocumentationPolicyTest RestArchetypeContractTest RestPostgresqlArchetypeContractTest ServicesArchetypeContractTest RestOpenApiDocsHasNoOpenApiImportsTest)
mkdir -p "$fixture/module/target/surefire-reports"
for class in "${classes[@]}"; do
  printf '<testsuite tests="2" failures="0" errors="0" skipped="0"/>\n' > "$fixture/module/target/surefire-reports/TEST-example.$class.xml"
done
check() { bash scripts/verify-documentation-contracts.sh --check-reports "$fixture" >/dev/null 2>&1; }
check
report="$fixture/module/target/surefire-reports/TEST-example.${classes[0]}.xml"
for xml in '<testsuite tests="0" failures="0" errors="0" skipped="0"/>' '<testsuite tests="2" failures="1" errors="0" skipped="0"/>' '<testsuite tests="2" failures="0" errors="1" skipped="0"/>' '<testsuite tests="2" failures="0" errors="0" skipped="2"/>' '<testsuite tests="2" failures="0" errors="0" skipped="1"/>' '<broken'; do
  printf '%s\n' "$xml" > "$report"
  if check; then echo "Invalid report accepted: $xml" >&2; exit 1; fi
done
rm "$report"
if check; then echo 'Missing report accepted' >&2; exit 1; fi
echo 'Documentation contract report verifier self-test passed'
