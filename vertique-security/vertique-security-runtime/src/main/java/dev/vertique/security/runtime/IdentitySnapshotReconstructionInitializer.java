// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.security.runtime;

import dev.vertique.context.CompositeContextScope;
import dev.vertique.core.context.ContextHolder;
import dev.vertique.core.context.ContextScopes;
import dev.vertique.core.context.DeferredExecutionOrigin;
import dev.vertique.core.context.DurableCarrierDescriptor;
import dev.vertique.core.context.InboundContextInitializationContext;
import dev.vertique.core.context.InboundContextInitializer;
import dev.vertique.security.CarriageRequirement;
import dev.vertique.security.IdentityReconstruction;
import dev.vertique.security.IdentityReconstructionException;
import dev.vertique.security.IdentitySnapshot;
import dev.vertique.security.PrincipalType;
import dev.vertique.security.SecurityContext;
import dev.vertique.security.SecurityContexts;
import dev.vertique.security.SecurityIdentity;
import dev.vertique.security.SnapshotDegradationMarker;
import dev.vertique.security.SnapshotDegradationReason;
import dev.vertique.security.SystemIdentities;
import java.util.Objects;
import java.util.Optional;

/**
 * Receive-side {@link InboundContextInitializer} that binds the correct {@link SecurityContext}
 * for a deferred dispatch per the frozen binding-precedence rule (PRD-ID-002 §14.3 "Frozen
 * binding-precedence rule", §14.3 amendment A6 "deferred-execution provenance gate", §14.6
 * amendment A9 "origin-or-verified mint evidence"):
 *
 * <ol>
 *   <li><b>Case 1 (live)</b> — a live {@link SecurityContext} is already bound; no-op (idempotent), a
 *       {@link ContextScopes#noop()} scope is returned.
 *   <li><b>Case 2 (reconstruct)</b> — a <em>verified</em> {@link IdentitySnapshotContext} is bound;
 *       {@link IdentityReconstruction#deferredExecution(SecurityIdentity, dev.vertique.security.IdentitySnapshot,
 *       dev.vertique.core.context.DurableCarrierDescriptor)} reconstructs the context, which is then bound.
 *       A verified snapshot is HMAC-trusted, so this case mints/reconstructs regardless of whether a
 *       {@link DeferredExecutionOrigin} is present — <em>unless</em> case 2f below refuses it first.
 *   <li><b>Case 2f (FORBIDDEN refusal, F4 security review)</b> — before case 2 or case 3 runs, a
 *       <em>present</em> {@link IdentitySnapshotContext} — verified or unverifiable — whose resolved
 *       target-kind is {@link CarriageRequirement#FORBIDDEN} is refused outright:
 *       {@link #degradeWithoutContext(SnapshotDegradationReason)} binds only the
 *       {@link SnapshotDegradationMarker}, no {@link SecurityContext}. A FORBIDDEN target-kind is
 *       declared to never carry identity, so a snapshot arriving on one — even one that would
 *       otherwise verify — must never mint an identity; verification status is irrelevant once the
 *       target is FORBIDDEN.
 *   <li><b>Case 3 (degrade)</b> — an {@link IdentitySnapshotContext} is bound but the identity could
 *       not be verified. Per the origin-or-verified mint evidence rule (A9), an unverifiable snapshot
 *       mints a service {@link SecurityContext} <em>only</em> when a {@link DeferredExecutionOrigin}
 *       proves the dispatch is deferred execution — an unverifiable (potentially garbage) snapshot is,
 *       unlike case 2, not itself trust evidence:
 *       <ul>
 *         <li>origin present — {@link #degrade(SecurityIdentity, SnapshotDegradationReason)} binds a
 *             service-only context (no subject) via {@link #mintServiceContext(SecurityIdentity)}
 *             alongside a {@link SnapshotDegradationMarker}.</li>
 *         <li>origin absent — {@link #degradeWithoutContext(SnapshotDegradationReason)} binds
 *             <em>only</em> the {@link SnapshotDegradationMarker}, no {@link SecurityContext}.
 *             Minting a context here would let an attacker who writes a garbage, unverifiable durable
 *             row with no origin marker end up strictly better off under
 *             {@code CONTINUE_WITHOUT_IDENTITY} than writing nothing at all (case 5's fail-closed no-op).</li>
 *       </ul>
 *       Either way the bound {@link SnapshotDegradationMarker} lets the async degradation gate (a
 *       later service-dispatch interceptor) emit the non-droppable degradation event and apply the
 *       configured {@code FAIL}/{@code CONTINUE_WITHOUT_IDENTITY} policy. This covers two sources of the
 *       same fail-closed outcome: a
 *       {@link IdentitySnapshotContext#unverifiable(SnapshotDegradationReason)} context bound by
 *       {@link IdentitySnapshotDurableDecoder} (case 3a — the durable snapshot failed HMAC / decode /
 *       schema verification on the receive side), and a verified-at-decode snapshot whose
 *       reconstruction re-verification (FR-ID-CA-008) nonetheless throws
 *       {@link IdentityReconstructionException} (case 3b, defense-in-depth) — both apply the same
 *       origin gate.
 *   <li><b>Case 4 (bounded mint)</b> — no {@link IdentitySnapshotContext} is bound but a
 *       {@link DeferredExecutionOrigin} <em>proves</em> the dispatch is deferred execution (a durable
 *       delayed job, a cron trigger, or an outbox relay) — a service context is minted via
 *       {@link #mintServiceContext(SecurityIdentity)} from the origin-resolved executing identity, no
 *       subject. When the resolved target-kind (§14.6 A9, below) is
 *       {@link CarriageRequirement#REQUIRED}, an {@link SnapshotDegradationReason#EXPECTED_ABSENT}
 *       {@link SnapshotDegradationMarker} is bound <em>in addition to</em> the minted context — a
 *       REQUIRED target that dispatches with no snapshot at all is a degradation, not a silent gap.
 *   <li><b>Case 5 (fail closed)</b> — no {@link IdentitySnapshotContext} <em>and</em> no
 *       {@link DeferredExecutionOrigin}: an ordinary context-empty dispatch with no evidence of
 *       deferred execution binds <em>nothing</em> ({@link ContextScopes#noop()}) when the resolved
 *       target-kind is {@link CarriageRequirement#OPTIONAL} or {@link CarriageRequirement#FORBIDDEN}.
 *       Minting a SYSTEM context here would over-broadly privilege any context-empty service
 *       dispatch, so the initializer fails closed and {@code ServiceAuthorizationInterceptor} treats
 *       the unbound context as {@code AUTHENTICATION_REQUIRED} (PRD-ID-002 §14.3 A6). When the
 *       resolved target-kind is {@link CarriageRequirement#REQUIRED}, an
 *       {@link SnapshotDegradationReason#EXPECTED_ABSENT} {@link SnapshotDegradationMarker} is bound
 *       instead of the plain no-op — no origin proves deferred execution here, so still no
 *       {@link SecurityContext} is minted, but the missing expected carriage is no longer silent.
 * </ol>
 *
 * <p><b>Expected-but-absent carriage detection (§14.6 A9).</b> Cases 4 and 5 additionally resolve
 * the dispatch's durable-target kind — {@link DeferredExecutionOrigin#kind()} when an origin is
 * present, else the {@link InboundContextInitializationContext#boundary()} string — and look up its
 * {@link CarriageRequirement} via the injected {@link IdentitySnapshotConfig}. Operator-declared
 * carriage requirements, recorded outside the app-writable durable store, are the only honest
 * mechanism for distinguishing a target that never carries identity (still silent) from a target
 * whose expected carriage is missing (now flagged via the same {@link SnapshotDegradationMarker} /
 * async degradation-gate path that unverifiable-snapshot cases use).
 *
 * <p>Case 2 and case 4 always leave a {@link SecurityContext} bound. Case 3 leaves one bound only
 * when a {@link DeferredExecutionOrigin} is present — the same origin-or-verified mint evidence rule
 * that splits case 3. Case 2f and case 5 never mint a {@link SecurityContext}. The deferred-execution
 * provenance gate (case 4 vs. case 5, and case 3's origin-present vs. origin-absent split) is the
 * security boundary that keeps the bounded SYSTEM mint from applying to an ordinary inline dispatch,
 * or to an unproven-deferred unverifiable snapshot, that merely happens to carry no context/no
 * origin; case 2f is a separate, unconditional boundary that overrides even case 2's HMAC-trusted
 * mint evidence once the target-kind itself is declared FORBIDDEN.
 *
 * <p>The executing service identity is resolved from the {@link DeferredExecutionOrigin} via the
 * optional {@link ServiceIdentityResolver} seam; when no application resolver is bound the built-in
 * {@link #DEFAULT_RESOLVER} maps the origin's {@link DeferredExecutionOrigin#reference()} to a system
 * {@code scheduledJob} identity. When a snapshot is present but no origin is bound (durable carriage
 * without an origin marker), the executing identity falls back to a system {@code scheduledJob}
 * identity keyed on the boundary string.
 *
 * <p>Registered via {@code @Provides @IntoSet InboundContextInitializer} by the execution-path
 * wiring that installs it (not this class — see slice P1.S5c).
 */
