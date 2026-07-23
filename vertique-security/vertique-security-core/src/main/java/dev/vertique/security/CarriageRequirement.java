// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.security;

/**
 * Operator-declared expectation for whether a durable dispatch target must carry a verified
 * identity snapshot (PRD-ID-002 §14.6 A9 "expected-but-absent carriage detection").
 *
 * <p>Config maps each durable-target kind (the {@code dev.vertique.core.context.DeferredExecutionOrigin#kind()}
 * / durable-target kind string, e.g. {@code "delayed-job"}, {@code "cron"}, {@code "outbox-relay"})
 * to one of these three requirements. The receive-side reconstruction initializer consults the
 * mapping to distinguish a legitimately carriage-free dispatch (a target never expected to carry
 * identity) from a dispatch whose expected carriage is missing — the only honest mechanism for
 * detecting the latter is operator intent recorded here, outside the app-writable durable store.
 */
public enum CarriageRequirement {

    /**
     * Every dispatch on this durable target MUST carry a verified identity snapshot. A dispatch
     * that arrives with no snapshot is an {@link SnapshotDegradationReason#EXPECTED_ABSENT}
     * degradation, not a silently-tolerated gap.
     *
     * <p>Forward hook: once Mode 2/3 (see PRD-ID-002 §14.6 S3/S4) are installable, any target-kind
     * enrolled in Mode 2/3 will be forced to {@code REQUIRED} regardless of its configured value —
     * that coupling lands in Phase-2 S3/S4 and is not implemented by this enum alone.
     */
    REQUIRED,

    /** Carriage may be absent on this durable target — today's default behavior. */
    OPTIONAL,

    /**
     * This durable target never carries identity (e.g. a system-scheduled cron trigger with no
     * originating principal) — an absent snapshot here is expected and never flagged. A
     * <em>present</em> snapshot on a FORBIDDEN target — verified or not — is refused fail-closed by
     * the receive-side reconstruction initializer rather than reconstructed: the target is declared
     * to never carry identity, so verification status is irrelevant once a snapshot arrives here at
     * all (F4 security review, PRD-ID-002 §14.6 A9).
     */
    FORBIDDEN
}
