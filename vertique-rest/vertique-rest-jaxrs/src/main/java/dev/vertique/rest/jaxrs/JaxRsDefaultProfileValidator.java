// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs;

import dev.vertique.core.json.JsonMapperProfileRegistry;
import dev.vertique.core.json.JsonProfileConfigurationException;
import dev.vertique.core.lifecycle.ComposeValidator;
import dev.vertique.rest.core.config.JaxRsConfig;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

/**
 * {@link ComposeValidator} that fails the application's {@code VALIDATE} phase when the configured
 * JAX-RS per-boundary default profile ({@code jaxrs.jsonProfile}) names a profile id that the
 * {@link JsonMapperProfileRegistry} does not know.
 *
 * <p>The validation is performed in the {@code @Inject} constructor (the
 * constructible-as-validation pattern), mirroring {@code JsonDefaultProfileValidator} in
 * {@code vertique-json}. When {@code jaxrs.jsonProfile} is non-null and non-blank, the constructor
 * resolves it through the registry; resolving an unknown id throws
 * {@link JsonProfileConfigurationException}. A {@code null}/blank id is a no-op.
 */
@Singleton
public final class JaxRsDefaultProfileValidator implements ComposeValidator {

    /**
     * Validates the configured JAX-RS per-boundary default profile id against the registry.
     *
     * <p>When {@code config.jsonProfile()} is non-null and non-blank, resolves it through the
     * registry; an unregistered id throws {@link JsonProfileConfigurationException}, failing fast at
     * construction (the {@code VALIDATE} phase). A {@code null}/blank id is a no-op — resolution falls
     * through to the global {@code json.jsonProfile} default and ultimately the reserved {@code vertx}
     * profile.
     *
     * @param config the parsed {@code jaxrs} configuration section
     * @param registry the JSON mapper profile registry used to resolve the configured default id
     * @throws JsonProfileConfigurationException if {@code config.jsonProfile()} names an unregistered
     *     profile id
     */
    @Inject
    public JaxRsDefaultProfileValidator(JaxRsConfig config, JsonMapperProfileRegistry registry) {
        registry.validateConfigured(config.jsonProfile());
    }
}
