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
 * Verifies that the actor id a delivery writes to the log for an outcome that records no command
 * passes through the shared text rule: an id taken from a message header cannot start a log line of
 * its own, carry a control or line separator character, or run past 255 code points.
 */
@ExtendWith(VertxExtension.class)
@Timeout(value = 20, unit = TimeUnit.SECONDS)
class WorkflowSignalSourceDeliveryIdentityLogTest {

    private static final String FORGED_LINE = "[2026-11-01 INFO] forged: admin granted";

    /** An id that ends one line with CRLF, begins a forged one, and adds a Unicode line separator. */
    private static final String FORGED_ACTOR = "mallory\r\n" + FORGED_LINE + " second";

    /** An id of 300 code points, none of which appears anywhere else in the record. */
    private static final String LONG_ACTOR = "Z".repeat(300);

    @Test
    @DisplayName("an actor id cannot forge a log line")
    void anActorIdCannotForgeALogLine(Vertx vertx) {
        // Given plans whose dispatchAs answers a user id holding CRLF and a forged line, and one of 300 characters
        for (String actorId : List.of(FORGED_ACTOR, LONG_ACTOR)) {
            String row = actorId.length() > 100 ? "the 300-character id" : "the forged id";
            SignalSourcePlan<ShipmentDelivered> plan =
                    ordersSignal(shipments().dispatchAs(message -> Future.succeededFuture(Optional.of(user(actorId)))));

            try (DeliveryRig rig = DeliveryRig.over(vertx)
                    .operationsRaising(new WorkflowSignalNoMatchException("orders", SHIPMENT_DELIVERED))) {
                // When the message is delivered and the operations answer no-match
                rig.deliver(plan, shipment(), envelope());

                // Then one log record is written for the outcome
                List<String> records = rig.logs().stream()
                        .filter(text -> text.contains(SHIPMENT_DELIVERED))
                        .toList();
                assertThat(records).as(row).hasSize(1);
                String logged = records.get(0);

                // and it holds no control or line separator character
                assertThat(logged.codePoints())
                        .as(row)
                        .noneMatch(WorkflowSignalSourceDeliveryIdentityLogTest::isControlOrLineSeparator);

                // and no line of it is the forged text
                assertThat(logged.lines()).as(row).noneMatch(line -> line.startsWith(FORGED_LINE));

                // and the actor id in it is at most 255 code points
                assertThat(logged).as(row).doesNotContain("Z".repeat(256));
                if (actorId.equals(LONG_ACTOR)) {
                    assertThat(logged).as(row).contains("Z".repeat(255));
                } else {
                    assertThat(logged).as(row).contains("mallory");
                }
            }
        }
    }

    private static boolean isControlOrLineSeparator(int codePoint) {
        int type = Character.getType(codePoint);
        return Character.isISOControl(codePoint)
                || type == Character.LINE_SEPARATOR
                || type == Character.PARAGRAPH_SEPARATOR;
    }
}
