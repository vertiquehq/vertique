// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.core.convert;

/**
 * The string-ish transport kinds that parameter conversion applies to.
 *
 * <p>This is a conversion-source enum only — it is intentionally <em>not</em> a replacement for the
 * richer runtime parameter-source enums in the jaxrs and rest-client modules. Non-convertible sources
 * (body, bean-param, context, URL, upload) have no constant here because they never produce a
 * {@link ConversionContext}.
 */
public enum ParamSource {
    /** A path-template parameter ({@code @PathParam}). */
    PATH,
    /** A query-string parameter ({@code @QueryParam}). */
    QUERY,
    /** An HTTP header parameter ({@code @HeaderParam}). */
    HEADER,
    /** An HTTP cookie parameter ({@code @CookieParam}). */
    COOKIE,
    /** A form-encoded body parameter ({@code @FormParam}). */
    FORM
}
