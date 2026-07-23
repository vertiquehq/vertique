// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.security.runtime;

/**
 * Policy governing what happens when a deferred execution's carried
 * {@link dev.vertique.security.IdentitySnapshot} is present but fails HMAC verification
 * (PRD-ID-002 §14.3 "Durable carriage" — {@code identity.snapshot.onDegradation}).
 *
 * <p>Consulted by the service-dispatch degradation gate (in {@code vertique-services}) after the
 * receive-side initializer binds a {@code SnapshotDegradationMarker} for an unverifiable snapshot.
 */
public enum IdentitySnapshotDegradationPolicy {

    /** Fail the deferred dispatch outright when the carried snapshot cannot be verified. */
    FAIL,

    /** Continue the operation without verified identity state. */
    CONTINUE_WITHOUT_IDENTITY
}
