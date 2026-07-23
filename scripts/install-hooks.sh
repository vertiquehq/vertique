#!/usr/bin/env bash
# SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
# SPDX-License-Identifier: EUPL-1.2

#
# Installs project Git hooks into .git/hooks/.
# Run once after cloning: ./scripts/install-hooks.sh

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
GIT_DIR="$(git -C "$SCRIPT_DIR" rev-parse --git-dir)"
HOOKS_DIR="$GIT_DIR/hooks"

install_hook() {
    local name="$1"
    local src="$SCRIPT_DIR/$name"
    local dst="$HOOKS_DIR/$name"

    if [ ! -f "$src" ]; then
        echo "  ✗ Hook script not found: $src"
        return 1
    fi

    cp "$src" "$dst"
    chmod +x "$dst"
    echo "  ✓ Installed $name"
}

echo "Installing Git hooks..."
install_hook "commit-msg"
echo "Done. Hooks active in $HOOKS_DIR"
