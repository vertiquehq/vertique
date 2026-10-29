// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.engine.spi;

import dev.vertique.workflow.state.WorkflowHistoryEntry;
import jakarta.annotation.Nullable;

/**
 * INTERNAL: a committed history entry together with the kind of the command it names, as read by
 * {@link WorkflowHistoryRepository#listRange}.
 *
 * @param entry       the enriched history entry
 * @param commandKind the {@code kind} of the command row the entry names, or {@code null} when the entry names no
 *                    command
 */
public record CommittedHistoryEntry(
        WorkflowHistoryEntry entry, @Nullable String commandKind) {}
