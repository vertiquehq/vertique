// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.registry.store;

/** Result of reconciling a static source with its durable metadata. */
public enum ReconcileOutcome {
    /** The version was recorded as available; the default scope's current version was left as it was. */
    INSERTED,
    /** The version was recorded as available and also became the default scope's current version. */
    INSERTED_AND_ACTIVATED,
    UNCHANGED,
    REHASHED,
    DRIFT,
    KIND_CONFLICT
}
