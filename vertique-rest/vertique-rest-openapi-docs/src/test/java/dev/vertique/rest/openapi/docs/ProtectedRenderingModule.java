// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs;

import dagger.Binds;
import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoSet;
import dev.vertique.core.json.JsonMapperProfileRegistry;
import dev.vertique.rest.jaxrs.application.RestApplications;
import dev.vertique.rest.jaxrs.publication.OperationPublicationSink;
import dev.vertique.rest.jaxrs.validation.OperationSchemaSource;
import jakarta.inject.Singleton;
import java.util.Optional;

/**
 * Binds one component-scoped {@link ProtectedRenderingSink} and contributes it to the publication
 * sinks, so the component renders the document of every {@code @ApiDocs} application without serving
 * it. A component exposes the sink, for example {@code ProtectedRenderingSink protectedRendering()};
 * it must then be declared in this package, since the sink is package-private.
 *
 * <p>The component lists this module and <em>not</em> {@link OpenApiDocsModule}: the documentation
 * mount is never deployed and the documentation module's sink never assembles. It compiles with
 * {@code RestModule} alone, which declares the sink set ({@code @Multibinds}), the optional schema
 * source and the optional documentation marker ({@code @BindsOptionalOf}), the declared applications,
 * and, through {@code JsonRuntimeModule}, the profile registry. Without the documentation marker,
 * declared applications carrying {@code @ApiDocs} only log that no documentation route is published.
 */
@Module
abstract class ProtectedRenderingModule {

    /**
     * Provides the component's rendering sink.
     *
     * @param schemaSource the component's bound schema source, if any
     * @param profiles     the component's profile registry
     * @param applications the component's declared applications
     * @return the sink
     */
    @Provides
    @Singleton
    static ProtectedRenderingSink protectedRenderingSink(
            Optional<OperationSchemaSource> schemaSource,
            JsonMapperProfileRegistry profiles,
            RestApplications applications) {
        return new ProtectedRenderingSink(schemaSource, profiles, applications);
    }

    /**
     * Contributes the rendering sink beside every other sink.
     *
     * @param sink the component's rendering sink
     * @return {@code sink}
     */
    @Binds
    @IntoSet
    abstract OperationPublicationSink protectedRendering(ProtectedRenderingSink sink);
}
