// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.engine;

import jakarta.annotation.Nullable;

/**
 * Typed payload for a {@code CANCELLED} history entry.
 *
 * <p>Records the human-readable cancellation reason supplied by the caller at the point the engine
 * transitions the instance to {@code CANCELLED}.
 *
 * @param reason the cancellation reason; may be {@code null} when no reason was provided
 * @param commandCorrelationId the correlation id bound to the ambient execution context at the
 *     point this entry was appended, or {@code null} when no correlation context was bound (PRD
 *     FR-WF-CTX-050/051). Additive field — history rows written before this field existed
 *     deserialize with {@code null}.
 */
record CancelledHistoryPayload(String reason, @Nullable String commandCorrelationId) {}
