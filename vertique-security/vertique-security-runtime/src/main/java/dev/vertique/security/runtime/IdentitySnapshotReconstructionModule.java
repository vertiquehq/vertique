// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.security.runtime;

import dagger.BindsOptionalOf;
import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoSet;
import dev.vertique.core.context.ContextHolder;
import dev.vertique.core.context.InboundContextInitializer;
import dev.vertique.security.IdentityReconstruction;
import jakarta.inject.Singleton;
import java.util.Optional;

/**
 * Dagger module that wires the receive-side identity-snapshot reconstruction path into deferred
 * execution boundaries (PRD-ID-002 §14.3 "Frozen binding-precedence rule", §14.6 slice P1.S5c).
 *
 * <p>Includes {@link IdentitySnapshotCarriageModule} (config-backed HMAC keyset, codec, and the
 * durable encoder/decoder pair), {@link PrivilegedIdentityModule} ({@link IdentityReconstruction}),
 * and {@code vertique-context}'s {@code ContextRuntimeModule} (the {@link ContextHolder} binding and
 * the {@code Set<InboundContextInitializer>} multibinding declaration itself — included directly here
 * rather than assumed from the host component, so this module wires standalone; Dagger deduplicates
 * the include when the host component also brings it in via {@code DispatchModule}). It then
 * contributes {@link IdentitySnapshotReconstructionInitializer} into that multibinding — the same one
 * {@code CorrelationContextSeeder} contributes to. Any Dagger component that includes
 * {@code DispatchModule} (directly or transitively) picks up this contribution automatically on its
 * {@code Set<InboundContextInitializer>} injection point ({@code InboundExecutionContextScope}), so
 * the initializer runs on every {@code SERVICE_DISPATCH} receive (delayed-job execution, inbox
 * handler dispatch, and any other event-bus service dispatch).
 *
 * <p>The {@link ServiceIdentityResolver} that supplies the executing actor is an overridable seam.
 * The framework provides <em>no</em> concrete {@code ServiceIdentityResolver} binding — only a
 * {@link BindsOptionalOf} declaration — so the initializer falls back to its own built-in default
 * (a system {@code scheduledJob} identity whose reason is the {@code DeferredExecutionOrigin}'s
 * reference) when no application binding is present. An application overrides the default simply by
 * binding its own {@code @Provides @Singleton ServiceIdentityResolver} in one of its modules: the
 * injected {@link Optional} is then present and is used in preference to the built-in default. There
 * is no duplicate-binding collision because the framework contributes no concrete resolver of its
 * own. This mirrors the existing {@code @BindsOptionalOf IdentitySnapshotCapture} pattern.
 *
 * <p>Applications wiring identity-snapshot durable carriage into deferred execution must include
 * this module (and supply the required {@code identity.snapshot.hmacKeys} config section that
 * {@link IdentitySnapshotCarriageModule} depends on) alongside their {@code DelayedJobModule} /
 * inbox-outbox module and {@code DispatchModule}. Without it, a carried {@code IdentitySnapshotContext}
 * is decoded by the durable propagator but never reconstructed into a live {@code SecurityContext} —
 * the dispatch instead falls through to whatever default the substrate provides for an unbound
 * context.
 */
@Module(
        includes = {
            IdentitySnapshotCarriageModule.class,
            PrivilegedIdentityModule.class,
            dev.vertique.context.ContextRuntimeModule.class,
        })
public abstract class IdentitySnapshotReconstructionModule {

    private IdentitySnapshotReconstructionModule() {
        /* Dagger abstract module — no instances */
    }

    /**
     * Declares an optional {@link ServiceIdentityResolver} binding without providing a concrete
     * default. When an application binds its own {@code @Provides @Singleton ServiceIdentityResolver}
     * the injected {@link Optional} is present and used; otherwise the initializer falls back to its
     * built-in default (a system {@code scheduledJob} identity keyed on the deferred-execution
     * origin's reference).
     *
     * @return the optional-binding declaration (Dagger-generated; never invoked directly)
     */
    @BindsOptionalOf
    abstract ServiceIdentityResolver serviceIdentityResolver();

    /**
     * Contributes {@link IdentitySnapshotReconstructionInitializer} into the
     * {@link InboundContextInitializer} multibinding set.
     *
     * @param impl the singleton initializer instance
     * @return the multibinding contribution
     */
    @Provides
    @IntoSet
    static InboundContextInitializer identitySnapshotReconstructionInitializer(
            IdentitySnapshotReconstructionInitializer impl) {
        return impl;
    }

    /**
     * Provides the {@link IdentitySnapshotReconstructionInitializer} singleton.
     *
     * @param holder                  the context holder to read/bind against
     * @param reconstruction          the privileged reconstruction service (from
     *                                {@link PrivilegedIdentityModule})
     * @param serviceIdentityResolver the optional application-supplied resolver for the executing
     *                                service identity; empty when no application binding is present,
     *                                in which case the initializer uses its built-in default
     * @param config                  the parsed identity-snapshot config (from
     *                                {@link IdentitySnapshotCarriageModule}) supplying per-target-kind
     *                                {@link dev.vertique.security.CarriageRequirement} lookups
     * @return the singleton initializer
     */
    @Provides
    @Singleton
    static IdentitySnapshotReconstructionInitializer identitySnapshotReconstructionInitializerInstance(
            ContextHolder holder,
            IdentityReconstruction reconstruction,
            Optional<ServiceIdentityResolver> serviceIdentityResolver,
            IdentitySnapshotConfig config) {
        return new IdentitySnapshotReconstructionInitializer(holder, reconstruction, serviceIdentityResolver, config);
    }
}
