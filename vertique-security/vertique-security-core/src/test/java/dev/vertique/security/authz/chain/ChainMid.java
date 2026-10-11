// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.security.authz.chain;

/** Overrides the package-private method publicly, so a class of another package overrides it too. */
public class ChainMid extends ChainBase {

    /**
     * Overrides the secured package-private method.
     *
     * @return a marker
     */
    @Override
    public String m() {
        return "mid";
    }
}
