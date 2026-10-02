// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.config;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.annotation.Nullable;

/**
 * One entry of {@code apidocs.documents}, keyed by application name.
 *
 * @param name the application name the entry configures
 * @param enabled whether the document is enabled; {@code null} when the entry does not say, which
 *     keeps the decision of the {@code @ApiDocs} annotation
 * @param info the {@code info} object of the document
 * @param serverUrl the optional server URL published in the document
 */
record DocumentConfig(
        String name,
        @Nullable Boolean enabled,
        @Nullable InfoConfig info,
        @Nullable String serverUrl) {

    /**
     * Jackson factory: {@code enabled} stays {@code null} when absent or an explicit JSON
     * {@code null}.
     *
     * @param name the application name, injected from the entry key
     * @param enabled the tri-state enabled flag, or {@code null}
     * @param info the {@code info} object, or {@code null}
     * @param serverUrl the optional server URL, or {@code null}
     * @return the parsed entry
     */
    @JsonCreator
    static DocumentConfig fromJson(
            @JsonProperty("name") String name,
            @JsonProperty("enabled") @Nullable Boolean enabled,
            @JsonProperty("info") @Nullable InfoConfig info,
            @JsonProperty("serverUrl") @Nullable String serverUrl) {
        return new DocumentConfig(name, enabled, info, serverUrl);
    }
}
