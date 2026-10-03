// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.startup;

import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoSet;
import dev.vertique.rest.core.security.AuthEnforcementCapability;
import dev.vertique.rest.core.security.SecuritySchemeHandler;
import dev.vertique.rest.openapi.docs.fixture.StubSchemeHandler;

/**
 * Combinations of security scheme handlers and the {@link AuthEnforcementCapability} marker, each a
 * nested module binding {@link StubSchemeHandler} instances and, where named, the marker. A
 * component includes at most one of them.
 */
public final class SchemeBindings {

    /** The scheme name protected fixtures declare. */
    public static final String BEARER_AUTH = "bearerAuth";

    /** A scheme name no fixture declares. */
    public static final String OTHER_AUTH = "otherAuth";

    private SchemeBindings() {}

    /** Binds only a handler named {@value SchemeBindings#OTHER_AUTH} and the enforcement marker. */
    @Module
    public static final class OtherAuthWithEnforcement {

        private OtherAuthWithEnforcement() {}

        /**
         * Contributes the {@value SchemeBindings#OTHER_AUTH} stub handler.
         *
         * @return the stub handler
         */
        @Provides
        @IntoSet
        static SecuritySchemeHandler otherAuthHandler() {
            return new StubSchemeHandler(OTHER_AUTH);
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

    /** Binds only a handler named {@value SchemeBindings#BEARER_AUTH}, without the enforcement marker. */
    @Module
    public static final class BearerAuthOnly {

        private BearerAuthOnly() {}

        /**
         * Contributes the {@value SchemeBindings#BEARER_AUTH} stub handler.
         *
         * @return the stub handler
         */
        @Provides
        @IntoSet
        static SecuritySchemeHandler bearerAuthHandler() {
            return new StubSchemeHandler(BEARER_AUTH);
        }
    }

    /** Binds only the enforcement marker, without any scheme handler. */
    @Module
    public static final class EnforcementOnly {

        private EnforcementOnly() {}

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

    /**
     * Binds a handler named {@value SchemeBindings#BEARER_AUTH} and the enforcement marker, by
     * including {@link StubSchemeHandler.BearerAuthWithEnforcement}.
     */
    @Module(includes = StubSchemeHandler.BearerAuthWithEnforcement.class)
    public static final class BearerAuthWithEnforcement {

        private BearerAuthWithEnforcement() {}
    }
}
