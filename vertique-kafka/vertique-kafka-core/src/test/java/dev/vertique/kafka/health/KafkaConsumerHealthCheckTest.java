// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.kafka.health;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import dev.vertique.core.health.HealthCheckResult;
import dev.vertique.core.health.HealthStatus;
import dev.vertique.kafka.KafkaConsumerRegistry;
import io.vertx.core.Future;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for {@link KafkaConsumerHealthCheck}: verifies that the check always reports
 * {@link HealthStatus#UP} and surfaces the correct total/enabled counts from the registry.
 */
@ExtendWith(VertxExtension.class)
@ExtendWith(MockitoExtension.class)
class KafkaConsumerHealthCheckTest {

    @Test
    @DisplayName("returns UP with correct total and enabled counts")
    void shouldReportUpWithEnabledConsumers(VertxTestContext ctx, @Mock KafkaConsumerRegistry registry) {
        when(registry.totalCount()).thenReturn(2);
        when(registry.enabledCount()).thenReturn(1L);

        KafkaConsumerHealthCheck check = new KafkaConsumerHealthCheck(registry);

        Future<HealthCheckResult> result = check.check();

        result.onComplete(ctx.succeeding(r -> {
            ctx.verify(() -> {
                assertEquals(HealthStatus.UP, r.status(), "Status must be UP");
                assertEquals(2, r.data().get("total"), "total must match registry totalCount");
                assertEquals(1L, r.data().get("enabled"), "enabled must match registry enabledCount");
            });
            ctx.completeNow();
        }));
    }

    @Test
    @DisplayName("returns UP with zero counts when registry is empty")
    void shouldReportUpWithNoConsumers(VertxTestContext ctx, @Mock KafkaConsumerRegistry registry) {
        when(registry.totalCount()).thenReturn(0);
        when(registry.enabledCount()).thenReturn(0L);

        KafkaConsumerHealthCheck check = new KafkaConsumerHealthCheck(registry);

        Future<HealthCheckResult> result = check.check();

        result.onComplete(ctx.succeeding(r -> {
            ctx.verify(() -> {
                assertEquals(HealthStatus.UP, r.status(), "Status must be UP even with no consumers");
                assertEquals(0, r.data().get("total"), "total must be 0 for empty registry");
                assertEquals(0L, r.data().get("enabled"), "enabled must be 0 for empty registry");
            });
            ctx.completeNow();
        }));
    }

    @Test
    @DisplayName("check name is kafka-consumers")
    void shouldHaveCorrectName(@Mock KafkaConsumerRegistry registry) {
        KafkaConsumerHealthCheck check = new KafkaConsumerHealthCheck(registry);
        assertEquals("kafka-consumers", check.name());
    }
}
