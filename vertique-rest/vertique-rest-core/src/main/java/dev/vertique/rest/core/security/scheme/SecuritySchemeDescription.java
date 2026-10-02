// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.core.security.scheme;

import java.util.Optional;

/**
 * A closed description of one OpenAPI Security Scheme. New kinds and new optional fields may be
 * added in later releases; callers must not switch exhaustively over the permitted kinds.
 *
 * <p>The sealed type limits a description to OpenAPI Security Scheme fields; what a handler puts in
 * those fields is its own responsibility. Every kind is an immutable, final class built through its
 * static factories, and rejects invalid values when built: {@code null} arguments with a
 * {@link NullPointerException}, except {@code Http.bearer}'s optional bearer format, and blank
 * strings and relative URIs with an {@link IllegalArgumentException}, each naming the offending
 * argument. A {@code with*} method returns a new instance and leaves its receiver unchanged.
 *
 * @see dev.vertique.rest.core.security.SecuritySchemeHandler#openApiDescription()
 */
public sealed interface SecuritySchemeDescription permits Http, ApiKey, OAuth2, OpenIdConnect, MutualTls {

    /**
     * The scheme's human-readable description, or empty when none was given.
     *
     * @return the description, or {@link Optional#empty()}
     */
    Optional<String> description();
}
