// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.application.conflict.opid;

import dev.vertique.rest.jaxrs.application.RestApplication;

/**
 * TP-009's ported cross-mount operationId fixtures (the pre-existing lettered cases (a) to (f)),
 * each a native {@code @RestApplication} declaring interface in place of the former {@code
 * jakarta.ws.rs.core.Application} subclass. Never implemented: native composition reads each
 * interface's registration directly.
 */
public final class OpidApis {

    private OpidApis() {}

    /** Case (a): lists only {@link OpidAlphaListResource}. */
    @RestApplication(name = "opid-alpha", path = "/opid/alpha", resources = OpidAlphaListResource.class)
    public interface OpidAlphaApi {}

    /** Case (a): lists only {@link OpidBetaListResource}. */
    @RestApplication(name = "opid-beta", path = "/opid/beta", resources = OpidBetaListResource.class)
    public interface OpidBetaApi {}

    /** Case (b): lists {@link OpidSharedListResource}, shared with {@link OpidShareTwoApi}. */
    @RestApplication(name = "opid-share-one", path = "/opid/share-one", resources = OpidSharedListResource.class)
    public interface OpidShareOneApi {}

    /** Case (b): lists {@link OpidSharedListResource}, shared with {@link OpidShareOneApi}. */
    @RestApplication(name = "opid-share-two", path = "/opid/share-two", resources = OpidSharedListResource.class)
    public interface OpidShareTwoApi {}

    /** Case (d): lists {@link OpidInheritedFirstResource}, a distinct subclass inheriting {@code list()}. */
    @RestApplication(
            name = "opid-inherited-first",
            path = "/opid/inherited-first",
            resources = OpidInheritedFirstResource.class)
    public interface OpidInheritedFirstApi {}

    /** Case (d): lists {@link OpidInheritedSecondResource}, a distinct subclass inheriting {@code list()}. */
    @RestApplication(
            name = "opid-inherited-second",
            path = "/opid/inherited-second",
            resources = OpidInheritedSecondResource.class)
    public interface OpidInheritedSecondApi {}

    /** Case (f): lists {@link OpidAopBaseResource}, matched only by an AOP-proxy-shaped manual subclass. */
    @RestApplication(name = "opid-aop", path = "/opid/aop", resources = OpidAopBaseResource.class)
    public interface OpidAopApi {}

    /** Case (e): unconditionally inactive; the sole registration in its component. */
    @RestApplication(name = "opid-inactive", path = "/opid/inactive", resources = OpidInactiveResource.class)
    public interface OpidInactiveApi {}
}
