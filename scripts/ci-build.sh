#!/usr/bin/env bash
# SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
# SPDX-License-Identifier: EUPL-1.2
set -euo pipefail

# PR builds never receive secrets. Analyze the verified main-branch build in
# the same Maven invocation, after the aggregate coverage report is produced.
args=(-ntp -Djacoco.module.report.skip=true clean verify)
if [[ ${EVENT_NAME:-} == push ]]; then
  : "${SONAR_TOKEN:?SONAR_TOKEN is required for main-branch analysis}"
  args+=(org.sonarsource.scanner.maven:sonar-maven-plugin:5.5.0.6356:sonar
    -Dsonar.projectKey=vertiquehq_vertique
    -Dsonar.organization=vertiquehq
    "-Dsonar.coverage.jacoco.xmlReportPaths=$PWD/vertique-coverage-report/target/site/jacoco-aggregate/jacoco.xml")
fi
exec ./mvnw "${args[@]}"