public final class IdentitySnapshotReconstructionInitializer implements InboundContextInitializer {

    /**
     * Built-in default resolver used when no application {@link ServiceIdentityResolver} is bound:
     * maps the deferred-execution origin's {@link DeferredExecutionOrigin#reference()} to a system
     * {@code scheduledJob} identity whose {@code system.reason} is that reference.
     */
    private static final ServiceIdentityResolver DEFAULT_RESOLVER =
            origin -> SystemIdentities.scheduledJob(origin.reference());

    private final ContextHolder holder;
    private final IdentityReconstruction reconstruction;
    private final Optional<ServiceIdentityResolver> serviceIdentityResolver;
    private final IdentitySnapshotConfig config;

    /**
     * Constructs an {@code IdentitySnapshotReconstructionInitializer}.
     *
     * @param holder                  the context holder to read/bind against; must not be
     *                                {@code null}
     * @param reconstruction          the privileged reconstruction service used for case 2; must
     *                                not be {@code null}
     * @param serviceIdentityResolver the optional application-supplied resolver for the executing
     *                                service identity; must not be {@code null} as an
     *                                {@code Optional} (an empty {@code Optional} selects the built-in
     *                                {@link #DEFAULT_RESOLVER})
     * @param config                  the identity-snapshot config supplying per-target-kind
     *                                {@link CarriageRequirement} lookups (§14.6 A9); must not be
     *                                {@code null}. Injected directly rather than through a dedicated
     *                                lookup type — {@link IdentitySnapshotConfig} already exposes
     *                                {@link IdentitySnapshotConfig#carriageRequirementFor(String)} and
     *                                is already the module's Dagger-provided singleton, so a separate
     *                                registry type would only add indirection.
     */
    public IdentitySnapshotReconstructionInitializer(
            ContextHolder holder,
            IdentityReconstruction reconstruction,
            Optional<ServiceIdentityResolver> serviceIdentityResolver,
            IdentitySnapshotConfig config) {
        this.holder = Objects.requireNonNull(holder, "holder");
        this.reconstruction = Objects.requireNonNull(reconstruction, "reconstruction");
        this.serviceIdentityResolver = Objects.requireNonNull(serviceIdentityResolver, "serviceIdentityResolver");
        this.config = Objects.requireNonNull(config, "config");
    }

