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
import dev.vertique.rest.core.config.JaxRsConfig;
import dev.vertique.rest.core.router.RouterMount;
import dev.vertique.rest.jaxrs.publication.ApiDocsInstalled;
import dev.vertique.rest.jaxrs.publication.OperationPublicationSink;
import dev.vertique.rest.jaxrs.publication.RestApplications;
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
 * that publish and serve them, and the marker that tells the JAX-RS module the documentation module
 * is installed.
 *
 * <p>The sink and the mount are contributed only when at least one document is enabled; with none,
 * no publication is built and no documentation route exists. The marker is bound whatever the
 * configuration.
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
     * @return the sink, or an empty set when no document is enabled
     */
    @Provides
    @ElementsIntoSet
    static Set<OperationPublicationSink> publicationSinks(EnabledDocuments documents, DocumentStore store) {
        return documents.isEmpty() ? Set.of() : Set.of(new DocsPublicationSink(documents, store));
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
     * @return the mount, or an empty set when no document is enabled
     */
    @Provides
    @ElementsIntoSet
    static Set<RouterMount> documentationMounts(
            EnabledDocuments documents, DocumentStore store, ApidocsConfig apidocsConfig, JaxRsConfig jaxRsConfig) {
        if (documents.isEmpty()) {
            return Set.of();
        }
        return Set.of(new DocsRouterMount(apidocsConfig.path(), documents, store, cacheControl(jaxRsConfig)));
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
     * Selects the documents enabled for the component. A document is enabled when the feature is
     * enabled, the application is active, its declaring interface itself carries {@link ApiDocs},
     * and its configuration entry is absent or does not say {@code enabled: false}. When at least one
     * document is enabled, {@code apidocs.path} and the {@code info} of every enabled document are
     * validated; disabled documents and applications without {@link ApiDocs} are never validated.
     *
     * @param config the root configuration
     * @param apidocsConfig the parsed {@code apidocs} section
     * @param applications the declared applications of the component
     * @return the enabled documents, ordered by application name
     * @throws ConfigurationException when {@code apidocs.path} or the {@code info} of an enabled
     *     document is invalid
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
            enabled.add(new EnabledDocuments.EnabledDocument(
                    application.name(),
                    application.declaringType(),
                    annotation.access(),
                    application.mountPath(),
                    application.contractOrigin(),
                    info));
        }
        if (enabled.isEmpty()) {
            return new EnabledDocuments(List.of());
        }
        validatePath(apidocsConfig.path());
        for (EnabledDocuments.EnabledDocument document : enabled) {
            validateInfo(document);
        }
        return new EnabledDocuments(enabled);
    }

    private static void validatePath(String path) {
        boolean valid = path != null
                && path.startsWith("/")
                && path.length() > 1
                && !path.endsWith("/")
                && !path.contains("//")
                && path.chars().noneMatch(c -> c == '*' || c == ':' || c == '{' || c == '}' || c == '?' || c == '#')
                && path.chars().noneMatch(Character::isWhitespace);
        if (valid) {
            for (String segment : path.substring(1).split("/", -1)) {
                if (segment.equals(".") || segment.equals("..")) {
                    valid = false;
                    break;
                }
            }
        }
        if (!valid) {
            throw new ConfigurationException("Invalid configuration 'apidocs.path': the prefix must be a literal "
                    + "path that starts with '/', is not '/', has no trailing '/', contains none of "
                    + "'*', ':', '{', '}', '?', '#', whitespace or '//', and has no '.' or '..' segment");
        }
    }

    private static void validateInfo(EnabledDocuments.EnabledDocument document) {
        String base = "apidocs.documents." + document.name() + ".info";
        InfoConfig info = document.info();
        if (info == null) {
            throw infoFailure(document, base);
        }
        if (info.title() == null || info.title().isBlank()) {
            throw infoFailure(document, base + ".title");
        }
        if (info.version() == null || info.version().isBlank()) {
            throw infoFailure(document, base + ".version");
        }
    }

    private static ConfigurationException infoFailure(EnabledDocuments.EnabledDocument document, String settingPath) {
        return new ConfigurationException("Application '" + document.name() + "' (declared by "
                + document.declaringType().getName() + ") needs a non-blank configured '" + settingPath + "'");
    }
}
