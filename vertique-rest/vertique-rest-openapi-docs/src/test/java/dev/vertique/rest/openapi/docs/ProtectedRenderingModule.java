// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs;

import dagger.Binds;
import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoSet;
import dev.vertique.core.json.JsonMapperProfileRegistry;
import dev.vertique.rest.jaxrs.application.RestApplications;
import dev.vertique.rest.jaxrs.publication.MountPublicationHook;
import dev.vertique.rest.jaxrs.validation.OperationSchemaSource;
import jakarta.inject.Singleton;
import java.util.Optional;

/**
 * Binds one component-scoped {@link ProtectedRenderingPublicationHook} and contributes it to the publication
 * hooks, so the component renders the document of every {@code @ApiDocs} application without serving
 * it. A component exposes the hook, for example {@code ProtectedRenderingPublicationHook protectedRendering()};
 * it must then be declared in this package, since the hook is package-private.
 *
 * <p>The component lists this module and <em>not</em> {@link OpenApiDocsModule}: the documentation
 * mount is never deployed and the documentation module's hook never assembles. It compiles with
 * {@code RestModule} alone, which declares the hook set ({@code @Multibinds}), the optional schema
 * source and the optional documentation marker ({@code @BindsOptionalOf}), the declared applications,
 * and, through {@code JsonRuntimeModule}, the profile registry. Without the documentation marker,
 * declared applications carrying {@code @ApiDocs} only log that no documentation route is published.
 */
@Module
abstract class ProtectedRenderingModule {

    /**
     * Provides the component's rendering hook.
     *
     * @param schemaSource the component's bound schema source, if any
     * @param profiles     the component's profile registry
     * @param applications the component's declared applications
     * @return the hook
     */
    @Provides
    @Singleton
    static ProtectedRenderingPublicationHook protectedRenderingHook(
            Optional<OperationSchemaSource> schemaSource,
            JsonMapperProfileRegistry profiles,
            RestApplications applications) {
        return new ProtectedRenderingPublicationHook(schemaSource, profiles, applications);
    }

    /**
     * Contributes the rendering hook beside every other hook.
     *
     * @param hook the component's rendering hook
     * @return {@code hook}
     */
    @Binds
    @IntoSet
    abstract MountPublicationHook protectedRendering(ProtectedRenderingPublicationHook hook);
}
