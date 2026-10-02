// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.protecteddocs.startup;

import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoSet;
import dev.vertique.rest.core.routing.SecuritySchemeRegistry;
import dev.vertique.rest.core.security.AuthEnforcementCapability;
import dev.vertique.rest.core.security.SecuritySchemeHandler;
import io.vertx.core.Future;
import io.vertx.ext.web.handler.AuthenticationHandler;
import io.vertx.ext.web.handler.HttpException;
import io.vertx.ext.web.handler.SimpleAuthenticationHandler;

/**
 * The security scheme handlers of the misconfigured-protected-document fixtures and the
 * combinations a component binds, each a nested module. A component includes exactly one of them.
 *
 * <ul>
 *   <li>{@value #BEARER_AUTH}: a handler that registers an authentication handler rejecting every
 *       request with {@code 401}.
 *   <li>{@value #EMPTY_AUTH}: a handler that is registered under its name but configures no
 *       authentication handler at all.
 * </ul>
 */
public final class StartupSchemeHandlers {

    /** The scheme whose handler registers an authentication handler. */
    public static final String BEARER_AUTH = "bearerAuth";

    /** The scheme whose handler registers no authentication handler. */
    public static final String EMPTY_AUTH = "emptyAuth";

    /** The scheme no handler registers. */
    public static final String NOPE = "nope";

    private StartupSchemeHandlers() {}

    /** A handler whose authentication handler rejects every request with {@code 401}. */
    static final class Authenticating implements SecuritySchemeHandler {

        @Override
        public String schemeName() {
            return BEARER_AUTH;
        }

        @Override
        public void configure(SecuritySchemeRegistry registry) {
            // A Vert.x-built handler, not a lambda: a route chaining scheme handlers requires Vert.x's
            // own authentication handler type.
            AuthenticationHandler rejectAll = SimpleAuthenticationHandler.create()
                    .authenticate(ctx -> Future.failedFuture(new HttpException(401)));
            registry.authenticationHandler(rejectAll);
        }
    }

    /** A handler registered under {@value #EMPTY_AUTH} that configures no authentication handler. */
    static final class Handlerless implements SecuritySchemeHandler {

        @Override
        public String schemeName() {
            return EMPTY_AUTH;
        }

        @Override
        public void configure(SecuritySchemeRegistry registry) {
            // Deliberately registers nothing.
        }
    }

    /** Binds only the authentication enforcement marker, without any scheme handler. */
    @Module
    public static final class EnforcementOnly {

        private EnforcementOnly() {}

        /**
         * Provides the authentication enforcement marker.
         *
         * @return the marker
         */
        @Provides
        static AuthEnforcementCapability authEnforcementCapability() {
            return AuthEnforcementCapability.INSTANCE;
        }
    }

    /** Binds the {@value StartupSchemeHandlers#BEARER_AUTH} handler without the enforcement marker. */
    @Module
    public static final class AuthenticatingWithoutEnforcement {

        private AuthenticatingWithoutEnforcement() {}

        /**
         * Contributes the {@value StartupSchemeHandlers#BEARER_AUTH} handler.
         *
         * @return the handler
         */
        @Provides
        @IntoSet
        static SecuritySchemeHandler authenticating() {
            return new Authenticating();
        }
    }

    /** Binds the {@value StartupSchemeHandlers#EMPTY_AUTH} handler and the enforcement marker. */
    @Module
    public static final class HandlerlessWithEnforcement {

        private HandlerlessWithEnforcement() {}

        /**
         * Contributes the {@value StartupSchemeHandlers#EMPTY_AUTH} handler.
         *
         * @return the handler
         */
        @Provides
        @IntoSet
        static SecuritySchemeHandler handlerless() {
            return new Handlerless();
        }

        /**
         * Provides the authentication enforcement marker.
         *
         * @return the marker
         */
        @Provides
        static AuthEnforcementCapability authEnforcementCapability() {
            return AuthEnforcementCapability.INSTANCE;
        }
    }

    /**
     * Binds both the {@value StartupSchemeHandlers#BEARER_AUTH} and the
     * {@value StartupSchemeHandlers#EMPTY_AUTH} handlers and the enforcement marker.
     */
    @Module
    public static final class BothWithEnforcement {

        private BothWithEnforcement() {}

        /**
         * Contributes the {@value StartupSchemeHandlers#BEARER_AUTH} handler.
         *
         * @return the handler
         */
        @Provides
        @IntoSet
        static SecuritySchemeHandler authenticating() {
            return new Authenticating();
        }

        /**
         * Contributes the {@value StartupSchemeHandlers#EMPTY_AUTH} handler.
         *
         * @return the handler
         */
        @Provides
        @IntoSet
        static SecuritySchemeHandler handlerless() {
            return new Handlerless();
        }

        /**
         * Provides the authentication enforcement marker.
         *
         * @return the marker
         */
        @Provides
        static AuthEnforcementCapability authEnforcementCapability() {
            return AuthEnforcementCapability.INSTANCE;
        }
    }
}
