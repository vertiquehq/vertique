// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.kafka.json;

import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoSet;
import dev.vertique.core.json.JsonMapperProfileRegistry;
import dev.vertique.core.lifecycle.ComposeValidator;
import dev.vertique.json.JsonConfig;
import dev.vertique.json.JsonRuntimeModule;
import dev.vertique.kafka.serialization.KafkaSerdeProvider;
import jakarta.inject.Singleton;

/**
 * Dagger module that contributes the framework-provided JSON serde provider to the
 * {@link KafkaSerdeProvider} multibinding and registers the Kafka per-boundary default-profile
 * validator.
 *
 * <p>Include this module alongside {@link dev.vertique.kafka.KafkaModule} in your Dagger
 * {@code @Component} to enable the {@code "json"} value format for Kafka consumers and producers.
 * This is the default/reference format; other formats (e.g. Avro) are contributed by their own
 * format modules.
 *
 * <pre>{@code
 * @Component(modules = {KafkaModule.class, KafkaJsonModule.class})
 * interface AppComponent { ... }
 * }</pre>
 *
 * <p>This module installs {@link JsonRuntimeModule} (FR-JSON-007B) so the
 * {@link JsonMapperProfileRegistry} and {@link JsonConfig} are available to the JSON serde provider
 * and to {@link KafkaDefaultProfileValidator}. The serde provider applies the three-tier precedence:
 * per-endpoint bag id &gt; {@code json.jsonProfile} global default &gt; {@code vertx} floor.
 */
@Module(includes = JsonRuntimeModule.class)
public abstract class KafkaJsonModule {

    /**
     * Contributes the {@link JsonSerdeProvider} for the {@code "json"} format to the
     * {@link KafkaSerdeProvider} multibinding, wired with the {@link JsonMapperProfileRegistry} and
     * {@link JsonConfig} so it can resolve named JSON mapper profiles selected per endpoint and apply
     * the global {@code json.jsonProfile} tier when no per-endpoint id is set.
     *
     * @param registry the JSON mapper profile registry used to resolve non-{@code vertx} profile ids
     * @param jsonConfig the global JSON config carrying {@code json.jsonProfile}
     * @return the {@link JsonSerdeProvider} instance
     */
    @Provides
    @Singleton
    @IntoSet
    static KafkaSerdeProvider jsonSerdeProvider(JsonMapperProfileRegistry registry, JsonConfig jsonConfig) {
        return new JsonSerdeProvider(registry, jsonConfig);
    }

    /**
     * Contributes the {@link KafkaDefaultProfileValidator} into the {@code Set<ComposeValidator>}
     * multibinding so the {@code VALIDATE} startup phase forces its construction, failing fast when
     * {@code kafka.jsonProfile} names an unknown profile (FR-JSON-050).
     *
     * @param impl the Kafka per-boundary default-profile validator
     * @return the validator contributed into the compose-validator set
     */
    @Provides
    @Singleton
    @IntoSet
    static ComposeValidator kafkaDefaultProfileValidator(KafkaDefaultProfileValidator impl) {
        return impl;
    }
}
