// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.registry.store;

/** Result of reconciling a static source with its durable metadata. */
public enum ReconcileOutcome {
    INSERTED,
    UNCHANGED,
    REHASHED,
    DRIFT,
    KIND_CONFLICT
}
