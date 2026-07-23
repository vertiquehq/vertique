// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.kafka.json;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.fasterxml.jackson.databind.ObjectMapper;
import dagger.Component;
import dagger.Module;
import dagger.Provides;
import dagger.multibindings.Multibinds;
import dev.vertique.core.VertxConfig;
import dev.vertique.core.config.ConfigParser;
import dev.vertique.core.exception.ConfigurationException;
import dev.vertique.kafka.config.KafkaConfig;
import dev.vertique.kafka.config.KafkaMessageKeyHashConfig;
import dev.vertique.kafka.serialization.KafkaDeserializer;
import dev.vertique.kafka.serialization.KafkaSerdeProvider;
import dev.vertique.kafka.serialization.KafkaSerdeRegistry;
import dev.vertique.kafka.serialization.KafkaSerializer;
import io.vertx.core.json.JsonObject;
import jakarta.inject.Singleton;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Dagger graph smoke test for {@link KafkaJsonModule}.
 *
 * <p>Assembles a minimal {@code @Component} that combines a stub core module (providing the
 * {@code @Multibinds Set<KafkaSerdeProvider>} declaration plus the transitive bindings required by
 * {@link KafkaJsonModule}: a root config, a config parser, and a {@link KafkaConfig}) with
 * {@link KafkaJsonModule}, then verifies:
 * <ul>
 *   <li>The Dagger graph compiles and a {@code Set<KafkaSerdeProvider>} with exactly one entry
 *       (the {@link JsonSerdeProvider}) is produced.</li>
 *   <li>The contributed provider has {@code format()=="json"}, {@code autoDetects==false}, and
 *       {@code mayBlock==false}.</li>
 *   <li>A {@link KafkaSerdeRegistry} built from the set can round-trip a POJO via JSON
 *       serialization and deserialization at the provider/registry level (no verticle deploy).</li>
 * </ul>
 *
 * <p>This test deliberately avoids deploying a consumer or producer verticle to stay clear of the
 * cross-module {@code ContextLocal} SPI registration mismatch (see plan §13). It exercises the
 * serde layer only.
 */
class KafkaJsonModuleTest {

    // --- Fixtures ---

    record Payload(String name, int value) {}

    // --- Minimal stub module providing the @Multibinds declaration ---

    /**
     * Minimal stub module that provides the {@code @Multibinds} declaration for
     * {@code Set<KafkaSerdeProvider>} and the transitive bindings required by
     * {@link KafkaJsonModule}: root config, config parser, and {@link KafkaConfig}. This avoids
     * pulling in the full {@link dev.vertique.kafka.KafkaModule} and its many transitive dependencies
     * (Vertx, config, deploy, health, etc.).
     */
    @Module
    abstract static class StubKafkaCoreModule {

        /**
         * Declares the empty-by-default multibinding for serde providers, mirroring the
         * declaration in {@link dev.vertique.kafka.KafkaModule}.
         *
         * @return an empty set (format modules contribute elements via {@code @IntoSet})
         */
        @Multibinds
        abstract Set<KafkaSerdeProvider> kafkaSerdeProviders();

        /**
         * Provides an empty root config so the {@link dev.vertique.json.JsonRuntimeModule}
         * provider for {@link dev.vertique.json.JsonConfig} can parse the {@code json} section.
         *
         * @return the empty root config object
         */
        @Provides
        @VertxConfig
        static JsonObject rootConfig() {
            return new JsonObject();
        }

        /**
         * Provides a minimal {@link ConfigParser} backed by a plain Jackson {@link ObjectMapper},
         * sufficient to deserialize {@link dev.vertique.json.JsonConfig} from the empty section.
         *
         * @return a minimal config parser
         */
        @Provides
        static ConfigParser configParser() {
            ObjectMapper mapper = new ObjectMapper();
            return new ConfigParser() {
                @Override
                public <T> T parse(JsonObject section, Class<T> type) {
                    try {
                        String json = section == null ? "{}" : section.encode();
                        return mapper.readValue(json, type);
                    } catch (Exception e) {
                        throw new ConfigurationException(
                                "failed to parse config section into " + type.getSimpleName(), e);
                    }
                }

                @Override
                public <T> List<T> parseKeyedObject(JsonObject section, String identityProp, Class<T> elementType) {
                    throw new UnsupportedOperationException("not needed for KafkaJsonModuleTest");
                }

                @Override
                public <T> List<T> parseKeyedObject(
                        JsonObject section, String identityProp, Class<T> elementType, Map<String, Object> fixedProps) {
                    throw new UnsupportedOperationException("not needed for KafkaJsonModuleTest");
                }
            };
        }

