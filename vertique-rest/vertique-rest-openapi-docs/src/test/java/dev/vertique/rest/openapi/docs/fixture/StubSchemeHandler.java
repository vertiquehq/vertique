// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture;

import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoSet;
import dev.vertique.rest.core.routing.SecuritySchemeRegistry;
import dev.vertique.rest.core.security.AuthEnforcementCapability;
import dev.vertique.rest.core.security.SecuritySchemeHandler;
import io.vertx.ext.web.handler.AuthenticationHandler;

/**
 * A stub {@link SecuritySchemeHandler} for a named scheme whose authentication handler rejects every
 * request with {@code 401}, so nothing it guards is ever readable.
 */
public final class StubSchemeHandler implements SecuritySchemeHandler {

    private final String schemeName;

    /**
     * Creates a stub handler.
     *
     * @param schemeName the scheme name it registers
     */
    public StubSchemeHandler(String schemeName) {
        this.schemeName = schemeName;
    }

    @Override
    public String schemeName() {
        return schemeName;
    }

    @Override
    public void configure(SecuritySchemeRegistry registry) {
        AuthenticationHandler rejectAll = ctx -> ctx.fail(401);
        registry.authenticationHandler(rejectAll);
    }

    /**
     * Binds the {@value ProtectedMgmtApi#SECURITY_SCHEME} stub handler and the
     * {@link AuthEnforcementCapability} marker, as an installed authentication module would.
     */
    @Module
    public static final class BearerAuthWithEnforcement {

        private BearerAuthWithEnforcement() {}

        /**
         * Contributes the {@value ProtectedMgmtApi#SECURITY_SCHEME} stub handler.
         *
         * @return the stub handler
         */
        @Provides
        @IntoSet
        static SecuritySchemeHandler bearerAuthHandler() {
            return new StubSchemeHandler(ProtectedMgmtApi.SECURITY_SCHEME);
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
    }
}
