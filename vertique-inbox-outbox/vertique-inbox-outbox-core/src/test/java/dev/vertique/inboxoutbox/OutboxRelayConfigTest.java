// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.inboxoutbox;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link OutboxRelayConfig} Jackson deserialization and default values.
 * Verifies that all defaults match the documented PRD values and that explicit overrides
 * round-trip correctly.
 */
@DisplayName("OutboxRelayConfig")
class OutboxRelayConfigTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Nested
    @DisplayName("defaults from empty JSON")
    class Defaults {

        @Test
        @DisplayName("pollingIntervalMs defaults to 1000")
        void pollingIntervalMsDefault() throws Exception {
            OutboxRelayConfig config = mapper.readValue("{}", OutboxRelayConfig.class);
            assertEquals(1_000L, config.pollingIntervalMs());
        }

        @Test
        @DisplayName("batchSize defaults to 50")
        void batchSizeDefault() throws Exception {
            OutboxRelayConfig config = mapper.readValue("{}", OutboxRelayConfig.class);
            assertEquals(50, config.batchSize());
        }

        @Test
        @DisplayName("leaseTimeoutMs defaults to 30000")
        void leaseTimeoutMsDefault() throws Exception {
            OutboxRelayConfig config = mapper.readValue("{}", OutboxRelayConfig.class);
            assertEquals(30_000L, config.leaseTimeoutMs());
        }

        @Test
        @DisplayName("maxAttempts defaults to 20")
        void maxAttemptsDefault() throws Exception {
            OutboxRelayConfig config = mapper.readValue("{}", OutboxRelayConfig.class);
            assertEquals(20, config.maxAttempts());
        }

        @Test
        @DisplayName("backoffBaseDelayMs defaults to 1000")
        void backoffBaseDelayMsDefault() throws Exception {
            OutboxRelayConfig config = mapper.readValue("{}", OutboxRelayConfig.class);
            assertEquals(1_000L, config.backoffBaseDelayMs());
        }

        @Test
        @DisplayName("backoffMaxDelayMs defaults to 300000")
        void backoffMaxDelayMsDefault() throws Exception {
            OutboxRelayConfig config = mapper.readValue("{}", OutboxRelayConfig.class);
            assertEquals(300_000L, config.backoffMaxDelayMs());
        }

        @Test
        @DisplayName("strategy defaults to LISTEN_NOTIFY")
        void strategyDefault() throws Exception {
            OutboxRelayConfig config = mapper.readValue("{}", OutboxRelayConfig.class);
            assertEquals(RelayStrategy.LISTEN_NOTIFY, config.strategy());
        }

        @Test
        @DisplayName("instances defaults to 1")
        void instancesDefault() throws Exception {
            OutboxRelayConfig config = mapper.readValue("{}", OutboxRelayConfig.class);
            assertEquals(1, config.instances());
        }
    }

    @Nested
    @DisplayName("deserialization with overridden values")
    class WithOverrides {

        @Test
        @DisplayName("overrides all fields from JSON")
        void overridesAllFields() throws Exception {
            String json = """
                    {
                      "pollingIntervalMs": 5000,
                      "batchSize": 100,
                      "leaseTimeoutMs": 60000,
                      "maxAttempts": 10,
                      "backoffBaseDelayMs": 2000,
                      "backoffMaxDelayMs": 600000,
                      "strategy": "POLLING",
                      "instances": 3
                    }
                    """;

            OutboxRelayConfig config = mapper.readValue(json, OutboxRelayConfig.class);
            assertEquals(5_000L, config.pollingIntervalMs());
            assertEquals(100, config.batchSize());
            assertEquals(60_000L, config.leaseTimeoutMs());
            assertEquals(10, config.maxAttempts());
            assertEquals(2_000L, config.backoffBaseDelayMs());
            assertEquals(600_000L, config.backoffMaxDelayMs());
            assertEquals(RelayStrategy.POLLING, config.strategy());
            assertEquals(3, config.instances());
        }

        @Test
        @DisplayName("partial override keeps unset fields at their defaults")
        void partialOverrideKeepsDefaults() throws Exception {
            String json = """
                    {
                      "batchSize": 25,
                      "instances": 2
                    }
                    """;

            OutboxRelayConfig config = mapper.readValue(json, OutboxRelayConfig.class);
            assertEquals(25, config.batchSize());
            assertEquals(2, config.instances());
            // Unset fields retain defaults
            assertEquals(1_000L, config.pollingIntervalMs());
            assertEquals(30_000L, config.leaseTimeoutMs());
            assertEquals(20, config.maxAttempts());
            assertEquals(RelayStrategy.LISTEN_NOTIFY, config.strategy());
        }
    }
}
