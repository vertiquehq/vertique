// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.kafka;

import static dev.vertique.kafka.KafkaTestSupport.configParser;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.kafka.config.KafkaConfig;
import dev.vertique.services.ServiceContractRegistry;
import dev.vertique.services.ServiceTargetResolver;
import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Verifies the required-strategies check of a handler binding at registration, on the resolved
 * configuration and without deploying anything.
 *
 * <p>Verified:
 * <ul>
 *   <li>A binding that requires its strategies registers when the configuration is silent or states
 *       the required values.</li>
 *   <li>A configuration that changes the commit strategy, the error strategy or the exhausted
 *       strategy fails registration with a {@link KafkaRegistrationException} that names the
 *       consumer and the option; a forcing binding that registers keeps the configured
 *       {@code maxInFlight} and {@code max.poll.records}.</li>
 *   <li>The same configurations on a non-forcing {@code SKIP} binding, on which each is legal, register as they always did.</li>
 * </ul>
 *
 * <p>A {@code StrategyRig} hides the registry call; the rows of the table are named by option.
 */
class KafkaRequiredStrategiesTest {

    private static final String CONSUMER = "forced";

    /** Payload type of the bindings; never decoded here. */
    record Payload(String id) {}

    /**
     * One configuration under {@code kafka.consumers.forced}.
     *
     * @param label the row, named by the option it states
     * @param consumerConfig the consumer's configuration block
     * @param changedOption the option a forcing binding must name, or {@code null} when the row
     *     leaves every required value as it is
     */
    record Row(String label, JsonObject consumerConfig, String changedOption) {}

    private static List<Row> rows() {
        return List.of(
                new Row("no configuration", new JsonObject(), null),
                new Row(
                        "retry.exhaustedStrategy=DEAD_LETTER",
                        new JsonObject().put("retry", new JsonObject().put("exhaustedStrategy", "DEAD_LETTER")),
                        null),
                new Row("maxInFlight=1", new JsonObject().put("maxInFlight", 1), null),
                new Row("maxInFlight=2", new JsonObject().put("maxInFlight", 2), null),
                new Row("commitStrategy=AUTO", new JsonObject().put("commitStrategy", "AUTO"), "commitStrategy"),
                new Row("errorStrategy=SKIP", new JsonObject().put("errorStrategy", "SKIP"), "errorStrategy"),
                new Row(
                        "retry.exhaustedStrategy=SKIP",
                        new JsonObject().put("retry", new JsonObject().put("exhaustedStrategy", "SKIP")),
                        "retry.exhaustedStrategy"));
    }

    @Test
    @DisplayName("a forcing binding starts a new group at the beginning of the topic, unless the reset is configured")
    void forcingBindingDefaultsToTheBeginningOfTheTopic() {
        // Given: a forcing binding, and a binding that does not require its strategies
        KafkaConsumerBinding<Payload> forcing = StrategyRig.binding(true, ErrorStrategy.RETRY);
        KafkaConsumerBinding<Payload> notForcing = StrategyRig.binding(false, ErrorStrategy.SKIP);
        JsonObject latest = new JsonObject().put("properties", new JsonObject().put("auto.offset.reset", "latest"));

        // When: each registers, with no reset configured and with one
        // Then: only the forcing binding changes the default, and a configured value wins
        assertEquals("earliest", offsetReset(forcing, new JsonObject()));
        assertEquals("latest", offsetReset(forcing, latest));
        assertEquals(null, offsetReset(notForcing, new JsonObject()));
    }

    private static String offsetReset(KafkaConsumerBinding<Payload> binding, JsonObject consumerConfig) {
        return List.copyOf(StrategyRig.register(binding, consumerConfig).entries())
                .get(0)
                .config()
                .kafkaProperties()
                .get("auto.offset.reset");
    }

