#!/usr/bin/env bash
# SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
# SPDX-License-Identifier: EUPL-1.2
set -euo pipefail

# Analyze in the same Maven invocation after producing the aggregate report.
# Fork and Dependabot pull requests cannot access repository secrets.
args=(-ntp -Djacoco.module.report.skip=true clean verify)
if [[ -n ${SONAR_TOKEN:-} ]]; then
  args+=(org.sonarsource.scanner.maven:sonar-maven-plugin:5.5.0.6356:sonar
    -Dsonar.projectKey=vertiquehq_vertique
    -Dsonar.organization=vertiquehq
    "-Dsonar.coverage.jacoco.xmlReportPaths=$PWD/vertique-coverage-report/target/site/jacoco-aggregate/jacoco.xml")
elif [[ ${SONAR_OPTIONAL:-false} != true ]]; then
  echo "SONAR_TOKEN is required for analysis" >&2
  exit 1
else
  echo "Sonar analysis skipped: repository secrets are unavailable for this pull request" >&2
fi
exec ./mvnw "${args[@]}"
