// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.kafka;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import dev.vertique.config.parser.DefaultConfigMapper;
import dev.vertique.config.parser.DefaultConfigParser;
import dev.vertique.kafka.config.KafkaConfig;
import dev.vertique.kafka.serialization.KafkaDeserializer;
import dev.vertique.kafka.serialization.KafkaSerdeProvider;
import dev.vertique.kafka.serialization.KafkaSerdeRegistry;
import dev.vertique.kafka.serialization.KafkaSerializer;
import io.vertx.core.json.JsonObject;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * Proves that {@link KafkaConsumerValidation#validateAndBuild} logs a WARN when a
 * cleanup {@code close()} call throws at either of the two swallow sites:
 *
 * <ol>
 *   <li>The <strong>disabled-consumer validation probe</strong>: a disabled consumer whose
 *       probe deserializer's {@code close()} throws must still produce a valid entry (the
 *       validation succeeded before the probe was built), AND a WARN must be logged naming
 *       the consumer and format.</li>
 *   <li>The <strong>rejected-enabled-entry cleanup</strong>: an enabled consumer that is
 *       rejected by a later step (e.g. {@code worker=false} vs a {@code mayBlock} format)
 *       whose framework-owned deserializer's {@code close()} throws must still return
 *       {@code null} (the rejection is unchanged), AND a WARN must be logged.</li>
 * </ol>
 *
 * <p>Both tests are RED before the fix (no WARN is emitted — the exception is swallowed
 * silently) and GREEN after ({@code log.warn(..., closeFailure)} is added).
 *
 * <p>Log capture uses a Logback {@link ListAppender} attached to the
 * {@code KafkaConsumerValidation} class logger, mirroring the pattern in
 * {@code AzureKeyVaultPropertySourceFactoryTest}.
 */
class KafkaConsumerValidationCloseFailureTest {

    // --- Test doubles ---

    /** Marker interface auto-detected by {@link MayBlockProvider}. */
    interface FakeRecord {}

    /** A payload type that is auto-detected and may block (simulates Avro). */
    record BlockingPayload(String field) implements FakeRecord {}

    /**
     * A {@link KafkaSerdeProvider} whose built {@link KafkaDeserializer} throws a
     * {@link RuntimeException} from {@code close()}.
     *
     * <p>Used for the disabled-probe site: the provider builds fine; the probe is built fine;
     * the probe's {@code close()} throws to trigger the WARN.
     *
     * @param mayBlock whether the provider reports {@code mayBlock=true}
     */
    record ThrowOnCloseProvider(boolean mayBlock) implements KafkaSerdeProvider {

        /** The exception thrown by the probe/deserializer's {@code close()}. */
        static final RuntimeException CLOSE_FAILURE =
                new RuntimeException("simulated serde registry client close failure");

        @Override
        public String format() {
            return "avro";
        }

        @Override
        public boolean autoDetects(Class<?> type) {
            return FakeRecord.class.isAssignableFrom(type);
        }

        @Override
        public boolean mayBlock() {
            return mayBlock;
        }

        @Override
        public <V> KafkaSerializer<V> serializer(Class<V> type, JsonObject endpointConfig) {
            return (value, topic, headers) -> new byte[0];
        }

        @Override
        @SuppressWarnings("unchecked")
        public <V> KafkaDeserializer<V> deserializer(Class<V> type, JsonObject endpointConfig) {
            return new KafkaDeserializer<>() {
                @Override
                public V deserialize(byte[] data, String topic, Map<String, String> headers) {
                    return null;
                }

                @Override
                public void close() {
                    throw CLOSE_FAILURE;
                }
            };
        }
    }

    // --- Log capture helpers ---

    private Logger validationLogger;
    private ListAppender<ILoggingEvent> logAppender;

    /**
     * Attaches a {@link ListAppender} to the {@code KafkaConsumerValidation} class logger
     * before each test so that WARN events emitted during validation can be inspected.
     */
    @BeforeEach
    void attachLogAppender() {
        validationLogger = (Logger) LoggerFactory.getLogger(KafkaConsumerValidation.class);
        logAppender = new ListAppender<>();
        logAppender.setContext(validationLogger.getLoggerContext());
        logAppender.start();
        validationLogger.addAppender(logAppender);
    }

    /**
     * Detaches and stops the {@link ListAppender} after each test to avoid interference
     * between test cases.
     */
    @AfterEach
    void detachLogAppender() {
        validationLogger.detachAppender(logAppender);
        logAppender.stop();
    }

    // --- Config helpers ---

