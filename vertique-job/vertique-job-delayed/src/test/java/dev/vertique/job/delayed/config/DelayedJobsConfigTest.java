// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.job.delayed.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.config.parser.DefaultConfigMapper;
import dev.vertique.config.parser.DefaultConfigParser;
import dev.vertique.core.config.ConfigParser;
import dev.vertique.core.exception.ConfigurationException;
import io.vertx.core.json.JsonObject;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link DelayedJobsConfig}, {@link DelayedJobQueueConfig}, and
 * {@link DelayedJobContractConfig}: keyed-object parsing of the {@code delayedJob} section into
 * typed, validated records with the object key injected into each element's identity field.
 *
 * <p>Verifies the six preserved queue fields and their defaults, contract overrides and defaults,
 * backoff-strategy validation, and blank/duplicate identity handling.
 */
@DisplayName("DelayedJobsConfig")
class DelayedJobsConfigTest {

    // --- Helpers ---

    /**
     * Returns a lenient {@link ConfigParser} for use in test call-sites that need to parse config.
     *
     * @return a {@link DefaultConfigParser} with lenient mapper
     */
    private static ConfigParser configParser() {
        return new DefaultConfigParser(DefaultConfigMapper.lenient());
    }

    // --- Queue parsing ---

    @Nested
    @DisplayName("queue parsing")
    class QueueParsing {

        @Test
        @DisplayName("a queue with only maxConcurrentJobs set keeps the other five fields at their defaults")
        void queueSixFieldsDefaults() {
            JsonObject root = new JsonObject()
                    .put(
                            "delayedJob",
                            new JsonObject()
                                    .put(
                                            "queues",
                                            new JsonObject()
                                                    .put("default", new JsonObject().put("maxConcurrentJobs", 9))));

            DelayedJobsConfig config = DelayedJobsConfig.fromConfig(root, configParser());
            Map<String, DelayedJobQueueConfig> index = config.queueIndex();

            DelayedJobQueueConfig queue = index.get("default");
            assertEquals("default", queue.name(), "name must be key-injected from the object key");
            assertEquals(9, queue.maxConcurrentJobs(), "the explicitly-set field wins");
            assertEquals(5000L, queue.sleepDelayMs());
            assertEquals("LINEAR", queue.backoffStrategy());
            assertEquals(30_000L, queue.backoffBaseDelayMs());
            assertEquals(3_600_000L, queue.backoffMaxDelayMs());
            assertEquals(1, queue.instances());
        }

        @Test
        @DisplayName("a fully specified queue keeps every supplied value and injects the name")
        void queueAllFieldsPreserved() {
            JsonObject root = new JsonObject()
                    .put(
                            "delayedJob",
                            new JsonObject()
                                    .put(
                                            "queues",
                                            new JsonObject()
                                                    .put(
                                                            "bulk",
                                                            new JsonObject()
                                                                    .put("sleepDelayMs", 1234L)
                                                                    .put("maxConcurrentJobs", 7)
                                                                    .put("backoffStrategy", "EXPONENTIAL")
                                                                    .put("backoffBaseDelayMs", 500L)
                                                                    .put("backoffMaxDelayMs", 90_000L)
                                                                    .put("instances", 3))));

            DelayedJobQueueConfig queue = DelayedJobsConfig.fromConfig(root, configParser())
                    .queueIndex()
                    .get("bulk");

            assertEquals("bulk", queue.name());
            assertEquals(1234L, queue.sleepDelayMs());
            assertEquals(7, queue.maxConcurrentJobs());
            assertEquals("EXPONENTIAL", queue.backoffStrategy());
            assertEquals(500L, queue.backoffBaseDelayMs());
            assertEquals(90_000L, queue.backoffMaxDelayMs());
            assertEquals(3, queue.instances());
        }

        @Test
        @DisplayName("an empty delayedJob section parses to no queues and no contracts")
        void emptySectionEmptyIndexes() {
            DelayedJobsConfig config = DelayedJobsConfig.fromConfig(new JsonObject(), configParser());

            assertTrue(config.queueIndex().isEmpty());
            assertTrue(config.contractIndex().isEmpty());
        }
    }

