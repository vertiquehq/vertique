// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.security.unit;

import dev.vertique.rest.core.routing.SecuritySchemeRegistry;
import dev.vertique.rest.core.security.ApiKey;
import dev.vertique.rest.core.security.SecuritySchemeDescription;
import dev.vertique.rest.core.security.SecuritySchemeHandler;
import java.util.Optional;

/**
 * One of two handlers that both provide {@value GhostResource#SCHEME}, describing it as a query API key. Its
 * binary name differs from its twin's so a failure can name both.
 */
public final class GhostQueryKeyHandler implements SecuritySchemeHandler {

    /** Creates the handler. */
    public GhostQueryKeyHandler() {}

    @Override
    public String schemeName() {
        return GhostResource.SCHEME;
    }

    @Override
    public void configure(SecuritySchemeRegistry registry) {
        // registers nothing: the handler is only described, never started
    }

    @Override
    public Optional<SecuritySchemeDescription> openApiDescription() {
        return Optional.of(ApiKey.query("api_key"));
    }
}