    /**
     * Builds a {@link ResolvedKafkaConsumerConfig} for the given consumer name with the
     * per-consumer overrides in {@code consumerJson}.
     *
     * @param name        the consumer binding name
     * @param consumerJson the per-consumer override object (e.g. {@code {enabled: false}})
     * @return the resolved consumer config
     */
    private static ResolvedKafkaConsumerConfig config(String name, JsonObject consumerJson) {
        JsonObject kafkaConfigJson = new JsonObject().put("consumers", new JsonObject().put(name, consumerJson));
        KafkaConfig kafkaConfig = KafkaConfig.fromConfig(
                new JsonObject().put("kafka", kafkaConfigJson), new DefaultConfigParser(DefaultConfigMapper.lenient()));
        return ResolvedKafkaConsumerConfig.resolve(
                name,
                "test.topic",
                "test-group",
                true,
                CommitStrategy.AUTO,
                ErrorStrategy.SKIP,
                "",
                30_000L,
                null,
                kafkaConfig,
                kafkaConfig.consumerIndex().get(name));
    }

    /**
     * Invokes {@link KafkaConsumerValidation#validateAndBuild} for a BINDING consumer with no
     * custom deserializer.
     *
     * @param name       consumer binding name
     * @param cfg        resolved consumer config
     * @param valueType  payload type
     * @param registry   serde registry
     * @param violations mutable violations list
     * @return the built entry, or {@code null} if rejected
     */
    private static ConsumerEntry buildBinding(
            String name,
            ResolvedKafkaConsumerConfig cfg,
            Class<?> valueType,
            KafkaSerdeRegistry registry,
            List<String> violations) {
        return KafkaConsumerValidation.validateAndBuild(
                name,
                cfg,
                ConsumerEntry.Kind.BINDING,
                valueType,
                "test.address",
                null,
                false,
                List.of(),
                null,
                registry,
                null,
                null,
                new HashSet<>(),
                violations);
    }

    // --- Tests ---

    @Test
    @DisplayName("disabled probe close() failure: entry still returned and WARN logged with consumer name and format")
    void disabledProbeCloseFailureLogsWarnAndReturnsEntry() {
        // Given: a DISABLED consumer whose probe deserializer's close() throws.
        // The provider does NOT report mayBlock so no worker-incompatibility violation fires.
        // The config is valid (known format, valid topic/groupId) so validation passes.
        // When: validateAndBuild is called.
        // Then: the entry is returned (probe close failure does not invalidate a valid consumer),
        //       AND a WARN is captured containing the consumer name "probe-throw-close".
        KafkaSerdeRegistry registry = new KafkaSerdeRegistry(Set.of(new ThrowOnCloseProvider(false)));
        ResolvedKafkaConsumerConfig cfg = config("probe-throw-close", new JsonObject().put("enabled", false));
        List<String> violations = new ArrayList<>();

        ConsumerEntry entry = buildBinding("probe-throw-close", cfg, BlockingPayload.class, registry, violations);

        // Outcome: valid entry, no violation added
        assertTrue(violations.isEmpty(), "close() failure must not add a violation; got: " + violations);
        assertNotNull(entry, "A valid disabled consumer must produce an entry even when probe close() throws");

        // Log assertion: a WARN must have been emitted by KafkaConsumerValidation
        String capturedWarns = logAppender.list.stream()
                .filter(e -> e.getLevel() == Level.WARN)
                .map(ILoggingEvent::getFormattedMessage)
                .collect(Collectors.joining("\n"));
        assertTrue(
                capturedWarns.contains("probe-throw-close"),
                "WARN must name the consumer 'probe-throw-close'; captured WARN lines:\n" + capturedWarns);
    }

    @Test
    @DisplayName("rejected enabled entry close() failure: null still returned and WARN logged with consumer name")
    void rejectedEntryCloseFailureLogsWarnAndReturnsNull() {
        // Given: an ENABLED consumer with worker=false and a mayBlock=true provider.
        // The registry builds a deserializer, then the worker=false check fires and rejects
        // the entry. Cleanup close() on the framework-owned deserializer throws.
        // When: validateAndBuild is called.
        // Then: null is returned (the rejection is unchanged),
        //       AND a WARN is captured containing the consumer name "reject-throw-close".
        KafkaSerdeRegistry registry = new KafkaSerdeRegistry(Set.of(new ThrowOnCloseProvider(true)));
        ResolvedKafkaConsumerConfig cfg = config("reject-throw-close", new JsonObject().put("worker", false));
        List<String> violations = new ArrayList<>();

        ConsumerEntry entry = buildBinding("reject-throw-close", cfg, BlockingPayload.class, registry, violations);

        // Outcome: rejected entry (worker=false + mayBlock), violation recorded
        assertNull(entry, "Entry must remain null (rejection unchanged) when cleanup close() throws");
        assertTrue(
                violations.stream().anyMatch(v -> v.contains("worker=false")),
                "Expected worker=false violation, got: " + violations);

        // Log assertion: a WARN must have been emitted by KafkaConsumerValidation
        String capturedWarns = logAppender.list.stream()
                .filter(e -> e.getLevel() == Level.WARN)
                .map(ILoggingEvent::getFormattedMessage)
                .collect(Collectors.joining("\n"));
        assertTrue(
                capturedWarns.contains("reject-throw-close"),
                "WARN must name the consumer 'reject-throw-close'; captured WARN lines:\n" + capturedWarns);
    }
}
