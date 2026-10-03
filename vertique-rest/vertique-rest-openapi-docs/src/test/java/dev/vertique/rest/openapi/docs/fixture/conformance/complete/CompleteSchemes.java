// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.conformance.complete;

import dev.vertique.rest.core.routing.SecuritySchemeRegistry;
import dev.vertique.rest.core.security.SecuritySchemeHandler;
import dev.vertique.rest.core.security.scheme.ApiKey;
import dev.vertique.rest.core.security.scheme.Http;
import dev.vertique.rest.core.security.scheme.SecuritySchemeDescription;
import io.vertx.core.Future;
import io.vertx.ext.web.handler.AuthenticationHandler;
import io.vertx.ext.web.handler.HttpException;
import io.vertx.ext.web.handler.SimpleAuthenticationHandler;
import java.util.Optional;

/**
 * The two described security scheme handlers both compositions contribute: {@value
 * CompleteEntries#BEARER_AUTH}, described as HTTP bearer with format {@code JWT}, and {@value
 * CompleteEntries#API_KEY_AUTH}, described as an API key in the {@value
 * CompleteEntries#API_KEY_HEADER} header. Each handler's authentication handler rejects every request
 * with {@code 401}; no test sends a request to a secured operation.
 */
public final class CompleteSchemes {

    private CompleteSchemes() {}

    /**
     * Returns the handler of {@value CompleteEntries#BEARER_AUTH}.
     *
     * @return a new handler
     */
    public static SecuritySchemeHandler bearerAuth() {
        return new Described(CompleteEntries.BEARER_AUTH, Http.bearer("JWT"));
    }

    /**
     * Returns the handler of {@value CompleteEntries#API_KEY_AUTH}.
     *
     * @return a new handler
     */
    public static SecuritySchemeHandler apiKeyAuth() {
        return new Described(CompleteEntries.API_KEY_AUTH, ApiKey.header(CompleteEntries.API_KEY_HEADER));
    }

    /** A handler that rejects every request and describes its scheme. */
    private static final class Described implements SecuritySchemeHandler {

        private final String schemeName;

        private final SecuritySchemeDescription description;

        Described(String schemeName, SecuritySchemeDescription description) {
            this.schemeName = schemeName;
            this.description = description;
        }

        @Override
        public String schemeName() {
            return schemeName;
        }

        @Override
        public void configure(SecuritySchemeRegistry registry) {
            // A Vert.x-built handler, not a lambda: the entry creation's two alternatives chain their
            // handlers, which requires Vert.x's internal authentication handler type.
            AuthenticationHandler rejectAll = SimpleAuthenticationHandler.create()
                    .authenticate(ctx -> Future.failedFuture(new HttpException(401)));
            registry.authenticationHandler(rejectAll);
        }

        @Override
        public Optional<SecuritySchemeDescription> openApiDescription() {
            return Optional.of(description);
        }
    }
}
