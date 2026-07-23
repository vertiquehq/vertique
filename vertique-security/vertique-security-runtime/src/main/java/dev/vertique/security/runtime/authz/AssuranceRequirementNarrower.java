// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.security.runtime.authz;

import dev.vertique.security.AuthenticationAssurance;
import dev.vertique.security.SecurityContext;
import dev.vertique.security.authz.ActionRef;
import dev.vertique.security.authz.AuthorizationDecision;
import dev.vertique.security.authz.AuthorizationNarrower;
import dev.vertique.security.authz.AuthorizationRequest;
import dev.vertique.security.authz.AuthzReasonCodes;
import dev.vertique.security.authz.RequirementDescriptor;
import io.vertx.core.Future;
import jakarta.inject.Inject;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * {@link AuthorizationNarrower} that denies an assurance-gated action with
 * {@link AuthzReasonCodes#STEP_UP_REQUIRED} when the context's authentication does not meet the
 * action's configured {@link AssuranceRequirement} (PRD identity-002 FR-ID-CA-005).
 *
 * <p><strong>Ungated actions pass through unchanged.</strong> {@link #narrow} parses
 * {@link AuthorizationRequest#action()} to an {@link ActionRef} and looks it up via
 * {@link AssuranceRequirementConfig#requirementFor(ActionRef)}, which matches against every
 * configured action <em>pattern</em> (FR-ID-AR-003) rather than an exact-string key; when no
 * configured pattern matches the action, the base decision is returned untouched — this narrower
 * only acts on actions an operator has explicitly assurance-gated.
 *
 * <p><strong>Denies are never touched.</strong> When a requirement is configured but the decision
 * so far is already a deny, {@link #narrow} returns it unchanged: this narrower only ever turns a
 * permit into a deny, never the reverse (the no-widen invariant {@code NarrowingAuthorizer}
 * enforces), and an existing deny already carries its own reason.
 *
 * <p><strong>The gate, for a gated action with a base PERMIT.</strong> Three independent conditions
 * each deny with {@link AuthzReasonCodes#STEP_UP_REQUIRED}, each stamped with a distinct
 * {@value #REASON_ATTRIBUTE} value in {@link AuthorizationDecision#safeAttributes()}:
 *
 * <ul>
 *   <li><strong>{@value #REASON_RECONSTRUCTED_NEEDS_STEP_UP}</strong> &mdash; the context is a
 *       framework verified reconstruction ({@link SecurityContext#reconstruction()} present). A
 *       reconstructed context carries no fresh, live authentication event of its own — it is
 *       rebuilt from a durable snapshot — so it structurally cannot satisfy a fresh-verification
 *       requirement, regardless of what assurance facts the frozen snapshot happens to carry. This
 *       check runs <strong>before</strong> the assurance checks below and short-circuits them.
 *   <li><strong>{@value #REASON_BELOW_MIN}</strong> &mdash; either {@link SecurityContext#authentication()}
 *       carries no {@link AuthenticationAssurance} at all, or its
 *       {@link AuthenticationAssurance#providerLevel()} (defaulting to {@code 0} when absent) is
 *       below the requirement's {@link AssuranceRequirement#minProviderLevel()}.
 *   <li><strong>{@value #REASON_DECAYED}</strong> &mdash; the assurance's
 *       {@link AuthenticationAssurance#authTime()} is absent, or is older than the requirement's
 *       {@link AssuranceRequirement#maxAge()} freshness window as measured against the narrower's
 *       injected {@link Clock} (constructor-injected so decay is deterministic under test, mirroring
 *       {@code InMemoryDelegationGrantValidator}).
 * </ul>
 *
 * <p>Every {@code STEP_UP_REQUIRED} deny this narrower produces also carries
 * {@value #MIN_LEVEL_ATTRIBUTE} and {@value #MAX_AGE_MS_ATTRIBUTE} — the unmet requirement, bounded
 * and audit-safe — so a caller (and audit output) can see exactly what step-up would satisfy the
 * gate. None of these attributes ever carry a token, credential, or other secret.
 *
 * <p><strong>Introspection is annotate-only, never a live pass/fail.</strong>
 * {@link #requirementFor} reports a {@link RequirementDescriptor} for every action
 * {@link AssuranceRequirementConfig} gates, regardless of whether the given {@code ctx} would
 * currently satisfy it — a low-assurance and a high-assurance context both see the identical
 * requirement annotation for the same action. Whether a specific context currently satisfies the
 * gate is answered only by {@link #narrow} at authorize-time.
 *
 * <p><strong>Ordering.</strong> Runs at {@link #priority()} {@value #PRIORITY} — after
 * {@link DelegationEnforcementNarrower} (priority 100, "delegation") within the default
 * {@code dev.vertique.core.extension.ExtensionPhase#APPLICATION} phase, so delegation-scope
 * enforcement composes before assurance-level narrowing.
 */
public final class AssuranceRequirementNarrower implements AuthorizationNarrower {

    /** Requirement kind reported for actions gated by a minimum-assurance requirement. */
    private static final String REQUIREMENT_KIND = "ASSURANCE";

    /**
     * {@link AuthorizationDecision#safeAttributes()} key carrying the unmet requirement's
     * {@link AssuranceRequirement#minProviderLevel()}. Package-private so
     * {@code AssuranceRequirementNarrowerTest} can assert on it directly.
     */
    static final String MIN_LEVEL_ATTRIBUTE = "assurance.required.minLevel";

    /**
     * {@link AuthorizationDecision#safeAttributes()} key carrying the unmet requirement's
     * {@link AssuranceRequirement#maxAge()} in milliseconds. Package-private so
     * {@code AssuranceRequirementNarrowerTest} can assert on it directly.
     */
    static final String MAX_AGE_MS_ATTRIBUTE = "assurance.required.maxAgeMs";

    /**
     * {@link AuthorizationDecision#safeAttributes()} key carrying which of {@value #REASON_BELOW_MIN},
     * {@value #REASON_DECAYED}, or {@value #REASON_RECONSTRUCTED_NEEDS_STEP_UP} caused a
     * {@link AuthzReasonCodes#STEP_UP_REQUIRED} deny. Package-private so
     * {@code AssuranceRequirementNarrowerTest} can assert on it directly.
     */
    static final String REASON_ATTRIBUTE = "assurance.reason";

    /** {@value #REASON_ATTRIBUTE} value: the caller's assurance level is missing or below the minimum. */
    static final String REASON_BELOW_MIN = "BELOW_MIN";

    /** {@value #REASON_ATTRIBUTE} value: the caller's assurance has decayed past the freshness window. */
    static final String REASON_DECAYED = "DECAYED";

    /**
     * {@value #REASON_ATTRIBUTE} value: the context is a verified reconstruction, which structurally
     * cannot satisfy a fresh-verification requirement.
     */
    static final String REASON_RECONSTRUCTED_NEEDS_STEP_UP = "RECONSTRUCTED_NEEDS_STEP_UP";

    /**
     * Fixed {@link #priority()} for this narrower ("assurance"): runs after
     * {@link DelegationEnforcementNarrower} (priority 100) within the default
     * {@code dev.vertique.core.extension.ExtensionPhase#APPLICATION} phase.
     */
    private static final int PRIORITY = 200;

    private final AssuranceRequirementConfig config;
    private final Clock clock;

    /**
     * Creates the narrower over the given per-action requirement config and clock.
     *
     * @param config the assurance-requirement config this narrower gates against; must not be
     *               {@code null}
     * @param clock  the {@link AssuranceClock}-qualified clock supplying "now" for freshness-window
     *               decay checks, injected so decay evaluation is deterministic under test; must not
     *               be {@code null}
     */
    @Inject
    public AssuranceRequirementNarrower(AssuranceRequirementConfig config, @AssuranceClock Clock clock) {
        this.config = Objects.requireNonNull(config, "config");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public int priority() {
        return PRIORITY;
    }

    @Override
    public Future<AuthorizationDecision> narrow(AuthorizationRequest request, AuthorizationDecision base) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(base, "base");
        if (!base.permitted()) {
            // The base decision is already a deny: annotate nothing, never widen — the existing deny
            // already carries its own reason.
            return Future.succeededFuture(base);
        }
        ActionRef actionRef;
        try {
            actionRef = ActionRef.parse(request.action());
        } catch (RuntimeException e) {
            // Fail closed: reaching a PERMIT already required DefaultAuthorizer to parse this same
            // action string successfully (an unparseable action denies ACTION_NOT_REGISTERED there),
            // so a parse failure here is a framework-integrity anomaly, not a normal case — mirrors
            // DelegationEnforcementNarrower's fail-closed handling of the same anomaly.
            return Future.succeededFuture(AuthorizationDecision.deny(AuthzReasonCodes.INTERNAL_AUTHZ_ERROR));
        }
        Optional<AssuranceRequirement> requirementOpt = config.requirementFor(actionRef);
        if (requirementOpt.isEmpty()) {
            // No configured pattern matches this action: annotate nothing.
            return Future.succeededFuture(base);
        }
        AssuranceRequirement requirement = requirementOpt.get();
        SecurityContext ctx = request.securityContext();
        if (ctx.reconstruction().isPresent()) {
            // A reconstructed context carries no fresh, live authentication event of its own — it
            // structurally cannot satisfy a fresh-verification requirement (FR-ID-CA-005).
            return Future.succeededFuture(stepUpDenied(requirement, REASON_RECONSTRUCTED_NEEDS_STEP_UP));
        }
        Optional<AuthenticationAssurance> assuranceOpt = ctx.authentication().assurance();
        if (assuranceOpt.isEmpty()) {
            return Future.succeededFuture(stepUpDenied(requirement, REASON_BELOW_MIN));
        }
        AuthenticationAssurance assurance = assuranceOpt.get();
        if (assurance.providerLevel().orElse(0) < requirement.minProviderLevel()) {
            return Future.succeededFuture(stepUpDenied(requirement, REASON_BELOW_MIN));
        }
        Optional<Instant> authTimeOpt = assurance.authTime();
        if (authTimeOpt.isEmpty()
                || authTimeOpt.get().plus(requirement.maxAge()).isBefore(Instant.now(clock))) {
            return Future.succeededFuture(stepUpDenied(requirement, REASON_DECAYED));
        }
        return Future.succeededFuture(base);
    }

    @Override
    public Optional<RequirementDescriptor> requirementFor(SecurityContext ctx, ActionRef action) {
        Objects.requireNonNull(ctx, "ctx");
        Objects.requireNonNull(action, "action");
        // Annotate-only: reports the requirement regardless of whether ctx currently satisfies it —
        // never a live pass/fail (see class javadoc).
        return config.requirementFor(action).map(req -> new RequirementDescriptor(REQUIREMENT_KIND, req.describe()));
    }

    /**
     * Builds the {@link AuthzReasonCodes#STEP_UP_REQUIRED} deny for an unmet requirement, stamped
     * with the bounded, audit-safe requirement and reason attributes described in the class javadoc.
     *
     * @param requirement the unmet requirement
     * @param reason      one of {@value #REASON_BELOW_MIN}, {@value #REASON_DECAYED}, or
     *                    {@value #REASON_RECONSTRUCTED_NEEDS_STEP_UP}
     * @return the step-up-required deny decision
     */
    private static AuthorizationDecision stepUpDenied(AssuranceRequirement requirement, String reason) {
        Map<String, Object> attributes = new HashMap<>();
        attributes.put(MIN_LEVEL_ATTRIBUTE, requirement.minProviderLevel());
        attributes.put(MAX_AGE_MS_ATTRIBUTE, requirement.maxAge().toMillis());
        attributes.put(REASON_ATTRIBUTE, reason);
        return new AuthorizationDecision(
                false, AuthzReasonCodes.STEP_UP_REQUIRED, Optional.empty(), Optional.empty(), attributes);
    }
}
