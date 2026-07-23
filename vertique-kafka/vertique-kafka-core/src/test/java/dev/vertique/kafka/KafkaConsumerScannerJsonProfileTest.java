// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.kafka;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.config.parser.DefaultConfigMapper;
import dev.vertique.config.parser.DefaultConfigParser;
import dev.vertique.core.config.ConfigParser;
import dev.vertique.core.exception.ConfigurationException;
import dev.vertique.core.json.JsonProfile;
import dev.vertique.kafka.config.KafkaConfig;
import dev.vertique.kafka.serialization.KafkaSerdeRegistry;
import dev.vertique.kafka.serialization.TestJsonSerdeProvider;
import dev.vertique.services.ServiceContractRegistry;
import dev.vertique.services.ServiceTargetResolver;
import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests for the Kafka consumer REFLECTIVE lane ({@link KafkaConsumerScanner}) reading
 * {@link JsonProfile} on the {@link KafkaListener} type.
 *
 * <p>These tests drive the reflective scanner through {@link KafkaConsumerRegistrar#scan} with
 * fixture classes/interfaces that have <em>no</em> generated companion on the classpath, so the
 * scanner falls back to its reflective path ({@code processRouterClass} / {@code
 * processHandlerInstance}). The effective value profile resolved by the reflective lane is observed
 * via {@code ConsumerEntry.config().jsonProfile()} — the resolved-config accessor that the
 * runtime threads from the listener default through {@link ResolvedKafkaConsumerConfig#resolve}.
 *
 * <p>The reflective lane reads {@link JsonProfile} on the {@code @KafkaListener} type at BOTH the
 * handler path and the router path as the sole per-binding profile selector, matching the codegen
 * lane (parity), and fails fast at BOOT time (a {@link ConfigurationException}; concretely the
 * registrar's {@link KafkaRegistrationException}, which extends it) when {@code @JsonProfile} is
 * placed at method level (FR-JSON-066).
 */
@DisplayName("KafkaConsumerScanner @JsonProfile reflective lane")
class KafkaConsumerScannerJsonProfileTest {

    // --- Shared payload ---

    /** Simple event payload used across the reflective fixtures. */
    record Event(String id) {}

    // --- Fixtures: router interfaces (Model 3, no companion → reflective processRouterClass) ---

    /** Router annotated {@code @JsonProfile("events-v2")}. */
    @JsonProfile("events-v2")
    @KafkaListener(name = "rp-router-ann", topic = "t.router.ann", groupId = "g-router-ann")
    interface RouterWithJsonProfile {

        /** Default catch-all route so the router is valid. */
        @KafkaHandler(defaultHandler = true)
        void onDefault();
    }

    /** Router with a method-level {@code @JsonProfile("v2")} (illegal placement — TYPE-level required). */
    @KafkaListener(name = "rp-router-method", topic = "t.router.method", groupId = "g-router-method")
    interface RouterWithMethodLevelJsonProfile {

        /** Handler method carrying an illegal method-level {@code @JsonProfile}. */
        @JsonProfile("v2")
        @KafkaHandler(matchHeader = "event-type", matchValue = "created")
        void onCreated(Event event);

        /** Default catch-all route. */
        @KafkaHandler(defaultHandler = true)
        void onDefault();
    }

    /** Router annotated {@code @JsonProfile("parity-v2")} for the generated-vs-reflective parity assertion. */
    @JsonProfile("parity-v2")
    @KafkaListener(name = "rp-router-parity", topic = "t.router.parity", groupId = "g-router-parity")
    interface RouterParity {

        /** Default catch-all route. */
        @KafkaHandler(defaultHandler = true)
        void onDefault();
    }

    /** Super-interface declaring a {@code @KafkaHandler} method that ALSO carries a method-level {@code @JsonProfile}. */
    interface RouterMethodLevelBase {

        /** Inherited handler method carrying an illegal method-level {@code @JsonProfile}. */
        @JsonProfile("v2")
        @KafkaHandler(matchHeader = "event-type", matchValue = "created")
        void onCreated(Event event);
    }

    /** {@code @KafkaListener} router that INHERITS the method-level {@code @JsonProfile} from its super-interface. */
    @KafkaListener(name = "rp-router-inherited-method", topic = "t.router.inh.method", groupId = "g-router-inh-method")
    interface RouterInheritsMethodLevelJsonProfile extends RouterMethodLevelBase {

        /** Default catch-all route so the router is valid. */
        @KafkaHandler(defaultHandler = true)
        void onDefault();
    }

    // --- Fixtures: handler classes (Model 4, no companion → reflective processHandlerInstance) ---

    /** Handler annotated {@code @JsonProfile("events-v2")}. */
    @JsonProfile("events-v2")
    @KafkaListener(name = "rp-handler-ann", topic = "t.handler.ann", groupId = "g-handler-ann")
    static class HandlerWithJsonProfile implements KafkaRecordHandler<Event> {

        @Override
        public Future<Void> handle(KafkaMessage<Event> message) {
            return Future.succeededFuture();
        }
    }

    // --- Helpers ---

    /** JSON-capable serde registry so format/threading resolution succeeds for the fixtures. */
    private static final KafkaSerdeRegistry SERDE = new KafkaSerdeRegistry(Set.of(new TestJsonSerdeProvider()));

    /** Empty typed Kafka config (no per-consumer config, no boundary default). */
    private static final KafkaConfig EMPTY_KAFKA_CONFIG =
            KafkaConfig.fromConfig(new JsonObject(), new DefaultConfigParser(DefaultConfigMapper.lenient()));

    /**
     * Returns a lenient {@link ConfigParser} matching the production boundary parser.
     *
     * @return a lenient config parser
     */
    private static ConfigParser configParser() {
        return new DefaultConfigParser(DefaultConfigMapper.lenient());
    }

    /**
     * Drives {@link KafkaConsumerRegistrar#scan} reflectively for a single listener contribution
     * (a router {@code Class<?>} or a {@link KafkaRecordHandler} instance) against an empty service
     * registry, returning the produced entries.
     *
     * @param contribution the Model-3 routing interface class or Model-4 handler instance
     * @return the reflective scan's consumer entries
     */
    private static List<ConsumerEntry> scanReflective(Object contribution) {
        ServiceContractRegistry registry = ServiceContractRegistry.build(Set.of(), configParser());
        ServiceTargetResolver resolver = ServiceTargetResolver.of(registry);
        return new KafkaConsumerRegistrar()
                .scan(Set.of(), Set.of(contribution), registry, resolver, SERDE, EMPTY_KAFKA_CONFIG);
    }

    /**
     * Returns the single produced entry's effective resolved value profile.
     *
     * @param contribution the router class or handler instance to scan
     * @return the resolved {@code config().jsonProfile()} of the single produced entry
     */
    private static String resolvedProfile(Object contribution) {
        List<ConsumerEntry> entries = scanReflective(contribution);
        assertEquals(1, entries.size(), "Expected exactly one entry from the reflective scan");
        return entries.get(0).config().jsonProfile();
    }

    // --- Tests ---

    @Test
    @DisplayName("reflectiveHandlerReadsJsonProfileAnnotation: handler @JsonProfile('events-v2') resolves 'events-v2'")
    void reflectiveHandlerReadsJsonProfileAnnotation() {
        assertEquals("events-v2", resolvedProfile(new HandlerWithJsonProfile()));
    }

    @Test
    @DisplayName("reflectiveRouterReadsJsonProfileAnnotation: router @JsonProfile('events-v2') resolves 'events-v2'")
    void reflectiveRouterReadsJsonProfileAnnotation() {
        assertEquals("events-v2", resolvedProfile(RouterWithJsonProfile.class));
    }

    @Test
    @DisplayName(
            "methodLevelJsonProfileOnReflectiveListener_failsAtBoot: method-level @JsonProfile throws naming method + TYPE")
    void methodLevelJsonProfileOnReflectiveListener_failsAtBoot() {
        ConfigurationException ex = assertThrows(
                ConfigurationException.class, () -> scanReflective(RouterWithMethodLevelJsonProfile.class));
        String message = ex.getMessage();
        assertTrue(
                message != null && message.contains("onCreated"),
                "Boot-time failure must name the offending method 'onCreated'; got: " + message);
        assertTrue(
                message != null && message.toUpperCase().contains("TYPE"),
                "Boot-time failure must state TYPE-level placement is required; got: " + message);
    }

    @Test
    @DisplayName(
            "methodLevelJsonProfileOnInheritedHandlerMethod_failsAtBoot: inherited method-level @JsonProfile throws naming method + TYPE")
    void methodLevelJsonProfileOnInheritedHandlerMethod_failsAtBoot() {
        // FR-JSON-066 parity: the handler-read uses cls.getMethods() (inherited-inclusive), so the
        // method-level reject must scan the SAME member set. A @JsonProfile on a @KafkaHandler method
        // declared on the SUPER-interface must fail fast even though the @KafkaListener type (the
        // Child) does not declare it. cls.getDeclaredMethods() misses it → currently no throw.
        ConfigurationException ex = assertThrows(
                ConfigurationException.class, () -> scanReflective(RouterInheritsMethodLevelJsonProfile.class));
        String message = ex.getMessage();
        assertTrue(
                message != null && message.contains("onCreated"),
                "Boot-time failure must name the offending inherited method 'onCreated'; got: " + message);
        assertTrue(
                message != null && message.toUpperCase().contains("TYPE"),
                "Boot-time failure must state TYPE-level placement is required; got: " + message);
    }

    @Test
    @DisplayName("generatedVsReflectiveParity_resolveSameProfileFromJsonProfile: reflective lane resolves 'parity-v2'")
    void generatedVsReflectiveParity_resolveSameProfileFromJsonProfile() {
        // The reflective lane must resolve the @JsonProfile id "parity-v2"; the S2 golden test pins
        // the codegen side to the same id, so equality of the two ids establishes lane parity.
        assertEquals("parity-v2", resolvedProfile(RouterParity.class));
    }

    // --- Legacy attribute removal (Phase 3 contract) ---

    @Test
    @DisplayName("kafkaListenerValueJsonProfileAttribute_noLongerExists: the legacy attribute method is gone")
    void kafkaListenerValueJsonProfileAttribute_noLongerExists() {
        // Proves the legacy attribute was removed from the annotation, not merely unread: the accessor
        // method must be absent from the @KafkaListener annotation class.
        assertThrows(NoSuchMethodException.class, () -> KafkaListener.class.getDeclaredMethod("valueJsonProfile"));
    }

    @Test
    @DisplayName("kafkaProducerValueJsonProfileAttribute_noLongerExists: the legacy attribute method is gone")
    void kafkaProducerValueJsonProfileAttribute_noLongerExists() {
        // Proves the legacy attribute was removed from the @KafkaProducer annotation class.
        assertThrows(
                NoSuchMethodException.class,
                () -> dev.vertique.kafka.producer.KafkaProducer.class.getDeclaredMethod("valueJsonProfile"));
    }
}
