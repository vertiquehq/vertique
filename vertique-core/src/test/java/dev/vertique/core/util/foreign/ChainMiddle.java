// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.core.util.foreign;

/**
 * Overrides the package-private method of its own package with a public one, which a class of
 * another package can in turn override: that class overrides {@link ChainBase#m()} through this
 * one (JLS 8.4.8.1).
 */
public class ChainMiddle extends ChainBase {

    /**
     * Overrides the package-private method.
     *
     * @return a marker
     */
    @Override
    public String m() {
        return "middle";
    }
}
