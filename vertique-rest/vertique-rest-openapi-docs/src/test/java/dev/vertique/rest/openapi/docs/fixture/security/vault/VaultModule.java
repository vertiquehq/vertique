// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.security.vault;

import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoSet;
import dev.vertique.rest.core.dagger.JaxRsResources;
import dev.vertique.rest.core.security.AuthEnforcementCapability;
import dev.vertique.rest.core.security.SecuritySchemeHandler;
import dev.vertique.rest.jaxrs.runtime.GeneratedRestApplicationRegistration;
import java.util.List;

/**
 * Registers the application {@code vault}, declared by {@link VaultApi}, exactly as the generated
 * registration module does ({@code GeneratedRestApplicationRegistration.of(declaringType, name,
 * path, resources, false, "", true)}); contributes {@link VaultResource} as a manual
 * {@code @JaxRsResources} instance; and binds the {@link UndescribedVaultHandler} and the {@link
 * AuthEnforcementCapability} marker, as an installed authentication module would.
 */
@Module
public final class VaultModule {

    private VaultModule() {}

    /**
     * Registers {@link VaultApi}.
     *
     * @return the registration
     */
    @Provides
    @IntoSet
    static GeneratedRestApplicationRegistration registration() {
        return GeneratedRestApplicationRegistration.of(
                VaultApi.class, VaultApi.NAME, VaultApi.PATH, List.of(VaultResource.class), false, "", true);
    }

    /**
     * Contributes {@link VaultResource}.
     *
     * @return a new resource instance
     */
    @Provides
    @IntoSet
    @JaxRsResources
    static Object vaultResource() {
        return new VaultResource();
    }

    /**
     * Contributes the counting, undescribed {@value UndescribedVaultHandler#VAULT_AUTH} handler.
     *
     * @return the handler
     */
    @Provides
    @IntoSet
    static SecuritySchemeHandler vaultAuthHandler() {
        return new UndescribedVaultHandler();
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