    @Test
    @DisplayName("a configuration that changes a required strategy fails registration naming the option")
    void configurationChangingARequiredStrategyFailsRegistrationNamingTheOption() {
        // Given: a MANUAL, RETRY handler binding that requires its strategies, and a MANUAL, SKIP binding that
        // does not, on which every row of the table is a legal configuration
        KafkaConsumerBinding<Payload> forcing = StrategyRig.binding(true, ErrorStrategy.RETRY);
        KafkaConsumerBinding<Payload> notForcing = StrategyRig.binding(false, ErrorStrategy.SKIP);

        for (Row row : rows()) {
            // When: the registry is built over the row's configuration
            // Then: the forcing binding registers on the required values and fails on any other, naming the option
            if (row.changedOption() == null) {
                List<ConsumerEntry> registered = List.copyOf(
                        StrategyRig.register(forcing, row.consumerConfig()).entries());
                assertEquals(1, registered.size(), "a forcing binding must register with " + row.label());
                assertEquals(
                        row.consumerConfig()
                                .getInteger("maxInFlight", ResolvedKafkaConsumerConfig.DEFAULT_MAX_IN_FLIGHT),
                        registered.get(0).config().maxInFlight(),
                        "a forcing binding must keep the configured maxInFlight with " + row.label());
                assertEquals(
                        null,
                        registered.get(0).config().kafkaProperties().get("max.poll.records"),
                        "a forcing binding must not change max.poll.records with " + row.label());
            } else {
                KafkaRegistrationException failure = assertThrows(
                        KafkaRegistrationException.class,
                        () -> StrategyRig.register(forcing, row.consumerConfig()),
                        "a forcing binding must refuse " + row.label());
                assertTrue(
                        failure.violations().stream()
                                .anyMatch(violation ->
                                        violation.contains(CONSUMER) && violation.contains(row.changedOption())),
                        "the violation for " + row.label() + " must name the consumer '" + CONSUMER
                                + "' and the option '" + row.changedOption() + "' but was: " + failure.violations());
            }

            // And: the binding that does not require its strategies registers under the same configuration, as today
            assertEquals(
                    1,
                    StrategyRig.register(notForcing, row.consumerConfig())
                            .entries()
                            .size(),
                    "a binding that does not require its strategies must register with " + row.label());
        }
    }

    /** Builds the binding and the registry of one row. */
    static final class StrategyRig {

        private StrategyRig() {}

        /**
         * Builds the handler binding under test.
         *
         * @param force whether the binding requires its strategies
         * @param errorStrategy the binding's error strategy
         * @return the binding, named {@code forced}, with {@code MANUAL} commit
         */
        static KafkaConsumerBinding<Payload> binding(boolean force, ErrorStrategy errorStrategy) {
            KafkaConsumerBinding.Builder<Payload> builder = KafkaConsumerBinding.builder(CONSUMER, Payload.class)
                    .topic("forced.events")
                    .groupId("forced-group")
                    .commitStrategy(CommitStrategy.MANUAL)
                    .errorStrategy(errorStrategy)
                    .handler(message -> Future.succeededFuture());
            if (force) {
                builder.requireBindingStrategies();
            }
            return builder.build();
        }

        /**
         * Registers one binding over a configuration of its consumer, with an empty service registry.
         *
         * @param binding the binding
         * @param consumerConfig the block under {@code kafka.consumers.forced}
         * @return the registry
         * @throws KafkaRegistrationException when the registration is refused
         */
        static KafkaConsumerRegistry register(KafkaConsumerBinding<Payload> binding, JsonObject consumerConfig) {
            JsonObject config = new JsonObject()
                    .put("kafka", new JsonObject().put("consumers", new JsonObject().put(CONSUMER, consumerConfig)));
            ServiceContractRegistry services = ServiceContractRegistry.build(Set.of(), configParser());
            return KafkaConsumerRegistry.build(
                    Set.of(binding),
                    Set.of(),
                    services,
                    ServiceTargetResolver.of(services),
                    KafkaTestSupport.jsonSerdeRegistry(),
                    KafkaConfig.fromConfig(config, configParser()));
        }
    }
}
