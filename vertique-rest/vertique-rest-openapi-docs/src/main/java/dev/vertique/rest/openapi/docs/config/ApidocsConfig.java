// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.config;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import dev.vertique.core.json.KeyedBy;
import jakarta.annotation.Nullable;
import java.util.List;

/**
 * The {@code apidocs} configuration section. Each {@code documents} key is injected into the
 * element's {@code name}.
 *
 * @param path the path prefix under which the documents are served; defaults to {@link
 *     EnabledDocuments#DEFAULT_PATH}
 * @param enabled whether the documentation feature is enabled; defaults to {@code true}
 * @param documents the configured document entries; defaults to empty
 */
record ApidocsConfig(
        String path, boolean enabled, @KeyedBy("name") List<DocumentConfig> documents) {

    /**
     * Compact constructor copying the keyed-collection list defensively for immutability.
     *
     * @param path the path prefix
     * @param enabled the global enabled flag
     * @param documents the document list (defensively copied; {@code null} becomes empty)
     */
    ApidocsConfig {
        documents = documents != null ? List.copyOf(documents) : List.of();
    }

    /**
     * Jackson factory applying the defaults for absent attributes.
     *
     * @param path the path prefix; defaults to {@code /apidocs} when {@code null}
     * @param enabled the global flag; defaults to {@code true} when {@code null}
     * @param documents the document entries; defaults to empty when {@code null}
     * @return the parsed section
     */
    @JsonCreator
    static ApidocsConfig fromJson(
            @JsonProperty("path") @Nullable String path,
            @JsonProperty("enabled") @Nullable Boolean enabled,
            @JsonProperty("documents") @Nullable List<DocumentConfig> documents) {
        return new ApidocsConfig(
                path != null ? path : EnabledDocuments.DEFAULT_PATH, enabled != null ? enabled : true, documents);
    }
}
