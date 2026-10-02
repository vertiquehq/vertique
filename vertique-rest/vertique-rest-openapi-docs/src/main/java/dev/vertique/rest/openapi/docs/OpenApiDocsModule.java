// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs;

import dagger.Module;
import dagger.Provides;
import dagger.multibindings.ElementsIntoSet;
import dev.vertique.core.VertxConfig;
import dev.vertique.core.config.ConfigParser;
import dev.vertique.core.exception.ConfigurationException;
import dev.vertique.core.json.JsonMapperProfileRegistry;
import dev.vertique.rest.core.config.JaxRsConfig;
import dev.vertique.rest.core.interceptor.RequestInterceptor;
import dev.vertique.rest.core.lifecycle.RouterLifecycleHook;
import dev.vertique.rest.core.middleware.Middleware;
import dev.vertique.rest.core.response.ResponseProducerBinding;
import dev.vertique.rest.core.router.MountCompositionValidator;
import dev.vertique.rest.core.router.MountCustomizer;
import dev.vertique.rest.core.router.RouterMount;
import dev.vertique.rest.core.security.AuthEnforcementCapability;
import dev.vertique.rest.core.security.SecuritySchemeHandler;
import dev.vertique.rest.jaxrs.application.ApiDocsInstalled;
import dev.vertique.rest.jaxrs.application.RestApplications;
import dev.vertique.rest.jaxrs.publication.OperationPublicationSink;
import dev.vertique.rest.jaxrs.synthetic.SyntheticOperations;
import dev.vertique.rest.jaxrs.validation.OperationSchemaSource;
import dev.vertique.rest.jaxrs.validation.RequestValidationStrategy;
import io.vertx.core.json.JsonObject;
import jakarta.inject.Provider;
import jakarta.inject.Singleton;
import java.util.Optional;
import java.util.Set;

/**
 * Dagger module of the OpenAPI documentation feature. It provides the documents enabled for the
 * component, resolved from the {@code apidocs} configuration section, the publication sink and the
 * router mount that publish and serve them, the composition validator that checks every composition
 * before its mounts create their routers and warns about the mount-scoped controls the document
 * routes bypass, and the marker that tells the JAX-RS module the documentation module is installed.
 *
 * <p>The sink, the validator, and the mount are contributed only when at least one document is
 * enabled; with none, no publication is built and no documentation route exists. The marker is
 * bound whatever the configuration.
 */
@Module
public abstract class OpenApiDocsModule {

    OpenApiDocsModule() {}

    /** The one marker instance the module binds. */
    private static final ApiDocsInstalled INSTALLED = new ApiDocsInstalled() {};

    /**
     * Binds the marker that reports the documentation module as installed, whether or not any
     * document is enabled.
     *
     * @return the marker
     */
    @Provides
    static ApiDocsInstalled apiDocsInstalled() {
        return INSTALLED;
    }

    /**
     * Contributes the publication sink when at least one document is enabled.
     *
     * @param documents the enabled documents and the documentation prefix
     * @param store the document store of the component
     * @param strategies the registered request-validation strategies
     * @param applications the declared applications of the component
     * @param schemaSource the bound operation schema source, if any
     * @param profiles the JSON mapper profile registry
     * @param warnings the documentation module's warnings of the component
     * @param producerBindings the registered response producer bindings
     * @param securitySchemeHandlers the registered security scheme handlers
     * @return the sink, or an empty set when no document is enabled
     */
    @Provides
    @ElementsIntoSet
    static Set<OperationPublicationSink> publicationSinks(
            EnabledDocuments documents,
            DocumentStore store,
            Set<RequestValidationStrategy> strategies,
            RestApplications applications,
            Optional<OperationSchemaSource> schemaSource,
            JsonMapperProfileRegistry profiles,
            DocumentWarnings warnings,
            Set<ResponseProducerBinding<?>> producerBindings,
            Set<SecuritySchemeHandler> securitySchemeHandlers) {
        if (documents.isEmpty()) {
            return Set.of();
        }
        return Set.of(new DocsPublicationSink(
                documents,
                store,
                documents.path(),
                strategies,
                applications,
                new AssemblyContext(schemaSource, profiles, warnings, producerBindings, securitySchemeHandlers)));
    }

