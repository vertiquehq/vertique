// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.application.unitb.membership;

import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoSet;
import dev.vertique.core.VertxConfig;
import dev.vertique.rest.jaxrs.runtime.GeneratedJaxRsApplicationRegistration;
import io.vertx.core.json.JsonObject;

/**
 * TP-005 case 16's registration module: {@code type()} is {@link MembershipDeclaredApplication},
 * but the factory constructs and returns a {@link MembershipWrongTypeApplication} instance, so
 * C-COMPOSE step 4's type check ({@code registration.type().isInstance(...)}) fails immediately.
 */
@Module
public final class MembershipMismatchedFactoryRegistrationModule {

    /**
     * Registers {@link MembershipDeclaredApplication}, unconditionally active, with a factory that
     * returns an unrelated {@link MembershipWrongTypeApplication} instance instead.
     *
     * @param config the application configuration (unused)
     * @return the registration, always active
     */
    @Provides
    @IntoSet
    static GeneratedJaxRsApplicationRegistration membershipMismatchedFactoryRegistration(
            @VertxConfig JsonObject config) {
        return GeneratedJaxRsApplicationRegistration.of(
                MembershipDeclaredApplication.class,
                MembershipDeclaredApplication.PATH,
                true,
                MembershipWrongTypeApplication::new);
    }
}