    @Override
    public ContextHolder.Scope initialize(InboundContextInitializationContext context) {
        Objects.requireNonNull(context, "context");

        // Case 1: a live SecurityContext already bound wins — no-op, idempotent.
        if (holder.current(SecurityContext.class).isPresent()) {
            return ContextScopes.noop();
        }

        Optional<DeferredExecutionOrigin> origin = holder.current(DeferredExecutionOrigin.class);
        Optional<IdentitySnapshotContext> snapshotContext = holder.current(IdentitySnapshotContext.class);
        CarriageRequirement requirement = config.carriageRequirementFor(resolveTargetKind(origin, context));

        if (snapshotContext.isEmpty()) {
            // Case 5: no snapshot AND no deferred-execution provenance. A REQUIRED target-kind is a
            // degradation (its expected carriage is absent) even with no origin evidence — the
            // EXPECTED_ABSENT marker is bound alone, no SecurityContext, since no origin proves the
            // dispatch is deferred execution. OPTIONAL/FORBIDDEN keep the plain fail-closed no-op.
            if (origin.isEmpty()) {
                if (requirement == CarriageRequirement.REQUIRED) {
                    return CompositeContextScope.of(bindExpectedAbsentMarker());
                }
                return ContextScopes.noop();
            }
            // Case 4: no snapshot but proven deferred execution — mint the resolved service context.
            // A REQUIRED target-kind additionally binds the EXPECTED_ABSENT marker alongside the mint
            // so the async degradation gate still fires, even though a context is minted.
            SecurityIdentity serviceIdentity = resolveService(origin, context);
            ContextHolder.Scope bindScope = holder.bind(SecurityContext.class, mintServiceContext(serviceIdentity));
            if (requirement == CarriageRequirement.REQUIRED) {
                return CompositeContextScope.of(bindScope, bindExpectedAbsentMarker());
            }
            return CompositeContextScope.of(bindScope);
        }

        IdentitySnapshotContext bound = snapshotContext.orElseThrow();

        // Case 2f (FORBIDDEN refusal, F4 security review): a FORBIDDEN target-kind never mints an
        // identity from a carried snapshot, verified or not — this runs BEFORE any
        // verification/reconstruction attempt so a present snapshot on a FORBIDDEN target cannot slip
        // through case 2's "verified snapshot always reconstructs" rule. Only the degradation marker
        // is bound, no SecurityContext, so the async degradation gate still fires.
        if (requirement == CarriageRequirement.FORBIDDEN) {
            return degradeWithoutContext(SnapshotDegradationReason.EXPECTED_ABSENT);
        }

        // Case 3a: the durable decoder already determined the carried snapshot was present but
        // unverifiable (bad HMAC / unknown key / decode / schema) — degrade without ever
        // reconstructing a subject. Per the origin-or-verified mint evidence rule (A9), a service
        // identity is resolved and a context minted ONLY when a DeferredExecutionOrigin proves the
        // dispatch is deferred execution; otherwise only the degradation marker is bound.
        Optional<SnapshotDegradationReason> unverifiableReason = bound.unverifiableReason();
        if (unverifiableReason.isPresent()) {
            if (origin.isEmpty()) {
                return degradeWithoutContext(unverifiableReason.orElseThrow());
            }
            SecurityIdentity serviceIdentity = resolveService(origin, context);
            return degrade(serviceIdentity, unverifiableReason.orElseThrow());
        }

        // Case 2: a verified snapshot always resolves an executing service identity and
        // reconstructs, regardless of origin presence — the HMAC-verified snapshot is itself trust
        // evidence, unlike the unverifiable case above.
        SecurityIdentity serviceIdentity = resolveService(origin, context);
        try {
            // Reconstruction re-verifies integrity per FR-ID-CA-008 as defense-in-depth. The
            // receive-side decoder already performed the F5 carrier check against the trusted
            // dispatch carrier before binding this verified context; the initializer has no
            // dispatch-carrier fact of its own here, so it passes the snapshot's own signed carrier as
            // the expected carrier (a self-match) — real dispatch-carrier propagation to this seam
            // arrives with the boundary-wiring commits.
            IdentitySnapshot verified = bound.snapshot().orElseThrow();
            DurableCarrierDescriptor expectedCarrier = new DurableCarrierDescriptor(
                    verified.carrier().carrierId(), verified.carrier().target());
            SecurityContext reconstructed =
                    reconstruction.deferredExecution(serviceIdentity, verified, expectedCarrier);
            ContextHolder.Scope bindScope = holder.bind(SecurityContext.class, reconstructed);
            return CompositeContextScope.of(bindScope);
        } catch (IdentityReconstructionException e) {
            // Case 3b (defense-in-depth): a snapshot that verified at decode but still fails
            // reconstruction re-verification degrades identically to case 3a — the same origin gate
            // applies, reusing the service identity already resolved above for the origin-present path.
            if (origin.isEmpty()) {
                return degradeWithoutContext(e.reason());
            }
            return degrade(serviceIdentity, e.reason());
        }
    }