    /**
     * Contributes the documentation composition validator when at least one document is enabled.
     *
     * @param documents the enabled documents and the documentation prefix
     * @param applications the declared applications of the component, whose effective contract
     *     locations the validator compares
     * @param warnings the documentation module's warnings of the component
     * @param mountCustomizers the registered mount customizers
     * @param middlewares the registered middlewares
     * @param lifecycleHooks the registered router lifecycle hooks
     * @param requestInterceptors the registered request interceptors
     * @return the validator, or an empty set when no document is enabled
     */
    @Provides
    @ElementsIntoSet
    static Set<MountCompositionValidator> compositionValidators(
            EnabledDocuments documents,
            RestApplications applications,
            DocumentWarnings warnings,
            Set<MountCustomizer> mountCustomizers,
            Set<Middleware> middlewares,
            Set<RouterLifecycleHook> lifecycleHooks,
            Set<RequestInterceptor> requestInterceptors) {
        if (documents.isEmpty()) {
            return Set.of();
        }
        return Set.of(new DocsCompositionValidator(
                documents,
                documents.path(),
                applications,
                warnings,
                mountCustomizers,
                middlewares,
                lifecycleHooks,
                requestInterceptors));
    }

    /**
     * Contributes the documentation mount when at least one document is enabled. Each composition
     * gets its own mount.
     *
     * @param documents the enabled documents and the documentation prefix
     * @param store the document store of the component
     * @param jaxRsConfig the JAX-RS routing configuration, whose default headers decide the caching
     *     header of the public documents
     * @param securitySchemeHandlers the registered security scheme handlers
     * @param authEnforcement the authentication enforcement capability, empty when not installed
     * @param syntheticOperations the installer the protected document routes are installed through
     * @param warnings the documentation module's warnings of the component
     * @return the mount, or an empty set when no document is enabled
     */
    @Provides
    @ElementsIntoSet
    static Set<RouterMount> documentationMounts(
            EnabledDocuments documents,
            DocumentStore store,
            JaxRsConfig jaxRsConfig,
            Set<SecuritySchemeHandler> securitySchemeHandlers,
            Optional<AuthEnforcementCapability> authEnforcement,
            SyntheticOperations syntheticOperations,
            DocumentWarnings warnings) {
        if (documents.isEmpty()) {
            return Set.of();
        }
        return Set.of(new DocsRouterMount(
                documents.path(),
                documents,
                store,
                DocumentCachePolicy.publicCacheControl(jaxRsConfig),
                securitySchemeHandlers,
                authEnforcement,
                syntheticOperations,
                warnings));
    }

    /**
     * Resolves the documents enabled for the component: parses the {@code apidocs} section, then
     * obtains the declared applications, then selects the enabled documents and runs the
     * configuration checks (see {@link EnabledDocumentsResolver}).
     *
     * @param config the root configuration
     * @param parser the canonical configuration parser
     * @param applications the declared applications of the component, obtained once the section is
     *     parsed
     * @return the enabled documents, ordered by application name, under the documentation path
     * @throws ConfigurationException when {@code apidocs} or {@code apidocs.enabled} is malformed, or
     *     when a document entry, {@code apidocs.path}, or the {@code info} or {@code serverUrl} of an
     *     enabled document is invalid, or, as the {@code RestConfigurationException} subtype, when
     *     the {@link ApiDocs} of an active application is malformed
     */
    @Provides
    @Singleton
    static EnabledDocuments enabledDocuments(
            @VertxConfig JsonObject config, ConfigParser parser, Provider<RestApplications> applications) {
        return EnabledDocumentsResolver.resolve(config, parser, applications::get);
    }
}
