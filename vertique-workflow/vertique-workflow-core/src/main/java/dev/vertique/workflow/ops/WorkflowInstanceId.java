// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.ops;

import java.util.UUID;

/**
 * Strongly-typed wrapper for a workflow instance's UUID primary key.
 *
 * <p>Using a dedicated record type instead of a raw {@link UUID} prevents accidental confusion with
 * other UUID-typed identifiers in the domain.
 *
 * @param value the underlying UUID value
 */
public record WorkflowInstanceId(UUID value) {}
