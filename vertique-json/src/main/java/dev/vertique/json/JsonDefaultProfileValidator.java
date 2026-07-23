// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.json;

import dev.vertique.core.json.JsonMapperProfileRegistry;
import dev.vertique.core.json.JsonProfileConfigurationException;
import dev.vertique.core.lifecycle.ComposeValidator;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

/**
 * {@link ComposeValidator} that fails the application's {@code VALIDATE} phase when the configured
 * global default profile ({@code json.jsonProfile}) names a profile id that the
 * {@link JsonMapperProfileRegistry} does not know.
 *
 * <p>The validation is performed in the {@code @Inject} constructor (the
 * constructible-as-validation pattern). When {@code json.jsonProfile} is non-null and non-blank, the
 * constructor resolves it through the registry; resolving an unknown id throws
 * {@link JsonProfileConfigurationException}. A {@code null}/blank id is a no-op (the reserved
 * {@code vertx} default applies).
 */
@Singleton
public final class JsonDefaultProfileValidator implements ComposeValidator {

    /**
     * Validates the configured global default profile id against the registry.
     *
     * <p>When {@code config.jsonProfile()} is non-null and non-blank, resolves it through the
     * registry; an unregistered id throws {@link JsonProfileConfigurationException}, failing fast at
     * construction (the {@code VALIDATE} phase). A {@code null}/blank id is a no-op — the reserved
     * {@code vertx} default applies.
     *
     * @param config the parsed {@code json} configuration section
     * @param registry the JSON mapper profile registry used to resolve the configured default id
     * @throws JsonProfileConfigurationException if {@code config.jsonProfile()} names an unregistered
     *     profile id
     */
    @Inject
    public JsonDefaultProfileValidator(JsonConfig config, JsonMapperProfileRegistry registry) {
        registry.validateConfigured(config.jsonProfile());
    }
}
