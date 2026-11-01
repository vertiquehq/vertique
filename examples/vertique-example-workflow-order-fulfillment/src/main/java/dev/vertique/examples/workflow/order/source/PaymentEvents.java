// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.workflow.order.source;

import dev.vertique.examples.workflow.order.OrderFulfillmentWorkflow;
import dev.vertique.examples.workflow.order.signal.PaymentCaptured;
import dev.vertique.workflow.kafka.KafkaSignalListener;
import dev.vertique.workflow.source.WorkflowSignalTarget;

/**
 * Resumes the order that waits at {@code wait-payment} when a {@code PaymentCaptured} event arrives on the
 * {@code payments.captured} topic.
 *
 * <p>The event holds the order id and no workflow instance id: the wait keys on the order id, so the event's
 * {@code orderId} finds the order. The interface needs no implementation, because the code generator turns it into a
 * signal source and registers it.
 */
@KafkaSignalListener(name = "payment-events", topic = "payments.captured", groupId = "order-fulfillment")
public interface PaymentEvents {

    /**
     * Delivers the event to the order whose id equals the event's {@code orderId}.
     *
     * @param event the captured payment
     */
    @WorkflowSignalTarget(contract = OrderFulfillmentWorkflow.class, signal = "payment.captured", key = "orderId")
    void captured(PaymentCaptured event);
}
