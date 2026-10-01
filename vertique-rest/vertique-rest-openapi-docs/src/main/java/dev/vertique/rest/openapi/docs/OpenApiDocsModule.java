// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs;

import dagger.Module;
import dagger.Provides;
import dagger.multibindings.ElementsIntoSet;
import dev.vertique.core.VertxConfig;
import dev.vertique.core.config.ConfigParser;
import dev.vertique.core.config.JsonConfigPaths;
import dev.vertique.core.exception.ConfigurationException;
import dev.vertique.core.json.JsonMapperProfileRegistry;
import dev.vertique.rest.core.config.JaxRsConfig;
import dev.vertique.rest.core.interceptor.RequestInterceptor;
import dev.vertique.rest.core.lifecycle.RouterLifecycleHook;
import dev.vertique.rest.core.middleware.Middleware;
import dev.vertique.rest.core.router.MountCompositionValidator;
import dev.vertique.rest.core.router.MountCustomizer;
import dev.vertique.rest.core.router.RouterMount;
import dev.vertique.rest.core.security.AuthEnforcementCapability;
import dev.vertique.rest.core.security.SecuritySchemeHandler;
import dev.vertique.rest.jaxrs.publication.ApiDocsInstalled;
import dev.vertique.rest.jaxrs.publication.OperationPublicationSink;
import dev.vertique.rest.jaxrs.publication.RestApplications;
import dev.vertique.rest.jaxrs.validation.OperationSchemaSource;
import dev.vertique.rest.jaxrs.validation.RequestValidationStrategy;
import io.vertx.core.json.JsonObject;
import jakarta.inject.Singleton;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Dagger module of the OpenAPI documentation feature. It provides the parsed {@code apidocs}
 * configuration, the documents enabled for the component, the publication sink and the router mount
 * that publish and serve them, the composition validator that checks every composition before its
 * mounts create their routers and warns about the mount-scoped controls the document routes bypass,
 * and the marker that tells the JAX-RS module the documentation module is installed.
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
     * @param documents the enabled documents
     * @param store the document store of the component
     * @param apidocsConfig the parsed {@code apidocs} section, whose path is the documentation prefix
     * @param strategies the registered request-validation strategies
     * @param applications the declared applications of the component
     * @param schemaSource the bound operation schema source, if any
     * @param profiles the JSON mapper profile registry
     * @return the sink, or an empty set when no document is enabled
     */
    @Provides
    @ElementsIntoSet
    static Set<OperationPublicationSink> publicationSinks(
            EnabledDocuments documents,
            DocumentStore store,
            ApidocsConfig apidocsConfig,
            Set<RequestValidationStrategy> strategies,
            RestApplications applications,
            Optional<OperationSchemaSource> schemaSource,
            JsonMapperProfileRegistry profiles) {
        if (documents.isEmpty()) {
            return Set.of();
        }
        return Set.of(new DocsPublicationSink(
                documents,
                store,
                apidocsConfig.path(),
                strategies,
                applications,
                new AssemblyContext(schemaSource, profiles)));
    }

    /**
     * Contributes the documentation composition validator when at least one document is enabled.
     *
     * @param documents the enabled documents
     * @param apidocsConfig the parsed {@code apidocs} section, whose path is the documentation prefix
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
            ApidocsConfig apidocsConfig,
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
                apidocsConfig.path(),
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
     * @param documents the enabled documents
     * @param store the document store of the component
     * @param apidocsConfig the parsed {@code apidocs} section
     * @param jaxRsConfig the JAX-RS routing configuration, whose default headers decide the caching
     *     header of the documents
     * @param securitySchemeHandlers the registered security scheme handlers
     * @param authEnforcement the authentication enforcement capability, empty when not installed
     * @return the mount, or an empty set when no document is enabled
     */
    @Provides
    @ElementsIntoSet
    static Set<RouterMount> documentationMounts(
            EnabledDocuments documents,
            DocumentStore store,
            ApidocsConfig apidocsConfig,
            JaxRsConfig jaxRsConfig,
            Set<SecuritySchemeHandler> securitySchemeHandlers,
            Optional<AuthEnforcementCapability> authEnforcement) {
        if (documents.isEmpty()) {
            return Set.of();
        }
        return Set.of(new DocsRouterMount(
                apidocsConfig.path(),
                documents,
                store,
                cacheControl(jaxRsConfig),
                securitySchemeHandlers,
                authEnforcement));
    }

    /**
     * Computes the {@code Cache-Control} value of the documents from the effective default: the last
     * default header named {@code Cache-Control}, in any letter case. The value is {@code no-store}
     * when that default has a {@code no-store} directive and {@code no-cache} otherwise, preceded by
     * {@code private, } when the default has a {@code private} directive. It is never public.
     */
    private static String cacheControl(JaxRsConfig jaxRsConfig) {
        String effective = null;
        if (jaxRsConfig.defaultHeaders() != null) {
            for (Map.Entry<String, String> header :
                    jaxRsConfig.defaultHeaders().toHeaderMap().entrySet()) {
                if ("Cache-Control".equalsIgnoreCase(header.getKey())) {
                    effective = header.getValue();
                }
            }
        }
        boolean noStore = false;
        boolean isPrivate = false;
        if (effective != null) {
            for (String directive : effective.split(",")) {
                int equals = directive.indexOf('=');
                String name = (equals < 0 ? directive : directive.substring(0, equals))
                        .trim()
                        .toLowerCase(Locale.ROOT);
                noStore |= name.equals("no-store");
                isPrivate |= name.equals("private");
            }
        }
        return (isPrivate ? "private, " : "") + (noStore ? "no-store" : "no-cache");
    }

    /**
     * Parses the {@code apidocs} section. {@code apidocs.enabled} is resolved first as a strict JSON
     * boolean (default {@code true}); when it is {@code false} the rest of the subtree is neither
     * parsed nor validated and the disabled defaults are returned. Otherwise the section is parsed
     * through the canonical parser and nothing else is validated.
     *
     * @param config the root configuration
     * @param parser the canonical configuration parser
     * @return the parsed section, or the disabled defaults
     * @throws ConfigurationException when {@code apidocs} is not an object or {@code apidocs.enabled}
     *     is present and not a JSON boolean
     */
    @Provides
    static ApidocsConfig apidocsConfig(@VertxConfig JsonObject config, ConfigParser parser) {
        JsonObject section = JsonConfigPaths.navigateObject(config, "apidocs");
        Object enabled = section.getValue("enabled");
        if (enabled != null && !(enabled instanceof Boolean)) {
            throw new ConfigurationException("Config path 'apidocs.enabled' must be a JSON boolean, got "
                    + enabled.getClass().getSimpleName());
        }
        if (Boolean.FALSE.equals(enabled)) {
            return new ApidocsConfig(ApidocsConfig.DEFAULT_PATH, false, List.of());
        }
        return parser.parse(section, ApidocsConfig.class);
    }

    /**
     * Selects the documents enabled for the component and runs the configuration checks. With the
     * feature disabled, nothing is selected or checked. Otherwise a document is enabled when the
     * application is active, its declaring interface itself carries {@link ApiDocs}, and its
     * configuration entry is absent or does not say {@code enabled: false}.
     *
     * <p>Every {@code apidocs.documents} entry is checked, whatever its {@code enabled} value: its key
     * must follow the application-name grammar and name a declared application, active or not; it
     * holds only {@code enabled}, {@code info}, and {@code serverUrl}; and {@code enabled: true}
     * requires {@link ApiDocs} on the declaring interface. The {@link ApiDocs} of every active
     * application is re-checked for its shape, whether or not its document is disabled. When at least
     * one document is enabled, {@code apidocs.path} is checked, and each enabled document takes its
     * {@code info} from configuration, else from an {@code OpenAPIDefinition} on its declaring
     * interface itself, and has a valid {@code serverUrl} when one is configured.
     *
     * @param config the root configuration
     * @param apidocsConfig the parsed {@code apidocs} section
     * @param applications the declared applications of the component
     * @return the enabled documents, ordered by application name, each with its resolved
     *     {@code info}
     * @throws ConfigurationException when a document entry, {@code apidocs.path}, or the
     *     {@code info} or {@code serverUrl} of an enabled document is invalid, or, as the
     *     {@code RestConfigurationException} subtype, when the {@link ApiDocs} of an active
     *     application is malformed
     */
    @Provides
    @Singleton
    static EnabledDocuments enabledDocuments(
            @VertxConfig JsonObject config, ApidocsConfig apidocsConfig, RestApplications applications) {
        if (!apidocsConfig.enabled()) {
            return new EnabledDocuments(List.of());
        }
        List<EnabledDocuments.EnabledDocument> enabled = new ArrayList<>();
        for (RestApplications.Entry application : applications.all()) {
            if (!application.active()) {
                continue;
            }
            ApiDocs annotation = application.declaringType().getAnnotation(ApiDocs.class);
            if (annotation == null) {
                continue;
            }
            Optional<DocumentConfig> entry = apidocsConfig.documents().stream()
                    .filter(document -> application.name().equals(document.name()))
                    .findFirst();
            if (entry.map(DocumentConfig::enabled).map(Boolean.FALSE::equals).orElse(false)) {
                continue;
            }
            InfoConfig info = entry.map(DocumentConfig::info).orElse(null);
            String serverUrl = entry.map(DocumentConfig::serverUrl).orElse(null);
            enabled.add(new EnabledDocuments.EnabledDocument(
                    application.name(),
                    application.declaringType(),
                    annotation.access(),
                    application.mountPath(),
                    application.contractOrigin(),
                    info,
                    serverUrl));
        }
        return new EnabledDocuments(DocumentConfigChecks.check(config, apidocsConfig, applications, enabled));
    }
}
