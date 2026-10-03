// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.config;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.annotation.Nullable;

/**
 * The {@code info} object of a configured document.
 *
 * <p>Internal to the OpenAPI documentation module; not an application API.
 *
 * @param title the document title
 * @param version the document version
 * @param description the optional document description
 */
record InfoConfig(String title, String version, @Nullable String description) {

    /**
     * Jackson factory: every attribute is optional at parse time; the selection of enabled
     * documents checks that {@code title} and {@code version} are present.
     *
     * @param title the document title, or {@code null}
     * @param version the document version, or {@code null}
     * @param description the optional document description, or {@code null}
     * @return the parsed object
     */
    @JsonCreator
    static InfoConfig fromJson(
            @JsonProperty("title") @Nullable String title,
            @JsonProperty("version") @Nullable String version,
            @JsonProperty("description") @Nullable String description) {
        return new InfoConfig(title, version, description);
    }
}
