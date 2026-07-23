// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.security.runtime.authz;

import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoSet;
import dev.vertique.security.DelegationGrantValidator;
import dev.vertique.security.authz.AuthorizationNarrower;

/**
 * Opt-in Dagger module that activates delegation-grant enforcement in the authorization engine
 * (PRD identity-002 FR-ID-DG-006).
 *
 * <p>Contributes {@link DelegationEnforcementNarrower} into the {@code Set<AuthorizationNarrower>}
 * multibinding declared by {@link SecurityAuthzModule}, so it folds into the
 * {@code NarrowingAuthorizer}/{@code NarrowingIntrospector} that module wires.
 *
 * <p><strong>Delegation enforcement is opt-in.</strong> Without this module installed, the
 * authorization graph is behavior-identical to today's: {@code SecurityAuthzModule}'s
 * {@code Set<AuthorizationNarrower>} multibinding is empty-by-default, so a delegated context's
 * evaluation is decided purely by the base engine's role/policy resolution, with no grant-scope
 * intersection ever applied. Installing this module is what turns delegation enforcement on.
 *
 * <p><strong>Install contract — no default {@link DelegationGrantValidator}.</strong> The installing
 * application must also provide a {@link DelegationGrantValidator} binding (e.g. bind the reference
 * {@code InMemoryDelegationGrantValidator}, or a store-backed implementation); without one the
 * Dagger graph will not compile. This module deliberately does <strong>not</strong> supply a default
 * {@link DelegationGrantValidator} binding — a default in-memory validator with no grants would
 * silently deny every delegated call, masking a missing grant store behind a false-negative rather
 * than a startup error. This mirrors {@link dev.vertique.security.runtime.IdentitySnapshotCarriageModule}'s
 * documented install contract in {@code vertique-security-runtime} (see its class javadoc): both modules require an
 * application to complete the wiring rather than papering over an absent dependency with a
 * fail-open or falsely-convenient default.
 *
 * <p>Include this module alongside {@link SecurityAuthzModule} in any Dagger {@code @Component} that
 * should enforce delegation-grant scope intersection.
 */
@Module
public abstract class DelegationEnforcementModule {

    private DelegationEnforcementModule() {
        /* Dagger abstract module — no instances */
    }

    /**
     * Contributes {@link DelegationEnforcementNarrower} into the {@code Set<AuthorizationNarrower>}
     * multibinding declared by {@link SecurityAuthzModule}.
     *
     * @param impl the singleton narrower, built from the application-supplied
     *             {@link DelegationGrantValidator}
     * @return the multibinding contribution
     */
    @Provides
    @IntoSet
    static AuthorizationNarrower delegationEnforcementNarrower(DelegationEnforcementNarrower impl) {
        return impl;
    }
}
