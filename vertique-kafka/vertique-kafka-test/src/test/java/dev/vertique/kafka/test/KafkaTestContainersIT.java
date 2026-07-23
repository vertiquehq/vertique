// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.kafka.test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.testcontainers.kafka.KafkaContainer;

/** Integration proof that the renamed test-support artifact starts a usable shared Kafka broker. */
@Timeout(value = 120, unit = TimeUnit.SECONDS)
class KafkaTestContainersIT {

    @Test
    @DisplayName("shared starts a running Kafka broker with bootstrap servers")
    void sharedStartsVerifiedBroker() {
        KafkaContainer kafka = KafkaTestContainers.shared();

        assertTrue(kafka.isRunning(), "shared broker must be running after verification");
        assertNotNull(kafka.getBootstrapServers(), "shared broker must expose bootstrap servers");
    }
}
