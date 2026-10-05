// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.security.authz.ancestor;

import dev.vertique.security.authz.RequiresPolicy;

/** Parent whose package-private method must not contribute a policy to another package. */
public class PackageAncestor {

    @RequiresPolicy(AncestorDenyPolicy.class)
    void hidden() {}
}