    /**
     * Resolves the executing service identity for the dispatch. The origin-absent and resolver-result
     * cases are kept strictly separate so a misconfigured resolver cannot be conflated with the
     * no-origin fallback:
     *
     * <ul>
     *   <li>When no {@link DeferredExecutionOrigin} is present — a snapshot carried without an origin
     *       marker — this falls back to a system {@code scheduledJob} identity keyed on the boundary
     *       string.
     *   <li>When an origin <em>is</em> present it is resolved through the application resolver (or the
     *       built-in {@link #DEFAULT_RESOLVER} when none is bound), and the result is guarded against
     *       {@code null}: a resolver that returns {@code null} for a present origin is a
     *       misconfiguration that fails <em>closed</em> with a {@link NullPointerException} carrying a
     *       clear message rather than silently degrading to the boundary-default identity. The NPE
     *       propagates out of the dispatch via {@code installInitializers} so the misbehaving resolver
     *       surfaces loudly instead of quietly under-privileging the dispatch.
     *   <li>The resolved identity must be <em>actor-only</em>: a resolver returning a
     *       {@link SecurityIdentity} that carries a {@link SecurityIdentity#subject()},
     *       {@link SecurityIdentity#delegation()}, or {@link SecurityIdentity#client()} fails
     *       <em>closed</em> with an {@link IllegalArgumentException}. Deferred execution mints a
     *       service-only context (no subject, no request-client provenance) per the frozen
     *       binding-precedence rule (PRD-ID-002 §14.3), so this is the resolver-contract chokepoint
     *       that stops a subject/delegation/client-bearing identity from ever reaching
     *       {@link #mintServiceContext(SecurityIdentity)} — whose actor-type guard alone would let
     *       the whole identity (subject included) through into the minted context.
     * </ul>
     *
     * @param origin  the optional deferred-execution provenance
     * @param context the initialization context carrying the boundary identifier
     * @return the executing service {@link SecurityIdentity}; never {@code null}, always actor-only
     * @throws NullPointerException     if an application {@link ServiceIdentityResolver} returns
     *                                  {@code null} for a present origin
     * @throws IllegalArgumentException if an application {@link ServiceIdentityResolver} returns an
     *                                  identity carrying a subject, delegation, or client for a
     *                                  present origin
     */
    private SecurityIdentity resolveService(
            Optional<DeferredExecutionOrigin> origin, InboundContextInitializationContext context) {
        if (origin.isEmpty()) {
            // Snapshot present but no origin marker — fall back to a boundary-keyed system identity.
            return SystemIdentities.scheduledJob(context.boundary());
        }
        SecurityIdentity resolved =
                serviceIdentityResolver.orElse(DEFAULT_RESOLVER).resolve(origin.orElseThrow());
        Objects.requireNonNull(
                resolved, "ServiceIdentityResolver.resolve(DeferredExecutionOrigin) must not return null");
        if (resolved.subject().isPresent()
                || resolved.delegation().isPresent()
                || resolved.client().isPresent()) {
            throw new IllegalArgumentException("ServiceIdentityResolver must return an actor-only executing identity "
                    + "(no subject, delegation, or client) — deferred execution mints a service-only context");
        }
        return resolved;
    }

