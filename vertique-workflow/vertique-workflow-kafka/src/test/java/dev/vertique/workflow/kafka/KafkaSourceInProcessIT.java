// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.kafka;

import static dev.vertique.workflow.kafka.SourceScenario.SHIPMENT_DELIVERED;
import static org.assertj.core.api.Assertions.assertThat;

import dev.vertique.workflow.kafka.SourceScenario.Feed;
import dev.vertique.workflow.kafka.SourceScenario.OrdersContract;
import dev.vertique.workflow.kafka.SourceScenario.Produced;
import dev.vertique.workflow.kafka.SourceScenario.ShipmentDelivered;
import dev.vertique.workflow.kafka.SourceScenario.Waiting;
import io.vertx.core.Vertx;
import io.vertx.core.VertxOptions;
import io.vertx.core.metrics.MetricsOptions;
import io.vertx.core.spi.VertxMetricsFactory;
import io.vertx.core.spi.metrics.EventBusMetrics;
import io.vertx.core.spi.metrics.VertxMetrics;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Proves, on a real broker and a real PostgreSQL engine, that a node whose Kafka source is running records a signal
 * through an in-process call: the event bus holds no consumer of the application's, so correlated delivery has no
 * service operation behind an address.
 */
@Timeout(value = 120, unit = TimeUnit.SECONDS)
class KafkaSourceInProcessIT {

    /** The prefix of the addresses Vert.x registers for itself. */
    private static final String INTERNAL_PREFIX = "__vertx.";

    @Test
    void aRunningSourceRegistersNoEventBusConsumerAndStillRecordsTheSignal() throws Exception {
        // Given a Vert.x instance that notes every address a handler registers on, and a node with one running source
        List<String> registered = new CopyOnWriteArrayList<>();
        Vertx vertx = Vertx.builder()
                .with(new VertxOptions().setMetricsOptions(new MetricsOptions().setEnabled(true)))
                .withMetrics(new AddressNotingMetrics(registered))
                .build();
        try (SourceScenario scenario = SourceScenario.open(vertx, "in-process")) {
            Feed<ShipmentDelivered> carriers =
                    scenario.carriers("carriers", source -> source.dedupBy(ShipmentDelivered::eventId)
                            .signal(OrdersContract.class, SHIPMENT_DELIVERED)
                            .key(ShipmentDelivered::carrierRef)
                            .payload(SourceScenario::confirmation)
                            .build());
            scenario.start();
            String ref = scenario.ref("PAY-77");
            Waiting order = scenario.waitingOrder("acme", ref);

            // When a record is delivered through the running source
            Produced record = carriers.produce(scenario.delivered("1", ref));
            scenario.awaitDelivered(record, order);

            // Then the instance resumed once, so the delivery works
            assertThat(scenario.signalsApplied(order)).isEqualTo(1);

            // and the event bus holds no handler of the application's: none for a service operation and none for
            // correlated delivery
            assertThat(registered).noneMatch(address -> !address.startsWith(INTERNAL_PREFIX));

            // and the note does see an application handler, so the check above is not blind
            vertx.eventBus().consumer("probe.handler", message -> {});
            assertThat(registered).contains("probe.handler");
        } finally {
            vertx.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
        }
    }

    /** Metrics that do nothing but note the address of each event-bus handler registered. */
    private static final class AddressNotingMetrics implements VertxMetricsFactory {

        private final List<String> registered;

        AddressNotingMetrics(List<String> registered) {
            this.registered = registered;
        }

        @Override
        public VertxMetrics metrics(VertxOptions options) {
            return new VertxMetrics() {
                @Override
                public EventBusMetrics<?> createEventBusMetrics() {
                    return new EventBusMetrics<Void>() {
                        @Override
                        public Void handlerRegistered(String address) {
                            registered.add(address);
                            return null;
                        }
                    };
                }
            };
        }
    }
}
