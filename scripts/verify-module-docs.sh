#!/usr/bin/env bash
# SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
# SPDX-License-Identifier: EUPL-1.2


set -euo pipefail

repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
index="$repository_root/docs/modules.md"

while IFS= read -r link; do
    target="${link#../}"
    if [[ ! -f "$repository_root/$target" ]]; then
        echo "Missing canonical module document: $target" >&2
        exit 1
    fi
done < <(sed -n 's/.*](\\([^)]*module\\.md\\)).*/\\1/p' "$index")

if rg -n 'vertique-(audit|blob|camel)|benchmarks' "$index"; then
    echo "Module index contains a non-public artifact" >&2
    exit 1
fi

echo "PASS: public module-documentation links resolve"
