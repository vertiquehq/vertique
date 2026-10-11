// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.jaxrs.gip.chain;

/** Overrides the package-private route publicly, so a class of another package can override it too. */
public class ChainMiddle extends ChainBase {

    /**
     * Overrides the secured package-private route.
     *
     * @param id the identifier
     * @return a marker
     */
    @Override
    public String find(String id) {
        return "middle " + id;
    }
}
