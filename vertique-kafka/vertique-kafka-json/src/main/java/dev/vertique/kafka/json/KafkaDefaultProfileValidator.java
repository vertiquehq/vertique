// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.kafka.json;

import dev.vertique.core.json.JsonMapperProfileRegistry;
import dev.vertique.core.json.JsonProfileConfigurationException;
import dev.vertique.core.lifecycle.ComposeValidator;
import dev.vertique.kafka.config.KafkaConfig;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

/**
 * {@link ComposeValidator} that fails the application's {@code VALIDATE} phase when the configured
 * kafka-boundary default profile ({@code kafka.jsonProfile}) names a profile id that the
 * {@link JsonMapperProfileRegistry} does not know.
 *
 * <p>The validation is performed in the {@code @Inject} constructor (the
 * constructible-as-validation pattern), mirroring {@link JaxRsDefaultProfileValidator}. When
 * {@code kafka.jsonProfile} is non-null and non-blank, the constructor resolves it through the
 * registry; resolving an unknown id throws {@link JsonProfileConfigurationException}. A
 * {@code null}/blank id is a no-op.
 *
 * <p>This validator is inert with respect to individual consumer/producer bindings — it validates the
 * kafka-boundary default id unconditionally, even when zero consumers or producers are configured and
 * even when a more-specific per-binding profile would shadow it at runtime. This closes the
 * lazy-resolution gap for inert or shadowed configured defaults (FR-JSON-050).
 */
@Singleton
public final class KafkaDefaultProfileValidator implements ComposeValidator {

    /**
     * Validates the configured Kafka per-boundary default profile id against the registry.
     *
     * <p>When {@code kafkaConfig.jsonProfile()} is non-null and non-blank, resolves it through
     * the registry; an unregistered id throws {@link JsonProfileConfigurationException}, failing fast
     * at construction (the {@code VALIDATE} phase). A {@code null}/blank id is a no-op — resolution
     * falls through to the global {@code json.jsonProfile} default and ultimately the reserved
     * {@code vertx} profile.
     *
     * @param kafkaConfig the typed {@code kafka} configuration section (carries {@code jsonProfile})
     * @param registry the JSON mapper profile registry used to resolve the configured default id
     * @throws JsonProfileConfigurationException if {@code kafkaConfig.jsonProfile()} names an
     *     unregistered profile id
     */
    @Inject
    public KafkaDefaultProfileValidator(KafkaConfig kafkaConfig, JsonMapperProfileRegistry registry) {
        registry.validateConfigured(kafkaConfig.jsonProfile());
    }
}
