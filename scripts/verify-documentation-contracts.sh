#!/usr/bin/env bash
# SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
# SPDX-License-Identifier: EUPL-1.2
set -euo pipefail
cd "$(dirname "$0")/.."
classes=(StarterFamilyDocumentationContractTest CodegenDocumentationPolicyTest RestArchetypeContractTest RestPostgresqlArchetypeContractTest ServicesArchetypeContractTest RestOpenApiDocsHasNoOpenApiImportsTest)
modules=(vertique-starter/vertique-starter-integration-tests vertique-codegen/vertique-codegen-integration-tests vertique-archetype/vertique-archetype-rest vertique-archetype/vertique-archetype-rest-postgresql vertique-archetype/vertique-archetype-services vertique-rest/vertique-rest-openapi-docs)
if [[ ${1:-} != --check-reports ]]; then
  [[ $# == 0 ]] || { echo 'Unexpected argument' >&2; exit 1; }
  for i in "${!classes[@]}"; do
    rm -f "${modules[$i]}"/target/surefire-reports/TEST-*."${classes[$i]}".xml
  done
  ./mvnw -ntp -pl "$(IFS=,; echo "${modules[*]}")" -am -Dtest="$(IFS=,; echo "${classes[*]}")" -Dsurefire.failIfNoSpecifiedTests=false test
  report_root=.
else
  [[ $# == 2 ]] || { echo 'Expected report root' >&2; exit 1; }
  report_root=$2
fi
python3 - "$report_root" "${classes[@]}" <<'PY'
import pathlib
import sys
import xml.etree.ElementTree as ET

root = pathlib.Path(sys.argv[1])
for name in sys.argv[2:]:
    reports = list(root.glob(f'**/target/surefire-reports/TEST-*.{name}.xml'))
    if len(reports) != 1:
        sys.exit(f'{name}: expected one report, found {len(reports)}')
    try:
        suite = ET.parse(reports[0]).getroot()
        tests, failures, errors, skipped = (int(suite.attrib[key]) for key in ('tests', 'failures', 'errors', 'skipped'))
        if suite.tag != 'testsuite' or tests <= 0 or failures != 0 or errors != 0 or skipped >= tests:
            raise ValueError('no executed tests or failures/errors present')
    except (ET.ParseError, KeyError, ValueError) as exc:
        sys.exit(f'{name}: invalid report: {exc}')
    print(f'{name}: tests={tests}, failures={failures}, errors={errors}, skipped={skipped}')
PY
