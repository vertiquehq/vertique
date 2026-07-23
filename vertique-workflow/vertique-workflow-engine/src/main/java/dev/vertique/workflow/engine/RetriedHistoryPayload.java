// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.engine;

import jakarta.annotation.Nullable;

/**
 * Typed payload for a {@code RETRIED} history entry.
 *
 * <p>Records the status the instance held immediately before the retry was initiated. The engine
 * always populates this with {@code "FAILED"} since retry is only permitted from that status.
 *
 * @param previousStatus the status string the instance held before the retry transition
 * @param commandCorrelationId the correlation id bound to the ambient execution context at the
 *     point this entry was appended, or {@code null} when no correlation context was bound (PRD
 *     FR-WF-CTX-050/051). Additive field — history rows written before this field existed
 *     deserialize with {@code null}.
 */
record RetriedHistoryPayload(
        String previousStatus, @Nullable String commandCorrelationId) {}
