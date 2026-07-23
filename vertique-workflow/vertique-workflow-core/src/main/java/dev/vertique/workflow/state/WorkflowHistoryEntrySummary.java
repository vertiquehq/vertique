// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.state;

import java.time.Instant;
import java.util.Objects;

/**
 * A narrow projection over {@link WorkflowHistoryEntry} surfaced to migration handlers.
 *
 * <p>Migration handlers receive a list of recent {@code WorkflowHistoryEntrySummary} instances
 * via {@link dev.vertique.workflow.migration.MigrationContext#recentHistory()}. This projection
 * intentionally omits the full {@code payloadJson} to prevent handlers from coupling to
 * engine-internal payload typing (PRD-WF-003 §7.4).
 *
 * <p>Field names and semantics mirror the corresponding fields on {@link WorkflowHistoryEntry}:
 * <ul>
 *   <li>{@code sequence} — monotonically increasing sequence number within the instance.</li>
 *   <li>{@code entryType} — typed discriminator; see {@link WorkflowEntryType}.</li>
 *   <li>{@code occurredAt} — timestamp when this entry was persisted; corresponds to
 *       {@link WorkflowHistoryEntry#recordedAt()}.</li>
 * </ul>
 *
 * @param sequence monotonically increasing sequence number within the workflow instance; starts at
 *     1
 * @param entryType typed discriminator identifying the kind of event recorded; must not be
 *     {@code null}
 * @param occurredAt timestamp when this entry was persisted; must not be {@code null}
 */
public record WorkflowHistoryEntrySummary(long sequence, WorkflowEntryType entryType, Instant occurredAt) {

    /**
     * Compact constructor — validates that {@code entryType} and {@code occurredAt} are non-null.
     *
     * @param sequence sequence number; any long value is accepted
     * @param entryType entry type discriminator; must not be {@code null}
     * @param occurredAt persistence timestamp; must not be {@code null}
     * @throws NullPointerException if {@code entryType} or {@code occurredAt} is {@code null}
     */
    public WorkflowHistoryEntrySummary {
        Objects.requireNonNull(entryType, "entryType");
        Objects.requireNonNull(occurredAt, "occurredAt");
    }
}
