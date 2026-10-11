// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.security.authz.chain;

import jakarta.annotation.security.RolesAllowed;

/** A secured package-private method, the first link of a package-private chain. */
public class ChainBase {

    @RolesAllowed("admin")
    String m() {
        return "base";
    }
}
