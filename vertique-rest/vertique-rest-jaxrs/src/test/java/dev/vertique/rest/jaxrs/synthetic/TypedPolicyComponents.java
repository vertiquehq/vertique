// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.synthetic;

import dagger.Component;
import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoSet;
import dev.vertique.rest.core.router.RouterMount;
import dev.vertique.rest.jaxrs.RestModule;
import dev.vertique.rest.security.AuthModule;
import dev.vertique.rest.security.SecurityModule;
import jakarta.inject.Singleton;

/**
 * The supported security composition of {@link TypedSyntheticComponents.Legacy} with the typed policy
 * operations of {@link TypedPolicyMount} mounted at {@code /typed}.
 */
public final class TypedPolicyComponents {

    private TypedPolicyComponents() {}

    /** The supported composition serving one synthetic operation per fixture policy. */
    @Singleton
    @Component(
            modules = {
                RestModule.class,
                AuthModule.class,
                SecurityModule.class,
                TypedSyntheticModules.ConfigSupport.class,
                TypedSyntheticModules.Observing.class,
                TypedSyntheticModules.BearerScheme.class,
                TypedSyntheticModules.OneRouteAuthHandler.class,
                TypedSyntheticModules.RegisteredActions.class,
                TypedSyntheticModules.CountingAuthorizer.class,
                PolicyMounts.class
            })
    public interface Policies extends TypedSyntheticComponents.Deployable {}

    /** Contributes the typed policy operations mount to {@link Policies}. */
    @Module
    static final class PolicyMounts {

        private PolicyMounts() {}

        @Provides
        @IntoSet
        static RouterMount policyMount(
                SyntheticOperationInstaller operations, TypedSyntheticObservations observations) {
            return new TypedPolicyMount(operations, observations);
        }
    }
}