    /**
     * Resolves the durable-target kind used to look up the dispatch's {@link CarriageRequirement}
     * (§14.6 A9): the {@link DeferredExecutionOrigin#kind()} when an origin is present (a
     * durable-target kind string such as {@code "delayed-job"}, {@code "cron"}, or
     * {@code "outbox-relay"}), otherwise the {@link InboundContextInitializationContext#boundary()}
     * string — the best available target-kind evidence when no origin marker was bound.
     *
     * @param origin  the optional deferred-execution provenance
     * @param context the initialization context carrying the boundary identifier
     * @return the resolved target-kind string; never {@code null}
     */
    private String resolveTargetKind(
            Optional<DeferredExecutionOrigin> origin, InboundContextInitializationContext context) {
        return origin.map(DeferredExecutionOrigin::kind).orElseGet(context::boundary);
    }

    /**
     * Mints the fallback {@link SecurityContext} for the resolved executing identity, honoring the
     * actor's {@link PrincipalType}: a {@link PrincipalType#SYSTEM} actor uses
     * {@link SecurityContexts#system(SecurityIdentity)} (a {@code custom("system")} authentication
     * method), while a {@link PrincipalType#SERVICE} actor uses
     * {@link SecurityContexts#unauthenticated(SecurityIdentity)} (a {@code none()} authentication
     * method) — stamping {@code custom("system")} on a SERVICE actor would misattribute it as
     * system-acting (ADR-0163).
     *
     * @param serviceIdentity the resolved executing identity to carry as the context's actor
     * @return the minted {@link SecurityContext}
     * @throws IllegalArgumentException if the actor is neither {@link PrincipalType#SYSTEM} nor
     *                                  {@link PrincipalType#SERVICE}
     */
    private SecurityContext mintServiceContext(SecurityIdentity serviceIdentity) {
        PrincipalType actorType = serviceIdentity.actor().type();
        if (actorType == PrincipalType.SYSTEM) {
            return SecurityContexts.system(serviceIdentity);
        }
        if (actorType == PrincipalType.SERVICE) {
            return SecurityContexts.unauthenticated(serviceIdentity);
        }
        throw new IllegalArgumentException("resolved executing identity must be SYSTEM or SERVICE, got: " + actorType);
    }

