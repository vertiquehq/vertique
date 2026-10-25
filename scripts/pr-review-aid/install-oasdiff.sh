#!/usr/bin/env bash
# SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
# SPDX-License-Identifier: EUPL-1.2
#
# Install one pinned oasdiff release binary. The workflow passes
# OASDIFF_VERSION and OASDIFF_SHA256. The checksum is verified before the
# archive is extracted. No oasdiff GitHub Action is used, so that action's
# review upload cannot run; the specs stay on the machine that invokes this
# script.

set -euo pipefail

if [[ -z "${OASDIFF_VERSION:-}" ]]; then
  echo "install-oasdiff: OASDIFF_VERSION is required" >&2
  exit 1
fi
if [[ -z "${OASDIFF_SHA256:-}" ]]; then
  echo "install-oasdiff: OASDIFF_SHA256 is required" >&2
  exit 1
fi

asset="oasdiff_${OASDIFF_VERSION}_linux_amd64.tar.gz"
url="https://github.com/oasdiff/oasdiff/releases/download/v${OASDIFF_VERSION}/${asset}"
dest="${OASDIFF_INSTALL_DIR:-${RUNNER_TEMP:-/tmp}/oasdiff-bin}"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

curl -fsSL --retry 3 -o "$tmp/$asset" "$url"

# Verify before extracting or installing.
if command -v sha256sum >/dev/null 2>&1; then
  echo "${OASDIFF_SHA256}  $tmp/$asset" | sha256sum -c -
else
  echo "${OASDIFF_SHA256}  $tmp/$asset" | shasum -a 256 -c -
fi

tar -xzf "$tmp/$asset" -C "$tmp"
if [[ ! -f "$tmp/oasdiff" ]]; then
  echo "install-oasdiff: release archive has no oasdiff binary" >&2
  exit 1
fi

mkdir -p "$dest"
cp "$tmp/oasdiff" "$dest/oasdiff"
chmod +x "$dest/oasdiff"
echo "install-oasdiff: installed ${dest}/oasdiff"
