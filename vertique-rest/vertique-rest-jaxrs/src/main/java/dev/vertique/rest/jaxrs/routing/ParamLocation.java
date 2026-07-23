// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.routing;

/**
 * The bindable request-parameter locations exposed through the public
 * {@link JaxRsOperationDescriptor#parameters()} SPI.
 *
 * <p>These are the locations a declared scalar/collection parameter can be bound from. The request
 * body is modeled separately by {@link BodyDescriptor} and is therefore not a member here.
 * Non-bindable parameter sources (such as {@code CONTEXT} or {@code BEAN_PARAM}) are dispatch
 * internals of {@code ResourceMethodMeta} and are intentionally not exposed through this SPI.
 */
public enum ParamLocation {
    /** Value extracted from a URI path segment (e.g. {@code /items/{id}}). */
    PATH,
    /** Value extracted from a URI query parameter. */
    QUERY,
    /** Value extracted from an HTTP request header. */
    HEADER,
    /** Value extracted from a request cookie. */
    COOKIE,
    /** Form field from a {@code multipart/form-data} or {@code application/x-www-form-urlencoded} request. */
    FORM
}
