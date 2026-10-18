// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.schema.fixtures;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;

/**
 * Payload types of the expense approval definition the descriptor tests read. They are public so that the
 * registration path can load them by their binary class name.
 */
public final class ExpensePayloads {

    private ExpensePayloads() {}

    /** The start payload of the expense definition. */
    public record ExpenseStart(String requester, long amountCents) {}

    /** The payload of the {@code approve} decision: a constrained and described comment. */
    public record ApprovalComment(
            @Size(max = 200) @Schema(description = "Why") String comment) {}

    /** A plain payload for a second human task. */
    public record AuditNote(String auditor, String finding) {}
}
