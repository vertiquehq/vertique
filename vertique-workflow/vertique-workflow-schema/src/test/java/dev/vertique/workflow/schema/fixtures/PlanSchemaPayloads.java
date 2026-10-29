// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.schema.fixtures;

import jakarta.validation.constraints.NotNull;

/** Payload types of the order definition the plan schema producer test reads. They are public so they can be loaded. */
public final class PlanSchemaPayloads {

    private PlanSchemaPayloads() {}

    /** The start payload: one required text and one number. */
    public record OrderStart(@NotNull String orderId, int amount) {}

    /** The payload of the {@code approve} decision. */
    public record ApprovePayload(String approver) {}

    /** The payload of the {@code reject} decision. */
    public record RejectPayload(String reason) {}
}
