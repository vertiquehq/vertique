// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.application;

/**
 * INTERNAL marker interface signalling that the OpenAPI documentation module is present in this
 * component. Outside the maturity promise: not an application contract, and never implemented by
 * hand-written application code.
 *
 * <p>{@code RestModule} declares this as {@code @BindsOptionalOf}, so a component resolves {@code
 * Optional<ApiDocsModuleInstalled>} whether or not the docs module is included. The docs module's own
 * Dagger module binds a trivial implementation when it is present; rest-jaxrs never depends on the
 * docs module itself, so it cannot bind — only detect — this marker.
 *
 * <p>{@link #ANNOTATION_NAME} is the fully qualified name of the {@code @ApiDocs} annotation
 * ({@code dev.vertique.rest.openapi.docs.ApiDocs}), read by reflection so rest-jaxrs can recognize
 * it on a declaring interface without a compile-time dependency on the docs module. Reflection
 * drops an annotation whose class is absent at runtime, so a lookup by this name observes {@code
 * @ApiDocs} only when the docs artifact is on the classpath — whether or not the docs module
 * itself is included in this component.
 */
public interface ApiDocsModuleInstalled {

    /**
     * The fully qualified name of the {@code @ApiDocs} annotation
     * ({@code dev.vertique.rest.openapi.docs.ApiDocs}), used to recognize it by reflection without
     * a compile-time dependency on the docs module that declares it.
     */
    String ANNOTATION_NAME = "dev.vertique.rest.openapi.docs.ApiDocs";
}
