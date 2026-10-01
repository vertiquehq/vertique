// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs;

import dagger.BindsInstance;
import dagger.Component;
import dev.vertique.config.parser.ConfigParsingModule;
import dev.vertique.core.VertxConfig;
import dev.vertique.rest.core.router.HttpVerticle;
import dev.vertique.rest.jaxrs.RestModule;
import dev.vertique.rest.openapi.docs.fixture.DocsTestSupportModule;
import dev.vertique.rest.openapi.docs.fixture.corpus.CorpusRegistrationModule;
import dev.vertique.rest.openapi.docs.fixture.input.InputValidationModule;
import dev.vertique.rest.openapi.docs.fixture.input.RecordingSchemaSource;
import io.vertx.core.json.JsonObject;
import jakarta.inject.Singleton;

/**
 * The Dagger test component of the embedding-equivalence corpus: the documented {@code corpus}
 * application under the {@code web-validation} strategy, with the documentation module.
 *
 * <p>The schema source is the canonical, annotation-driven source wrapped by a {@link
 * RecordingSchemaSource}, which returns the canonical result unchanged and keeps a deep copy of
 * every schema the validation gate received. No Bean Validation {@code Validator} is bound, so the
 * canonical source describes bodies exactly as the input-direction generator does on its own.
 */
public final class EquivalenceTestComponents {

    private EquivalenceTestComponents() {}

    /** The corpus application, its recording schema source, and the documentation module. */
    @Singleton
    @Component(
            modules = {
                RestModule.class,
                OpenApiDocsModule.class,
                ConfigParsingModule.class,
                DocsTestSupportModule.class,
                InputValidationModule.class,
                CorpusRegistrationModule.class
            })
    public interface CorpusComponent {

        /**
         * Creates a new {@link HttpVerticle} of this component.
         *
         * @return a new verticle
         */
        HttpVerticle httpVerticle();

        /**
         * Resolves the component's recording schema source.
         *
         * @return the recording source the gate's schemas came from
         */
        RecordingSchemaSource recordingSource();

        /** Factory taking the application configuration. */
        @Component.Factory
        interface Factory {

            /**
             * Creates the component.
             *
             * @param config the application configuration
             * @return the component
             */
            CorpusComponent create(@BindsInstance @VertxConfig JsonObject config);
        }
    }
}
