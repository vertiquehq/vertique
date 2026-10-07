// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.jaxrs.hidden;

import dev.vertique.security.authz.AccessPolicy;
import dev.vertique.security.authz.RequiresPolicy;
import jakarta.annotation.security.PermitAll;

/** Package-private method in another package. It must not replace a subclass type policy. */
public class ForeignHiddenBase {

    @PermitAll
    public interface OpenPolicy extends AccessPolicy {}

    @RequiresPolicy(OpenPolicy.class)
    String get() {
        return "";
    }
}
