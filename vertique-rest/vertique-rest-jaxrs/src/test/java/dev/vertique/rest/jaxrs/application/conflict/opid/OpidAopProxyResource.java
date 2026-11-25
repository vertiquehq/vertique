// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.application.conflict.opid;

import jakarta.inject.Inject;

/**
 * Operation-id conflict case (f)'s AOP-proxy-shaped direct subclass of {@link OpidAopBaseResource}:
 * {@code public final}, extends the bean directly, adds no interface, and marks its override with
 * only the source-retained {@code @Override} — no other class or method annotation, no parameter
 * annotations (matching the shape {@code AopProxyEmitter} generates).
 * {@code sameSurface} accepts exactly this shape, so {@link OpidApis.OpidAopApi}'s listing of
 * {@link OpidAopBaseResource} successfully selects this instance, and the validator's owner rule
 * normalizes this operation to {@link OpidAopBaseResource}, the same owner as
 * {@link OpidAopHandBuiltMountModule}'s unproxied instance's operation.
 */
public final class OpidAopProxyResource extends OpidAopBaseResource {

    /** Public {@code @Inject} constructor. */
    @Inject
    public OpidAopProxyResource() {}

    /**
     * Overrides {@link OpidAopBaseResource#list()}, keeping the same method name and
     * adding no annotation beyond the source-retained {@code @Override}.
     *
     * @return the base implementation's result
     */
    @Override
    public String list() {
        return super.list();
    }
}
