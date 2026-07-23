// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.client;

import dev.vertique.core.json.JsonMapperProfileRegistry;
import dev.vertique.core.json.JsonProfileConfigurationException;
import dev.vertique.core.lifecycle.ComposeValidator;
import dev.vertique.rest.client.config.RestClientDefaults;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

/**
 * {@link ComposeValidator} that fails the application's {@code VALIDATE} phase when the configured
 * rest-client per-boundary default profile ({@code restClient.defaults.jsonProfile}) names a profile
 * id that the {@link JsonMapperProfileRegistry} does not know.
 *
 * <p>The validation is performed in the {@code @Inject} constructor (the
 * constructible-as-validation pattern), mirroring {@code JaxRsDefaultProfileValidator} in
 * {@code vertique-rest-jaxrs}. When the injected {@link RestClientDefaults#jsonProfile()} is
 * non-null and non-blank, the constructor resolves it through the registry; resolving an unknown id
 * throws {@link JsonProfileConfigurationException}. A {@code null}/blank id is a no-op.
 *
 * <p>This validator is forced unconditionally by {@code ComposeValidationStep} (VALIDATE phase)
 * even when <strong>zero</strong> rest clients are configured — closing the inert-boundary gap
 * ({@code FR-JSON-050}).
 */
@Singleton
public final class RestClientDefaultProfileValidator implements ComposeValidator {

    /**
     * Validates the configured rest-client default profile id against the registry.
     *
     * <p>When {@link RestClientDefaults#jsonProfile()} is non-null and non-blank, resolves it
     * through the registry; an unregistered id throws {@link JsonProfileConfigurationException},
     * failing fast at construction (the {@code VALIDATE} phase). A {@code null}/blank profile is a
     * no-op.
     *
     * @param restClientDefaults the parsed {@code restClient.defaults} typed record; never
     *     {@code null} — its {@code jsonProfile()} is {@code null} when the sub-object is absent
     *     from config
     * @param registry the JSON mapper profile registry used to resolve the configured default id
     * @throws JsonProfileConfigurationException if {@code restClientDefaults.jsonProfile()} names
     *     an unregistered profile id
     */
    @Inject
    public RestClientDefaultProfileValidator(
            RestClientDefaults restClientDefaults, JsonMapperProfileRegistry registry) {
        registry.validateConfigured(restClientDefaults.jsonProfile());
    }
}
