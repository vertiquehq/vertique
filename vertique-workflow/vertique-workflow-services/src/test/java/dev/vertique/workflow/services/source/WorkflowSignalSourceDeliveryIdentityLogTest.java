// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.services.source;

import static dev.vertique.workflow.services.source.PlanFixture.SHIPMENT_DELIVERED;
import static dev.vertique.workflow.services.source.PlanFixture.envelope;
import static dev.vertique.workflow.services.source.PlanFixture.ordersSignal;
import static dev.vertique.workflow.services.source.PlanFixture.shipment;
import static dev.vertique.workflow.services.source.PlanFixture.shipments;
import static dev.vertique.workflow.services.source.PlanFixture.user;
import static org.assertj.core.api.Assertions.assertThat;

import dev.vertique.workflow.exception.WorkflowSignalNoMatchException;
import dev.vertique.workflow.services.source.PlanFixture.ShipmentDelivered;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.junit5.VertxExtension;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Verifies the log record a delivery writes for an outcome that records no command: one record that names the
 * actor by the id the source supplied, unchanged.
 */
@ExtendWith(VertxExtension.class)
@Timeout(value = 20, unit = TimeUnit.SECONDS)
class WorkflowSignalSourceDeliveryIdentityLogTest {

    @Test
    @DisplayName("an outcome that records no command is logged once with the actor id as supplied")
    void anOutcomeWithoutACommandIsLoggedWithTheActorId(Vertx vertx) {
        // Given a plan whose dispatchAs answers a user id, and operations that answer no-match
        String actorId = "mallory " + "Z".repeat(300);
        SignalSourcePlan<ShipmentDelivered> plan =
                ordersSignal(shipments().dispatchAs(message -> Future.succeededFuture(Optional.of(user(actorId)))));

        try (DeliveryRig rig = DeliveryRig.over(vertx)
                .operationsRaising(new WorkflowSignalNoMatchException("orders", SHIPMENT_DELIVERED))) {
            // When the message is delivered
            rig.deliver(plan, shipment(), envelope());

            // Then one log record is written for the outcome, and it names the actor by the whole id
            List<String> records = rig.logs().stream()
                    .filter(text -> text.contains(SHIPMENT_DELIVERED))
                    .toList();
            assertThat(records).hasSize(1);
            assertThat(records.get(0)).contains("actor '" + actorId + "'");
        }
    }
}