    // --- backoffStrategy validation ---

    @Nested
    @DisplayName("backoffStrategy validation")
    class BackoffStrategyValidation {

        @Test
        @DisplayName("an unrecognized backoffStrategy is rejected at parse time")
        void backoffStrategyInvalidRejected() {
            JsonObject root = new JsonObject()
                    .put(
                            "delayedJob",
                            new JsonObject()
                                    .put(
                                            "queues",
                                            new JsonObject()
                                                    .put("default", new JsonObject().put("backoffStrategy", "BOGUS"))));

            assertThrows(ConfigurationException.class, () -> DelayedJobsConfig.fromConfig(root, configParser()));
        }

        @Test
        @DisplayName("a lower-case backoffStrategy is accepted (case-insensitive)")
        void backoffStrategyCaseInsensitiveAccepted() {
            JsonObject root = new JsonObject()
                    .put(
                            "delayedJob",
                            new JsonObject()
                                    .put(
                                            "queues",
                                            new JsonObject()
                                                    .put("default", new JsonObject().put("backoffStrategy", "fixed"))));

            DelayedJobQueueConfig queue = DelayedJobsConfig.fromConfig(root, configParser())
                    .queueIndex()
                    .get("default");

            assertEquals("fixed", queue.backoffStrategy());
        }
    }

    // --- identity validation ---

    @Nested
    @DisplayName("identity validation")
    class IdentityValidation {

        @Test
        @DisplayName("a blank queue key is rejected at parse time")
        void blankQueueNameRejected() {
            JsonObject root = new JsonObject()
                    .put("delayedJob", new JsonObject().put("queues", new JsonObject().put("", new JsonObject())));

            assertThrows(ConfigurationException.class, () -> DelayedJobsConfig.fromConfig(root, configParser()));
        }

        @Test
        @DisplayName("an explicit name conflicting with the object key is rejected")
        void conflictingExplicitNameRejected() {
            JsonObject root = new JsonObject()
                    .put(
                            "delayedJob",
                            new JsonObject()
                                    .put(
                                            "queues",
                                            new JsonObject().put("default", new JsonObject().put("name", "other"))));

            assertThrows(ConfigurationException.class, () -> DelayedJobsConfig.fromConfig(root, configParser()));
        }
    }

    // --- contract parsing ---

    @Nested
    @DisplayName("contract parsing")
    class ContractParsing {

        @Test
        @DisplayName("a contract overrides maxAttempts, queue, and priority")
        void contractOverridesMaxAttemptsQueuePriority() {
            JsonObject root = new JsonObject()
                    .put(
                            "delayedJob",
                            new JsonObject()
                                    .put(
                                            "contracts",
                                            new JsonObject()
                                                    .put(
                                                            "deliver-webhook",
                                                            new JsonObject()
                                                                    .put("maxAttempts", 7)
                                                                    .put("queue", "priority")
                                                                    .put("priority", 10))));

            DelayedJobContractConfig contract = DelayedJobsConfig.fromConfig(root, configParser())
                    .contractIndex()
                    .get("deliver-webhook");

            assertEquals("deliver-webhook", contract.name());
            assertEquals(7, contract.maxAttempts());
            assertEquals("priority", contract.queue());
            assertEquals(10, contract.priority());
        }

        @Test
        @DisplayName("a contract with no overrides carries null for every override (annotation fallback)")
        void contractDefaults() {
            JsonObject root = new JsonObject()
                    .put(
                            "delayedJob",
                            new JsonObject().put("contracts", new JsonObject().put("plain-job", new JsonObject())));

            DelayedJobContractConfig contract = DelayedJobsConfig.fromConfig(root, configParser())
                    .contractIndex()
                    .get("plain-job");

            // Absent overrides stay null so the consumer falls back to the @DelayedJobContract
            // annotation value rather than to a config default that could clobber a non-default
            // annotation value on a sibling field.
            assertEquals("plain-job", contract.name());
            assertNull(contract.maxAttempts());
            assertNull(contract.queue());
            assertNull(contract.priority());
        }
    }
}
