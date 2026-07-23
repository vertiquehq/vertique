// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.security.runtime;

import dagger.Module;
import dagger.Provides;
import dev.vertique.security.IdentityReconstruction;
import jakarta.inject.Singleton;

/**
 * Dagger {@link Module} that provides {@link IdentityReconstruction} to framework infrastructure
 * components only (job execution, inbox/outbox, workflow resume).
 *
 * <p>Per identity-002 §14.3's privileged-boundary rule, this binding is deliberately kept out of
 * the general {@code SecurityEventsModule}/{@code SecurityAuthzModule} wiring: an application
 * that includes this module is doing so visibly, so accidental general-purpose injection of the
 * reconstruction service is caught at code review by an unexpected module in the component's
 * module list, rather than being silently available everywhere.
 *
 * <p>The {@link IdentitySnapshotCodec} this module wires {@link IdentityReconstruction} to is the
 * shared, config-backed singleton provided by {@link IdentitySnapshotCarriageModule} — this module
 * must always be installed alongside {@link IdentitySnapshotCarriageModule} in the application's
 * Dagger component.
 */
@Module
public abstract class PrivilegedIdentityModule {

    private PrivilegedIdentityModule() {
        /* Dagger abstract module — no instances */
    }

    /**
     * Provides the {@link IdentityReconstruction} singleton, backed by the shared
     * {@link IdentitySnapshotCodec}.
     *
     * @param codec the snapshot codec used to re-verify a snapshot's integrity before
     *              reconstruction; must not be {@code null}
     * @return a new {@link DefaultIdentityReconstruction}; never {@code null}
     */
    @Provides
    @Singleton
    static IdentityReconstruction identityReconstruction(IdentitySnapshotCodec codec) {
        return new DefaultIdentityReconstruction(codec);
    }
}