        /**
         * Provides a default {@link KafkaConfig} with no {@code jsonProfile} set, satisfying the
         * {@link KafkaDefaultProfileValidator} dependency contributed by {@link KafkaJsonModule}.
         *
         * @return a default {@link KafkaConfig}
         */
        @Provides
        static KafkaConfig kafkaConfig() {
            return new KafkaConfig(
                    null, // format
                    null, // properties
                    null, // schemaRegistry
                    List.of(), // consumers
                    List.of(), // producers
                    new KafkaMessageKeyHashConfig(null), // messageKeyHash
                    new JsonObject(), // connectionProperties
                    new JsonObject(), // producerProperties
                    null); // jsonProfile — no-op validator
        }
    }

    // --- Test component ---

    /**
     * Minimal test {@code @Component} combining the stub core module with {@link KafkaJsonModule}.
     *
     * <p>Exposes only the {@code Set<KafkaSerdeProvider>} multibinding for assertion.
     */
    @Singleton
    @Component(modules = {StubKafkaCoreModule.class, KafkaJsonModule.class})
    interface TestComponent {

        /**
         * Returns the full set of contributed {@link KafkaSerdeProvider} instances.
         *
         * @return the populated provider set
         */
        Set<KafkaSerdeProvider> serdeProviders();
    }

    // --- Tests ---

    @Test
    @DisplayName("KafkaJsonModule contributes exactly one provider with format 'json'")
    void contributesJsonProvider() {
        TestComponent component = DaggerKafkaJsonModuleTest_TestComponent.create();
        Set<KafkaSerdeProvider> providers = component.serdeProviders();

        assertNotNull(providers, "provider set must not be null");
        assertEquals(1, providers.size(), "KafkaJsonModule must contribute exactly one provider");

        KafkaSerdeProvider provider = providers.iterator().next();
        assertEquals("json", provider.format(), "contributed provider must have format 'json'");
        assertFalse(provider.autoDetects(Payload.class), "JSON provider must not auto-detect types");
        assertFalse(provider.mayBlock(), "JSON provider must not block");
    }

    @Test
    @DisplayName("KafkaSerdeRegistry built from the json provider round-trips a POJO")
    void registryRoundTripsPojo() {
        TestComponent component = DaggerKafkaJsonModuleTest_TestComponent.create();
        Set<KafkaSerdeProvider> providers = component.serdeProviders();

        KafkaSerdeRegistry registry = new KafkaSerdeRegistry(providers);

        KafkaSerializer<Payload> ser = registry.serializer("json", Payload.class, new JsonObject());
        KafkaDeserializer<Payload> deser = registry.deserializer("json", Payload.class, new JsonObject());

        Payload original = new Payload("hello", 42);
        byte[] bytes = ser.serialize(original, "topic", Map.of());

        assertNotNull(bytes, "serialized bytes must not be null");

        Payload deserialized = deser.deserialize(bytes, "topic", Map.of());
        assertEquals(original.name(), deserialized.name(), "name must round-trip");
        assertEquals(original.value(), deserialized.value(), "value must round-trip");
    }

    @Test
    @DisplayName("KafkaSerdeRegistry built from the json provider resolves format 'json' for a plain type")
    void registryResolvesJsonFormat() {
        TestComponent component = DaggerKafkaJsonModuleTest_TestComponent.create();
        KafkaSerdeRegistry registry = new KafkaSerdeRegistry(component.serdeProviders());

        String format = registry.resolveFormat(Payload.class, new JsonObject(), null);
        assertEquals("json", format, "resolveFormat must fall through to DEFAULT_FORMAT when json provider is present");
    }

    @Test
    @DisplayName("JSON bytes round-tripped through the registry carry correct field values")
    void roundTripFieldValues() {
        TestComponent component = DaggerKafkaJsonModuleTest_TestComponent.create();
        KafkaSerdeRegistry registry = new KafkaSerdeRegistry(component.serdeProviders());

        Payload original = new Payload("world", 99);
        byte[] bytes =
                registry.serializer("json", Payload.class, new JsonObject()).serialize(original, "t", Map.of());

        String json = new String(bytes, StandardCharsets.UTF_8);
        org.junit.jupiter.api.Assertions.assertTrue(
                json.contains("\"name\""), "serialized JSON must contain the name field");
        org.junit.jupiter.api.Assertions.assertTrue(
                json.contains("\"world\""), "serialized JSON must contain the name value");

        Payload recovered =
                registry.deserializer("json", Payload.class, new JsonObject()).deserialize(bytes, "t", Map.of());
        assertEquals("world", recovered.name());
        assertEquals(99, recovered.value());
    }
}
