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
 * G-07 (a)'s registration module: {@code type()} is {@link MembershipDeclaredApplication} (reused
 * purely for its type and path constants; its own constructor is never called here), but the
 * factory returns {@code null} instead of an instance — the hand-written-registration shape a
 * generated factory never produces, but a hand-written one legally can.
 */
@Module
public final class MembershipNullFactoryRegistrationModule {

    /**
     * Registers {@link MembershipDeclaredApplication}, unconditionally active, with a factory that
     * always returns {@code null}.
     *
     * @param config the application configuration (unused)
     * @return the registration, always active
     */
    @Provides
    @IntoSet
    static GeneratedJaxRsApplicationRegistration membershipNullFactoryRegistration(@VertxConfig JsonObject config) {
        return GeneratedJaxRsApplicationRegistration.of(
                MembershipDeclaredApplication.class,
                MembershipDeclaredApplication.PATH,
                true,
                MembershipNullFactoryRegistrationModule::alwaysNull);
    }

    /**
     * Always returns {@code null}, standing in for a hand-written factory that fails to construct
     * an instance.
     *
     * @return {@code null}, always
     */
    private static MembershipDeclaredApplication alwaysNull() {
        return null;
    }
}
