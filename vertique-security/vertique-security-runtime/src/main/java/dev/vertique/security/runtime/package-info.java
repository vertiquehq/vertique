// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

/**
 * Vertique Security runtime implementations.
 *
 * <p>This package is the root of the {@code vertique-security-runtime} module, which provides
 * the default implementations of the SPIs declared in {@code vertique-security-core}:
 * <ul>
 *   <li>{@code dev.vertique.security.runtime.authz} — authorization engine:
 *       {@code DefaultAuthorizer}, {@code DefaultActionRegistry}, {@code DefaultAuthorizationIntrospector},
 *       {@code InMemoryPolicyDefinitionSource}, {@code InMemoryRolePolicyResolver},
 *       {@code AuthzResolution}, {@code BuiltinAuthzActionContributor}, and
 *       {@code SecurityAuthzModule} (Dagger wiring)</li>
 *   <li>{@code dev.vertique.security.runtime.events} — security event pipeline:
 *       {@code SecurityEventEmitter} and {@code SecurityEventsModule} (Dagger wiring)</li>
 *   <li>Identity-snapshot durable carriage and receive-side reconstruction:
 *       {@code IdentitySnapshotCarriageModule} (config-backed keyset/codec + durable encoder/decoder),
 *       {@code PrivilegedIdentityModule} ({@code IdentityReconstruction}), and
 *       {@code IdentitySnapshotReconstructionModule} — the opt-in composition an application
 *       includes to wire {@code IdentitySnapshotReconstructionInitializer} into every
 *       {@code SERVICE_DISPATCH} receive (delayed-job execution, inbox handler dispatch)</li>
 * </ul>
 *
 * <p>No type in this module is part of the public API — consumers depend on the interfaces in
 * {@code vertique-security-core} and wire implementations via the Dagger modules.
 */
package dev.vertique.security.runtime;
