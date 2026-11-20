// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.ops;

/** INTERNAL observer of reply recording outcomes. Carries no payload or handle. */
public interface ReplyOutcomeObserver {
    /** Outcome of a reply recording or wait check. */
    enum Outcome {
        RECORDED,
        DUPLICATE,
        STALE,
        REFUSED
    }

    /** Receives an outcome without affecting delivery. */
    void onOutcome(Outcome outcome);
}
