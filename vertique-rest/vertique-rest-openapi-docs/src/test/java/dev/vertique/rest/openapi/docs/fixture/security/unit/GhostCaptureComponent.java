// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.security.unit;

import dagger.BindsInstance;
import dagger.Component;
import dev.vertique.config.parser.ConfigParsingModule;
import dev.vertique.core.VertxConfig;
import dev.vertique.rest.core.router.HttpVerticle;
import dev.vertique.rest.jaxrs.RestModule;
import dev.vertique.rest.openapi.docs.fixture.DocsTestSupportModule;
import io.vertx.core.json.JsonObject;
import jakarta.inject.Singleton;

/**
 * The application {@link GhostApi} with its {@value GhostResource#SCHEME} handler and a {@link
 * PublicationCapture}, without the documentation module: deploying its {@link HttpVerticle} builds
 * the real mount and leaves the mount's publication, with detail, in the capture. No document is
 * assembled or served by this component.
 */
@Singleton
@Component(
        modules = {
            RestModule.class,
            ConfigParsingModule.class,
            DocsTestSupportModule.class,
            GhostRegistrationModule.class,
            GhostSchemeModule.class,
            PublicationCapture.Binding.class
        })
public interface GhostCaptureComponent {

    /**
     * Creates a new {@link HttpVerticle} of this component.
     *
     * @return a new verticle
     */
    HttpVerticle httpVerticle();

    /**
     * Resolves the component's publication capture.
     *
     * @return the capture
     */
    PublicationCapture capture();

    /** Factory taking the application configuration. */
    @Component.Factory
    interface Factory {

        /**
         * Creates the component.
         *
         * @param config the application configuration
         * @return the component
         */
        GhostCaptureComponent create(@BindsInstance @VertxConfig JsonObject config);
    }
}
