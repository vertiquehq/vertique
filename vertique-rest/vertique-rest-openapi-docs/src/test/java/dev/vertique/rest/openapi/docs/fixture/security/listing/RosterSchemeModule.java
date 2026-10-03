// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.security.listing;

import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoSet;
import dev.vertique.rest.core.routing.SecuritySchemeRegistry;
import dev.vertique.rest.core.security.AuthEnforcementCapability;
import dev.vertique.rest.core.security.SecuritySchemeHandler;
import dev.vertique.rest.core.security.scheme.Http;
import dev.vertique.rest.core.security.scheme.SecuritySchemeDescription;
import io.vertx.ext.web.handler.AuthenticationHandler;
import java.util.Optional;

/**
 * Binds a handler for {@value #SCHEME} that rejects every request with {@code 401} and describes an
 * HTTP bearer scheme with the bearer format {@code JWT}, and the {@link AuthEnforcementCapability}
 * marker, so the mount of {@link RosterApi} builds and its document can be assembled: the route
 * registrar refuses an operation whose scheme has no handler, and a scopeless requirement is a
 * restrictive policy that needs the enforcement marker.
 */
@Module
public final class RosterSchemeModule {

    /** The security scheme the restricted operations of {@link RosterApi} require. */
    public static final String SCHEME = "rosterAuth";

    private RosterSchemeModule() {}

    /**
     * Contributes the described {@value #SCHEME} handler; a test also hands it to the assembler.
     *
     * @return a new handler
     */
    @Provides
    @IntoSet
    public static SecuritySchemeHandler rosterAuthHandler() {
        return new DescribedBearerHandler();
    }

    /**
     * Provides the auth enforcement marker.
     *
     * @return the marker instance
     */
    @Provides
    static AuthEnforcementCapability authEnforcementCapability() {
        return AuthEnforcementCapability.INSTANCE;
    }

    /** The {@value #SCHEME} handler: rejects every request and describes an HTTP bearer scheme. */
    private static final class DescribedBearerHandler implements SecuritySchemeHandler {

        @Override
        public String schemeName() {
            return SCHEME;
        }

        @Override
        public void configure(SecuritySchemeRegistry registry) {
            AuthenticationHandler rejectAll = ctx -> ctx.fail(401);
            registry.authenticationHandler(rejectAll);
        }

        @Override
        public Optional<SecuritySchemeDescription> openApiDescription() {
            return Optional.of(Http.bearer("JWT"));
        }
    }
}
