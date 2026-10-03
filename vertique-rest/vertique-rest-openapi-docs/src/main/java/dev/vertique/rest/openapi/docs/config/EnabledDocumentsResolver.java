// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.config;

import dev.vertique.core.config.ConfigParser;
import dev.vertique.core.config.JsonConfigPaths;
import dev.vertique.core.exception.ConfigurationException;
import dev.vertique.rest.jaxrs.application.RestApplications;
import dev.vertique.rest.openapi.docs.ApiDocs;
import dev.vertique.rest.openapi.docs.document.DocumentInfo;
import io.vertx.core.json.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Resolves the documents enabled for one component from the root configuration and the declared
 * applications: it parses the {@code apidocs} section, selects the enabled documents, and runs the
 * configuration checks of {@link DocumentConfigChecks}.
 *
 * <p>The parsed section stays on the configuration side; only the {@link EnabledDocuments}, which
 * carry the documentation path, leave it.
 *
 * <p>Internal to the OpenAPI documentation module; not an application API.
 */
public final class EnabledDocumentsResolver {

    private EnabledDocumentsResolver() {}

    /**
     * Parses the {@code apidocs} section, then obtains the declared applications, then selects the
     * enabled documents. The applications are obtained after the section is parsed and whether or
     * not the feature is enabled.
     *
     * @param config the root configuration
     * @param parser the canonical configuration parser
     * @param applications supplies the declared applications of the component
     * @return the enabled documents, as {@link #select} returns them
     * @throws ConfigurationException as {@link #parse} and {@link #select} throw
     */
    public static EnabledDocuments resolve(
            JsonObject config, ConfigParser parser, Supplier<RestApplications> applications) {
        ApidocsConfig apidocsConfig = parse(config, parser);
        RestApplications declared = applications.get();
        return select(config, apidocsConfig, declared);
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
    static ApidocsConfig parse(JsonObject config, ConfigParser parser) {
        JsonObject section = JsonConfigPaths.navigateObject(config, "apidocs");
        Object enabled = section.getValue("enabled");
        if (enabled != null && !(enabled instanceof Boolean)) {
            throw new ConfigurationException("Config path 'apidocs.enabled' must be a JSON boolean, got "
                    + enabled.getClass().getSimpleName());
        }
        if (Boolean.FALSE.equals(enabled)) {
            return new ApidocsConfig(EnabledDocuments.DEFAULT_PATH, false, List.of());
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
     * one document is enabled, {@code apidocs.path} is checked. Each enabled generated document takes
     * its {@code info} from configuration, else from an {@code OpenAPIDefinition} on its declaring
     * interface itself, and has a valid {@code serverUrl} when one is configured. Each enabled
     * document whose application serves its own contract needs no {@code info}, and configures
     * neither {@code info} nor {@code serverUrl} and carries no {@code OpenAPIDefinition} on its
     * declaring interface itself, since each would rewrite that contract.
     *
     * @param config the root configuration
     * @param apidocsConfig the parsed {@code apidocs} section
     * @param applications the declared applications of the component
     * @return the enabled documents, ordered by application name, each generated one with its
     *     resolved {@code info}, under the configured {@code apidocs.path}; with the feature
     *     disabled, none under the default path
     * @throws ConfigurationException when a document entry, {@code apidocs.path}, or the
     *     {@code info} or {@code serverUrl} of an enabled document is invalid, or, as the
     *     {@code RestConfigurationException} subtype, when the {@link ApiDocs} of an active
     *     application is malformed
     */
    static EnabledDocuments select(JsonObject config, ApidocsConfig apidocsConfig, RestApplications applications) {
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
            DocumentInfo info = entry.map(DocumentConfig::info)
                    .map(configured ->
                            new DocumentInfo(configured.title(), configured.version(), configured.description()))
                    .orElse(null);
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
        return new EnabledDocuments(
                DocumentConfigChecks.check(config, apidocsConfig, applications, enabled), apidocsConfig.path());
    }
}
