// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.security.authz;

/**
 * Top-level resource whose compiler bridge delegates to a superclass method.
 *
 * <p>A nested class does not get this bridge. The generated JAX-RS descriptor resolves the public
 * method with {@code Class.getMethod}, so this is the shape that reaches
 * {@link AccessPolicyResolver#collectMethodAnnotations}.
 */
interface InheritedBridgeOps {
    @RequiresPolicy(AccessPolicyResolverTest.AdminPolicy.class)
    String get();
}

class InheritedBridgeBase {
    public String get() {
        return "";
    }
}

public class InheritedBridgeResource extends InheritedBridgeBase implements InheritedBridgeOps {}