    /**
     * Binds a service-only fallback context (no subject) plus a {@link SnapshotDegradationMarker}
     * carrying the given typed reason, so the async degradation gate can emit the non-droppable
     * degradation event and apply the configured {@code FAIL}/{@code CONTINUE_WITHOUT_IDENTITY} policy. An
     * unverifiable snapshot never yields a reconstructed user identity — this is the single
     * fail-closed exit for both degradation sources (case 3a decoder-detected, case 3b
     * reconstruction re-verification). The fallback context is minted via
     * {@link #mintServiceContext(SecurityIdentity)} so a SERVICE executing identity degrades via
     * {@link SecurityContexts#unauthenticated(SecurityIdentity)} rather than being misattributed as
     * system-acting.
     *
     * @param serviceIdentity the executing service identity to bind as the fallback context's actor
     * @param reason          the typed degradation reason to record on the marker
     * @return a composite scope binding the fallback context and the degradation marker
     */
    private ContextHolder.Scope degrade(SecurityIdentity serviceIdentity, SnapshotDegradationReason reason) {
        ContextHolder.Scope contextScope = holder.bind(SecurityContext.class, mintServiceContext(serviceIdentity));
        ContextHolder.Scope markerScope = holder.bind(
                SnapshotDegradationMarker.class, new SnapshotDegradationMarker(reason.name(), Optional.empty()));
        return CompositeContextScope.of(contextScope, markerScope);
    }

    /**
     * Binds only a {@link SnapshotDegradationMarker} carrying the given typed reason — no
     * {@link SecurityContext} is minted. This is the origin-absent counterpart of {@link #degrade}
     * for the unverifiable-snapshot paths (case 3a/3b): per the origin-or-verified mint evidence
     * rule (PRD-ID-002 §14.6 amendment A9), an unverifiable snapshot mints a service context only
     * when a {@link DeferredExecutionOrigin} proves the dispatch is deferred execution. Without that
     * evidence, minting a fallback context here would let an attacker who writes a garbage,
     * unverifiable durable row with no origin marker end up strictly better off under
     * {@code CONTINUE_WITHOUT_IDENTITY} than writing nothing at all (case 5's fail-closed no-op). Binding
     * the marker alone still lets the async degradation gate fire (a later service-dispatch
     * interceptor emits the non-droppable degradation event and applies the configured
     * {@code FAIL}/{@code CONTINUE_WITHOUT_IDENTITY} policy), and leaves the dispatch with no bound
     * {@link SecurityContext} so {@code ServiceAuthorizationInterceptor} denies
     * {@code AUTHENTICATION_REQUIRED} for {@code @RequiresAction} operations.
     *
     * @param reason the typed degradation reason to record on the marker
     * @return a scope binding only the degradation marker
     */
    private ContextHolder.Scope degradeWithoutContext(SnapshotDegradationReason reason) {
        ContextHolder.Scope markerScope = holder.bind(
                SnapshotDegradationMarker.class, new SnapshotDegradationMarker(reason.name(), Optional.empty()));
        return CompositeContextScope.of(markerScope);
    }

    /**
     * Binds a {@link SnapshotDegradationMarker} carrying
     * {@link SnapshotDegradationReason#EXPECTED_ABSENT} — the no-snapshot counterpart of
     * {@link #degrade}/{@link #degradeWithoutContext} for cases 4 and 5's carriage-required
     * detection (§14.6 A9): a {@link CarriageRequirement#REQUIRED} target-kind dispatched with no
     * {@link IdentitySnapshotContext} bound at all, not merely an unverifiable one. Binding the
     * marker alone (the caller composes it with a minted context scope on case 4, or returns it alone
     * on case 5) still lets the async degradation gate fire and apply the configured
     * {@code FAIL}/{@code CONTINUE_WITHOUT_IDENTITY} policy.
     *
     * @return a scope binding the {@code EXPECTED_ABSENT} degradation marker
     */
    private ContextHolder.Scope bindExpectedAbsentMarker() {
        return holder.bind(
                SnapshotDegradationMarker.class,
                new SnapshotDegradationMarker(SnapshotDegradationReason.EXPECTED_ABSENT.name(), Optional.empty()));
    }
}
