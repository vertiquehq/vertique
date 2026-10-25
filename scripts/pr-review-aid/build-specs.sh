#!/usr/bin/env bash
# SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
# SPDX-License-Identifier: EUPL-1.2
#
# Compile the checkout and copy every generated OpenAPI document to SPECS_OUT,
# keyed by the module path. Modules are discovered from pom.xml files that
# declare an OpenAPI output file name, then built with their reactor
# dependencies. Tests are not run. The copy step still collects whatever
# target/classes/openapi.yaml the build wrote.

set -euo pipefail

if [[ -z "${CHECKOUT:-}" ]]; then
  echo "build-specs: CHECKOUT is required" >&2
  exit 1
fi
if [[ -z "${SPECS_OUT:-}" ]]; then
  echo "build-specs: SPECS_OUT is required" >&2
  exit 1
fi

pl=""
while IFS= read -r pom; do
  if grep -q '<outputFileName>openapi</outputFileName>' "$pom"; then
    dir="$(dirname "$pom")"
    dir="${dir#"$CHECKOUT"/}"
    if [[ -n "$pl" ]]; then
      pl="${pl},${dir}"
    else
      pl="$dir"
    fi
  fi
done < <(find "$CHECKOUT" -name pom.xml -not -path '*/target/*' -not -path '*/.git/*' -not -path '*/pr-review-aid-out/*' | LC_ALL=C sort)

mkdir -p "$SPECS_OUT"

if [[ -z "$pl" ]]; then
  echo "build-specs: no module generates openapi.yaml"
  exit 0
fi

echo "build-specs: ./mvnw -ntp -B -DskipTests -Darchetype.test.skip=true -pl ${pl} -am compile"
( cd "$CHECKOUT" && ./mvnw -ntp -B -DskipTests -Darchetype.test.skip=true -pl "$pl" -am compile )

while IFS= read -r spec; do
  rel="${spec#"$CHECKOUT"/}"
  mod="${rel%/target/classes/openapi.yaml}"
  mkdir -p "$SPECS_OUT/$mod"
  cp "$spec" "$SPECS_OUT/$mod/openapi.yaml"
done < <(find "$CHECKOUT" -path '*/target/classes/openapi.yaml' -not -path '*/pr-review-aid-out/*' | LC_ALL=C sort)
