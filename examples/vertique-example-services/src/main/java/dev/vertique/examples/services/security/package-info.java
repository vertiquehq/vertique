// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

/**
 * Authorization wiring for the example-services {@code @RequiresAction} proof.
 *
 * <p>Contains the {@link dev.vertique.examples.services.security.AuthzProbeActionContributor} that
 * registers the {@code svc.probe.run} action, and the
 * {@link dev.vertique.examples.services.security.AuthzEventCollector} that records authorization
 * decision events and guarded-handler invocations so an integration test can verify enforcement
 * through the real, Dagger-wired services dispatch pipeline.
 *
 * <p>The policy/role mappings and the capturing {@code SecurityEventObserver} are bound in
 * {@link dev.vertique.examples.services.AuthzModule}.
 */
package dev.vertique.examples.services.security;
