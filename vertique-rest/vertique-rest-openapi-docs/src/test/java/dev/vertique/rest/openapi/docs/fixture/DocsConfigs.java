// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture;

import io.vertx.core.json.JsonObject;

/**
 * Builders for the test configurations. Every builder returns a fresh {@link JsonObject}: the
 * server binds {@code 127.0.0.1} on port {@code 0}, and the {@code none} validation strategy is
 * selected. Modifiers change the given object in place and return it.
 */
public final class DocsConfigs {

    /** The documentation prefix a configuration without {@code apidocs.path} uses. */
    public static final String DEFAULT_APIDOCS_PATH = "/apidocs";

    /** The configured {@code info.title} of the {@code public} document. */
    public static final String PUBLIC_TITLE = "Catalog";

    /** The configured {@code info.version} of the {@code public} document. */
    public static final String PUBLIC_VERSION = "1.0";

    /** The configured {@code info.title} of the protected {@code mgmt} document. */
    public static final String MGMT_TITLE = "Mgmt";

    /** The configured {@code info.version} of the protected {@code mgmt} document. */
    public static final String MGMT_VERSION = "1";

    /** The routing base path of the legacy default mount in the configuration without registrations. */
    public static final String LEGACY_BASE_PATH = "/api/public/*";

    private DocsConfigs() {}

    /**
     * Returns the loopback configuration with the {@code none} strategy and no {@code apidocs}
     * section.
     *
     * @return a fresh configuration
     */
    public static JsonObject loopback() {
        return new JsonObject()
                .put("http", new JsonObject().put("host", "127.0.0.1").put("port", 0))
                .put("jaxrs", new JsonObject().put("validationStrategy", "none"));
    }

    /**
     * Returns the shared configuration: the loopback configuration plus
     * {@code apidocs.documents.public.info} {@code {title: "Catalog", version: "1.0"}} and nothing
     * else under {@code apidocs}.
     *
     * @return a fresh configuration
     */
    public static JsonObject shared() {
        JsonObject config = loopback();
        withDocumentInfo(config, PublicApi.NAME, PUBLIC_TITLE, PUBLIC_VERSION);
        return config;
    }

    /**
     * Returns the shared configuration plus {@code apidocs.documents.mgmt.info}
     * {@code {title: "Mgmt", version: "1"}}, for the protected {@code mgmt} document.
     *
     * @return a fresh configuration
     */
    public static JsonObject sharedWithMgmtInfo() {
        JsonObject config = shared();
        withDocumentInfo(config, MgmtApi.NAME, MGMT_TITLE, MGMT_VERSION);
        return config;
    }

    /**
     * Returns the loopback configuration for a component without registrations: no {@code apidocs}
     * section, and the legacy default mount at {@code jaxrs.basePath} {@value #LEGACY_BASE_PATH}.
     *
     * @return a fresh configuration
     */
    public static JsonObject legacyDefaultMount() {
        JsonObject config = loopback();
        config.getJsonObject("jaxrs").put("basePath", LEGACY_BASE_PATH);
        return config;
    }

    /**
     * Sets {@code apidocs.enabled}.
     *
     * @param config  the configuration to change
     * @param enabled the value
     * @return {@code config}
     */
    public static JsonObject withApidocsEnabled(JsonObject config, boolean enabled) {
        apidocs(config).put("enabled", enabled);
        return config;
    }

    /**
     * Sets {@code apidocs.path}.
     *
     * @param config the configuration to change
     * @param path   the value
     * @return {@code config}
     */
    public static JsonObject withApidocsPath(JsonObject config, String path) {
        apidocs(config).put("path", path);
        return config;
    }

    /**
     * Sets {@code apidocs.documents.<name>.enabled}; a {@code null} value is written as an explicit
     * JSON {@code null}.
     *
     * @param config  the configuration to change
     * @param name    the document's name
     * @param enabled the value, or {@code null}
     * @return {@code config}
     */
    public static JsonObject withDocumentEnabled(JsonObject config, String name, Boolean enabled) {
        document(config, name).put("enabled", enabled);
        return config;
    }

    /**
     * Sets {@code apidocs.documents.<name>.info} to {@code {title, version}}.
     *
     * @param config  the configuration to change
     * @param name    the document's name
     * @param title   the {@code info.title} value
     * @param version the {@code info.version} value
     * @return {@code config}
     */
    public static JsonObject withDocumentInfo(JsonObject config, String name, String title, String version) {
        document(config, name).put("info", new JsonObject().put("title", title).put("version", version));
        return config;
    }

    /**
     * Returns {@code apidocs.documents.<name>}, creating it and its parents when absent, so a test
     * can change one rule with one {@code put} or {@code remove}.
     *
     * @param config the configuration
     * @param name   the document's name
     * @return the live entry object
     */
    public static JsonObject document(JsonObject config, String name) {
        JsonObject documents = apidocs(config).getJsonObject("documents");
        if (documents == null) {
            documents = new JsonObject();
            apidocs(config).put("documents", documents);
        }
        JsonObject entry = documents.getJsonObject(name);
        if (entry == null) {
            entry = new JsonObject();
            documents.put(name, entry);
        }
        return entry;
    }

    /**
     * Returns the {@code apidocs} section, creating it when absent.
     *
     * @param config the configuration
     * @return the live section object
     */
    public static JsonObject apidocs(JsonObject config) {
        JsonObject apidocs = config.getJsonObject("apidocs");
        if (apidocs == null) {
            apidocs = new JsonObject();
            config.put("apidocs", apidocs);
        }
        return apidocs;
    }
}
