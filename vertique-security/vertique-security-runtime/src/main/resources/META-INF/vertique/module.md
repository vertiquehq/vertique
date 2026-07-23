<!--
SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
SPDX-License-Identifier: EUPL-1.2
-->

# Security Runtime Module

> **Status:** Alpha
> **Package:** `dev.vertique.security.runtime` (identity-snapshot durable carriage + reconstruction), `dev.vertique.security.runtime.authz`, `dev.vertique.security.runtime.events`
> **Artifact:** `vertique-security-runtime`
> **Depends on:** security-core, core

Default in-process authorization engine, event fan-out wiring, and identity-snapshot durable-carriage/reconstruction pipeline for the Vertique security model. This module provides `DefaultAuthorizer`, `DefaultAuthorizationIntrospector`, `DefaultActionRegistry`, `SecurityEventEmitter`, `DefaultIdentitySnapshotFactory`, `DefaultIdentityReconstruction`, and the Dagger modules (`SecurityAuthzModule`, `SecurityEventsModule`, `IdentitySnapshotCarriageModule`, `IdentitySnapshotReconstructionModule`, `PrivilegedIdentityModule`) that wire them together. It carries no dependency on any transport module, configuration module, or Vert.x Web.

It also provides the opt-in authorization-narrowing composition (`NarrowingAuthorizer`, `NarrowingIntrospector`, `DelegationEnforcementNarrower`, `AssuranceRequirementNarrower`) and the reconstructed-context authority modes (`ReconstructedAuthorityResolvingAuthorizer` for Mode 2, `DefaultCapturedAuthorityReconstruction`/`CapturedAuthorityActivation` for Mode 3) — each behind its own opt-in Dagger module, degrading to a byte-identical passthrough of the Phase-1 base engine when not installed.

The API/SPI contracts this module implements live in `dev.vertique:vertique-security-core`. Config-backed policy and role-resolver contributions live in `dev.vertique:vertique-security-config`.

---

## When To Use It

Include `vertique-security-runtime` in any application that uses the authorization engine or needs to emit/observe security lifecycle events. Include both `SecurityAuthzModule` and `SecurityEventsModule` in the Dagger component:

```java
@Component(modules = {
    VertxModule.class,
    SecurityAuthzModule.class,   // engine: ActionRegistry, Authorizer, AuthorizationIntrospector
    SecurityEventsModule.class,  // fan-out: SecurityEventEmitter, Set<SecurityEventObserver>
    AuthzConfigModule.class,     // optional: config-backed PolicyDefinitionSource + RolePolicyResolver
})
interface AppComponent {
    Authorizer authorizer();
    AuthorizationIntrospector introspector();
    SecurityEventEmitter eventEmitter();
}
```

Application subsystems contribute `ActionContributor`, `PolicyDefinitionSource`, and `RolePolicyResolver` entries via `@IntoSet` into the sets declared by `SecurityAuthzModule`.

---

## Core Concepts

### Startup Ordering (FR-008)

`SecurityAuthzModule` guarantees the following construction order through the Dagger dependency graph:

1. **`ActionRegistry`** is built first from all contributed `ActionContributor`s. Duplicate actions (same canonical value from two contributors) fail fast with an `IllegalStateException` naming both sources.
2. **`AuthzResolution`** is built next. This is the single place the policy catalogue is merged and validated:
   - Every contributed `PolicyDefinitionSource` is validated against the registry polymorphically via `PolicyDefinitionSource.validateAgainst(ActionRegistry)` — no `instanceof` on concrete subtypes.
   - Duplicate policy names across sources fail fast, naming both offenders (FR-018).
   - All contributed `RolePolicyResolver`s are merged into a single composite resolver (union of results).
3. **`Authorizer`** and **`AuthorizationIntrospector`** are built last from the same shared `AuthzResolution` singleton.

Because both the authorizer and the introspector consume the same `AuthzResolution`, their verdicts are guaranteed to agree by construction — the agreement invariant documented on `AuthorizationIntrospector` is a structural guarantee, not a behavioral promise.

### Fail-Closed Design

`DefaultAuthorizer` wraps the entire role→policy→action resolution in a try/catch. Any `RuntimeException` from a misbehaving resolver or policy source produces a deny decision with `AuthzReasonCodes.INTERNAL_AUTHZ_ERROR` rather than propagating. The engine never throws to the caller and never returns a failed `Future`.

### Event Fan-Out Isolation

`SecurityEventEmitter` fans out to all registered `SecurityEventObserver`s via `Future.join(...)` — it waits for all futures to complete regardless of individual outcomes. Synchronous throws from observer methods and asynchronous `Future` failures are both caught, logged at WARN, and recovered. One misbehaving observer cannot prevent others from receiving the event.

### Narrowing Composition & Reconstructed-Authority Modes

`SecurityAuthzModule` decorates the base engine in two independent, opt-in layers, outermost first:

```
ReconstructedAuthorityResolvingAuthorizer   (Mode 2, present only when a PrincipalAuthorityResolver is bound)
  → NarrowingAuthorizer                     (folds Set<AuthorizationNarrower>, always present)
    → DefaultAuthorizer                     (base role/policy engine)
```

With no narrowers installed and no `PrincipalAuthorityResolver` bound, the exposed `Authorizer`/`AuthorizationIntrospector` are byte-identical to the Phase-1 base engine — every opt-in layer is a pure decorator that degrades to a no-op passthrough when absent.

`NarrowingAuthorizer`/`NarrowingIntrospector` fold the contributed `Set<AuthorizationNarrower>` — sorted and validated once by the shared package-private `AuthorizationNarrowerOrdering` helper (`OrderedExtension#comparator()` order; a duplicate `(priority, orderKey)` pair fails startup naming both classes) — over the base decision:

- **No-widen guard.** A narrower's candidate decision that turns a deny into a permit is discarded; the current deny is kept and a framework-integrity error is logged naming the offending narrower.
- **Empty-set byte-identical.** With zero contributed narrowers, `NarrowingAuthorizer`/`NarrowingIntrospector` pass every request straight through to the base engine unchanged.
- **Agreement invariant preserved.** `NarrowingIntrospector.capabilities(ctx)` folds `requirementFor` across the same ordered narrower set and collects **every** non-empty requirement it finds — not merely the first, since two independent narrowers (e.g. a delegation-scope narrower and an assurance-level narrower) may each gate the same action for unrelated reasons — into `ActionCapability#requirements()` (a `Set<RequirementDescriptor>`). Membership never changes, so `allowedActions(ctx) == capabilities(ctx).stream().map(ActionCapability::action)` continues to hold.
- **Annotate-only introspection.** A narrower's `requirementFor` is never a live pass/fail — it reports the same requirement for every actor regardless of whether that actor's concrete request would currently satisfy it; only `narrow` (at authorize-time) evaluates a specific request.
- **Reconstructed contexts are rejected outright.** `NarrowingIntrospector` — the framework's exposed `AuthorizationIntrospector` binding — throws `ReconstructedContextIntrospectionUnsupportedException` for both `allowedActions` and `capabilities` when `ctx.reconstruction()` is present, *before* consulting the base introspector or any narrower: a reconstructed context's current authority is resolved live at authorize-time (Mode 2), so a synchronous introspection answer computed from its currently-held claims could silently disagree with what `authorize()` would decide for the same actor.

`ReconstructedAuthorityResolvingAuthorizer` is the outermost decorator, present only when an application binds a `PrincipalAuthorityResolver` (the Mode-2 opt-in). It triggers strictly off `SecurityContext.reconstruction()` — never the descriptive `identity.reconstructed=true` attribute — and never consults the resolver for a non-reconstructed or Mode-3 `CAPTURED` context. See its Key Classes entry below for the full per-mode behavior.

---

## Key Classes

### SecurityAuthzModule

Abstract Dagger `@Module` (`dev.vertique.security.runtime.authz`). Declares four empty-by-default `@Multibinds` sets plus an optional `PrincipalAuthorityResolver` binding, and provides the entire engine stack.

**Multibinding declarations:**

| Set type | Populated by |
|----------|-------------|
| `Set<ActionContributor>` | Subsystem modules via `@Provides @IntoSet ActionContributor` |
| `Set<PolicyDefinitionSource>` | `AuthzConfigModule` and application modules via `@Provides @IntoSet PolicyDefinitionSource` |
| `Set<RolePolicyResolver>` | `AuthzConfigModule` and application modules via `@Provides @IntoSet RolePolicyResolver`; always contains at least one entry (the default empty-mapping resolver) |
| `Set<AuthorizationNarrower>` | Opt-in narrower modules (e.g. `DelegationEnforcementModule`, `AssuranceRequirementModule`) via `@Provides @IntoSet AuthorizationNarrower`; may be empty |

**Optional binding:**

| Binding | Populated by |
|---------|-------------|
| `Optional<PrincipalAuthorityResolver>` (`@BindsOptionalOf`, no default) | An application `@Provides PrincipalAuthorityResolver` binding — presence **is** the Mode-2 opt-in (see Core Concepts above) |
| `Optional<PrincipalAuthorityResolutionConfig>` (`@BindsOptionalOf`, defaults to `PrincipalAuthorityResolutionConfig#defaults()`) | `PrincipalAuthorityResolutionConfigModule` config-drives it from `identity.authz.resolutionTimeoutMs`; unused unless a `PrincipalAuthorityResolver` is also bound |

**Provided bindings:**

| Binding | Scope | Built from |
|---------|-------|-----------|
| `ActionRegistry` | `@Singleton` | All `ActionContributor`s |
| `AuthzResolution` | `@Singleton` | `ActionRegistry` + all `PolicyDefinitionSource`s + all `RolePolicyResolver`s |
| `Authorizer` | `@Singleton` | `DefaultAuthorizer`, wrapped by `NarrowingAuthorizer` over `Set<AuthorizationNarrower>`; only when `Optional<PrincipalAuthorityResolver>` is present, that resolver is first wrapped in `TimeoutPrincipalAuthorityResolver` (bounded by `PrincipalAuthorityResolutionConfig#resolutionTimeoutMs()`) and the whole chain further wrapped by `ReconstructedAuthorityResolvingAuthorizer` |
| `AuthorizationIntrospector` | `@Singleton` | `DefaultAuthorizationIntrospector`, wrapped by `NarrowingIntrospector` over the same `Set<AuthorizationNarrower>` |

The module also contributes `BuiltinAuthzActionContributor` `@IntoSet`, reserving `authz.action.list` and `authz.action.introspect` in every application.

---

### SecurityEventsModule

Abstract Dagger `@Module` (`dev.vertique.security.runtime.events`). Declares the single `@Multibinds Set<SecurityEventObserver>` for the framework. This is the one canonical declaration — surfaces (REST, WebSocket, services) and integrations (audit, OpenTelemetry, Micrometer) contribute observers via `@Provides @IntoSet SecurityEventObserver` without re-declaring the set.

`SecurityEventEmitter` is a `@Singleton` with an `@Inject` constructor — any component that includes `SecurityEventsModule` can inject it directly without an explicit `@Provides` for it.

---

### AuthzResolution

Internal `@Singleton` class (`dev.vertique.security.runtime.authz`) that is the shared core of the default authorization engine. Holds the materialised policy catalogue (immutable `Map<String, PolicyDefinition>` indexed by name) and the merged `RolePolicyResolver`.

Exposes a single package-private `decide(SecurityContext, ActionRef)` method that performs the five-step evaluation:

1. No `ROLE` claim → deny `ROLE_MISSING`
2. Roles map to no policy name → deny `ROLE_POLICY_MISSING`
3. Mapped policy name absent from catalogue → deny `POLICY_NOT_FOUND`
4. Some `ALLOW` statement matches the action → permit `PERMITTED`
5. No match → deny `ACTION_NOT_ALLOWED`

Any `RuntimeException` from the resolver or catalogue lookup fails closed to `INTERNAL_AUTHZ_ERROR`.

`AuthzResolution` is consumed by both `DefaultAuthorizer` and `DefaultAuthorizationIntrospector`. This shared instance is the mechanism that guarantees agreement: the authorizer wraps `decide` with an action-registration check; the introspector calls `decide` once per registered action. Both surfaces see the same merged catalogue and the same resolver.

#### Invariants & Gotchas

- `AuthzResolution` assumes duplicate policy names have already been rejected upstream by `SecurityAuthzModule.mergedSource(...)`. If a same-name pair somehow reaches it, last-wins (no secondary guard).
- The decision runs entirely synchronously and in-memory. Custom `Authorizer` implementations that consult async backends must still report failures as a fail-closed deny, not as a failed `Future`.

---

### DefaultAuthorizer

`@Singleton` implementation of `Authorizer`. Wraps `AuthzResolution.decide(...)` with the action-registration check:

1. If the action is not in the `ActionRegistry` → deny `ACTION_NOT_REGISTERED`.
2. Otherwise delegate to `AuthzResolution.decide(...)`.
3. Fail-closed on null `ctx` or unexpected error.

The `authorize(SecurityContext, ActionRef, ResourceRef)` convenience overload builds an `AuthorizationRequest` with an empty context map and delegates to `authorize(AuthorizationRequest)`.

---

### DefaultAuthorizationIntrospector

`@Singleton` implementation of `AuthorizationIntrospector`. Iterates all registered actions from the `ActionRegistry`, calling `AuthzResolution.decide(ctx, action)` for each, and returns the set of permitted actions. Built on the same `AuthzResolution` singleton as `DefaultAuthorizer` — so for the same engine state and actor, `allowedActions` and `authorize` agree by construction.

---

### DefaultActionRegistry

`@Singleton` implementation of `ActionRegistry`. Built at startup from all contributed `ActionContributor`s. Fails fast on duplicate actions (keyed on `ActionRef.value()`) with an `IllegalStateException` naming both contributing sources. Stores actions in an immutable map for O(1) `contains` and `find` lookups.

---

### BuiltinAuthzActionContributor

`@Singleton` `ActionContributor` bound `@IntoSet` by `SecurityAuthzModule`. Reserves two framework actions:

| Action | Purpose |
|--------|---------|
| `authz.action.list` | Authorizes access to the action-registry introspection endpoint |
| `authz.action.introspect` | Authorizes access to the per-subject allowed-action introspection endpoint |

The `authz` subsystem prefix is reserved for framework use. Application subsystems must use a different leading segment.

---

### InMemoryPolicyDefinitionSource

Programmatic `PolicyDefinitionSource` backed by a `List<PolicyDefinition>` supplied at construction. For applications that define policies in code rather than config.

---

### InMemoryRolePolicyResolver

Programmatic `RolePolicyResolver` backed by a `Map<String, List<String>>` (role → policy names) supplied at construction. Used as the default empty-mapping resolver contributed `@IntoSet` by `SecurityAuthzModule`, and available for programmatic use in applications.

---

### NarrowingAuthorizer, NarrowingIntrospector

`Authorizer`/`AuthorizationIntrospector` decorators (`dev.vertique.security.runtime.authz`) that fold an ordered `Set<AuthorizationNarrower>` over the base engine's decision. Wired unconditionally by `SecurityAuthzModule` — with an empty narrower set both are behavior-identical to the base engine.

```java
public final class NarrowingAuthorizer implements Authorizer {
    public NarrowingAuthorizer(Authorizer base, Set<AuthorizationNarrower> narrowers);
}

public final class NarrowingIntrospector implements AuthorizationIntrospector {
    public NarrowingIntrospector(AuthorizationIntrospector base, Set<AuthorizationNarrower> narrowers);
}
```

Both constructors sort and validate the narrower set once via the shared package-private `AuthorizationNarrowerOrdering` helper — `OrderedExtension#comparator()` order (phase, then ascending `priority()`, then `orderKey()`); a duplicate `(priority, orderKey)` pair throws `IllegalStateException` naming both conflicting narrower classes at construction (i.e. at Dagger graph build / application startup).

`NarrowingAuthorizer.authorize(request)` composes the base decision through each narrower in order (async `compose`), applying the no-widen guard after every step: a candidate that turns a deny into a permit is discarded (the current deny is kept) and a framework-integrity error is logged naming the offending narrower. `NarrowingIntrospector.capabilities(ctx)` delegates `allowedActions` straight to the base introspector unchanged, then for each allowed action folds `requirementFor` across the ordered narrowers and attaches the **first** non-empty requirement — preserving `allowedActions(ctx) == capabilities(ctx).stream().map(ActionCapability::action)` by construction.

---

### DelegationEnforcementNarrower, DelegationEnforcementModule

Framework-shipped `AuthorizationNarrower` (priority 100, `"delegation"`) that bounds a delegated evaluation to the intersection of the actor's base authority and the delegation grant's scope.

**Non-delegated contexts pass through unchanged** — both `SecurityIdentity.subject()` and `SecurityIdentity.delegation()` must be present for this narrower to act.

**Framework-scheduled deferred work is not grant-backed delegation.** A `DelegationContext` whose `kind()` is `DelegationContext#DEFERRED_EXECUTION_KIND` (the marker `IdentityReconstruction#deferredExecution` mints) records a framework-mediated scheduling relationship, not a grant a principal requested — its authority is the executing service's own, already resolved live by Mode 2. `narrow` recognizes this `kind` and passes `base` through **unchanged, without ever consulting `DelegationGrantValidator`**: validating the synthetic scheduling id as a grant would deny every reconstructed deferred action, which FR-ID-DG-006 explicitly excludes from grant-scope intersection. `requirementFor` reports `Optional.empty()` for the same contexts, keeping the narrow-deny/requirement-present Agreement invariant consistent.

For a genuinely grant-backed delegated context:

| Base decision | Grant lookup result | Narrowed outcome |
|---|---|---|
| PERMIT | Grant permits (scope + direction match, not expired) | Permit survives |
| PERMIT | Grant denies (not found, expired, out of scope) | Denied, with the grant's own `DelegationReasonCodes` reason surfaced as the decision's `reasonCode` |
| DENY | (not looked up) | Base deny survives unchanged — this narrower never adds authority |

**Scope mapping (frozen):** `scopeKind` is the requested action's `"<subsystem>.<resource>"` segments (parsed via `ActionRef.parse`); `scopeRef` is the request's `ResourceRef#id()`. An unparseable action string fails closed to `AuthzReasonCodes#INTERNAL_AUTHZ_ERROR`.

**Audit visibility:** every decision for a delegated context carries `delegation.subject` (`"<PrincipalType>:<id>"`) and `delegation.authorityId` (the evaluated grant id) in `AuthorizationDecision#safeAttributes()` — never the subject's non-authoritative `attributes()`.

`requirementFor` is annotate-only and synchronous: it never calls `DelegationGrantValidator.validate`, reporting every action as gated by the active grant for a delegated actor without a concrete resource to evaluate against.

`DelegationEnforcementModule` is the opt-in Dagger module that contributes `DelegationEnforcementNarrower` `@IntoSet AuthorizationNarrower`. **No default `DelegationGrantValidator` binding is supplied** — the installing application must also bind one (e.g. `InMemoryDelegationGrantValidator`, or a store-backed implementation), or the Dagger graph fails to compile; a default in-memory validator with no grants would silently deny every delegated call rather than surfacing a missing grant store as a startup error.

```java
@Provides @Singleton
static DelegationGrantValidator delegationGrantValidator() {
    return new InMemoryDelegationGrantValidator(myGrants);
}
```

---

### InMemoryDelegationGrantValidator

Framework-shipped, in-memory `DelegationGrantValidator` reference implementation (`dev.vertique.security.runtime`). Holds a fixed, immutable set of `DelegationGrant`s supplied at construction — the same dependency-clean-default shape as `InMemoryPolicyDefinitionSource`/`InMemoryRolePolicyResolver` — and ships the expiry and scope checks the framework default requires.

```java
public InMemoryDelegationGrantValidator(Collection<DelegationGrant> grants);
public InMemoryDelegationGrantValidator(Collection<DelegationGrant> grants, Clock clock); // deterministic under test
```

`validate(actor, subject, scopeKind, scopeRef, grantId)` maps `actor` to the grant's `grantee()` and `subject` to the grant's `grantor()` — mirroring `IdentityReconstruction.deferredExecution`'s actor/subject direction. Principal matching compares only `(type, id)`, never `attributes` (FR-ID-CA-012). Any exception during evaluation — malformed input, unexpected internal failure, backing-store lookup failure — is caught and mapped to `DelegationReasonCodes#GRANT_LOOKUP_FAILED`; `validate` never returns a failed `Future`.

---

### AssuranceRequirementNarrower, AssuranceRequirement, AssuranceRequirementConfig, AssuranceRequirementModule

Framework-shipped `AuthorizationNarrower` (priority 200, runs after `DelegationEnforcementNarrower`) that denies an assurance-gated action with `AuthzReasonCodes#STEP_UP_REQUIRED` when the context's authentication does not meet the action's configured minimum-assurance requirement.

```java
public record AssuranceRequirement(int minProviderLevel, Duration maxAge)

public record AssuranceRequirementConfig(Map<ActionPattern, AssuranceRequirement> patterns)
```

`AssuranceRequirementConfig#patterns()` maps a parsed `ActionPattern` — the same action-pattern grammar `PolicyStatement` authors policies with: exact (`"cms.content.delete"`) or trailing-suffix wildcard (`"payments.refund.*"`, `"payments.*"`) — to the `AssuranceRequirement` gating a matching action, via `requirementFor(ActionRef)` (FR-ID-AR-003; not an exact-string lookup). An action matching no configured pattern is not assurance-gated at all. **Overlapping patterns combine strictest-wins**: when more than one configured pattern matches the same action, the effective requirement takes the higher `minProviderLevel` and the shorter `maxAge` of every match (`AssuranceRequirement#strictest`) — a wildcard can never weaken a more-specific sibling rule, or vice versa. A blank or malformed pattern key fails config parsing fast (`ConfigurationException`), never silently ungating a typo'd pattern. Config path `identity.assurance`:

```yaml
identity:
  assurance:
    actions:
      "cms.content.delete":
        minProviderLevel: 2
        maxAgeMs: 300000
      "payments.refund.*":
        minProviderLevel: 3
        maxAgeMs: 60000
```

For a gated action with a base PERMIT, `AssuranceRequirementNarrower.narrow` denies with `STEP_UP_REQUIRED` on any of three independent conditions, each stamped with a distinct `assurance.reason` value in `AuthorizationDecision#safeAttributes()` (alongside `assurance.required.minLevel` and `assurance.required.maxAgeMs`, echoing the unmet requirement):

| Condition | `assurance.reason` |
|---|---|
| The context is a framework verified reconstruction (`SecurityContext.reconstruction()` present) — checked **first**, short-circuiting the checks below, since a reconstructed context carries no fresh, live authentication event of its own | `RECONSTRUCTED_NEEDS_STEP_UP` |
| No `AuthenticationAssurance` present, or its `providerLevel()` (default `0`) is below `minProviderLevel` | `BELOW_MIN` |
| `authTime()` absent, or older than `maxAge` measured against the narrower's injected `@AssuranceClock Clock` | `DECAYED` |

`requirementFor` is annotate-only: it reports the configured requirement for every gated action regardless of whether the given context would currently satisfy it — only `narrow` (at authorize-time) is a live pass/fail. For a reconstructed context this method is never actually reached through introspection at all: `NarrowingIntrospector` throws `ReconstructedContextIntrospectionUnsupportedException` for any context whose `reconstruction()` is present, before consulting any narrower — a caller needing the `RECONSTRUCTED_NEEDS_STEP_UP` answer for such a context calls `Authorizer#authorize(...)` instead.

`AssuranceRequirementModule` is the opt-in Dagger module: it provides the parsed `AssuranceRequirementConfig`, contributes `AssuranceRequirementNarrower` `@IntoSet AuthorizationNarrower`, and provides the `@AssuranceClock`-qualified `Clock` (`Clock.systemUTC()`) the narrower consumes — qualified so it coexists with any other `Clock` binding in the same component without a duplicate-binding error. With no configured `identity.assurance.actions` entries, the installed narrower is a no-op passthrough.

---

### SecurityEventEmitter

`@Singleton` fan-out emitter (`dev.vertique.security.runtime.events`). Four typed overloads — one per event type — each fan out to all registered `SecurityEventObserver`s in parallel using `Future.join(...)`.

```java
// Inject and use in an enforcement layer (PEP)
@Inject SecurityEventEmitter emitter;

emitter.emit(new AuthorizationDecisionEvent(
    Instant.now(), correlationCtx, originOpt, request, decision));
```

The returned `Future<Void>` always succeeds — per-observer failures are caught and logged. The caller does not need to compose on it to protect the auth flow.

**Observer invocation ordering is not guaranteed.** Observers must be idempotent and self-ordered if ordering within a downstream pipeline matters.

---

### DefaultIdentitySnapshotFactory

Default `IdentitySnapshotFactory` implementation (`dev.vertique.security.runtime`) providing credential-free identity snapshot capture outside any REST middleware.

`capture(SecurityContext live)` returns an `IdentitySnapshotContent` — **content only**, with no carrier binding, no temporal bounds, and no integrity envelope, since capture cannot legitimately produce any of those. `IdentitySnapshotDurableEncoder` assembles the surrounding `IdentitySnapshot` envelope (carrier + `issuedAt`/`expiresAt`) around this content, and `IdentitySnapshotCodec` is the single HMAC signer — it computes the authoritative tag when the envelope is encoded. `IdentitySnapshotCodec.SUPPORTED_SCHEMA_VERSION` (`2`) is stamped onto the envelope by the encoder, not by this factory.

`authenticatedAt` derivation precedence: the assurance `authTime` when present; else the first (decisive) `AuthenticationEvidence`'s `verifiedAt`; else — for a reconstructed live context — the `identity.reconstructed.authenticatedAt` marker attribute (so a chained re-capture preserves the original authentication instant); else the capture instant (synthetic system/unauthenticated/anonymous contexts only).

Capture is **credential-free by enforcement** (PRD identity-002 A7, FR-ID-CA-003/NFR-ID2-003), and the projection is **dimension-aware**: subject, client, and claim `attributes` maps are projected to empty unconditionally; the actor map retains only `system.reason`, and only when the actor is `SYSTEM`-typed and the value is a bounded (≤ 256 chars) `String`. The bound *limits* — it does not *prevent* — what trusted in-process code can place under that key (a hand-assembled `SYSTEM`-typed actor via `SecurityContexts.assemble(...)` can still carry a ≤ 256-char reason; `SystemIdentities` is the sanctioned source). Attribute maps are application-controlled and the snapshot persists to an unencrypted durable store, so nothing else survives capture; structural fields (`type`, `id`, claim components, `DelegationSummary`'s kind + authorityId) are unaffected. Durable principal identity is the trust-domain-unique `(PrincipalType, id)` tuple, never attributes (FR-ID-CA-012).

---

### DefaultIdentityReconstruction

Default `IdentityReconstruction` implementation (`dev.vertique.security.runtime`), provided only by `PrivilegedIdentityModule`.

```java
public final class DefaultIdentityReconstruction implements IdentityReconstruction {
    public DefaultIdentityReconstruction(IdentitySnapshotCodec codec);
    // resumeAsPrincipal(snapshot, expectedCarrier) / deferredExecution(executingService, snapshot, expectedCarrier)
    // — see security-core.md
}
```

Both `resumeAsPrincipal` and `deferredExecution` call `IdentitySnapshotCodec.verifyForUse(snapshot)` — re-verifying **both** the same canonical-payload-plus-HMAC integrity path `decode` already uses **and** the snapshot's current freshness (re-evaluated against the codec's clock at the moment of reconstruction, not just at the earlier `decode`) — then confirm the snapshot's signed `carrier()` matches the caller-supplied `expectedCarrier` (`DurableCarrierDescriptor`, same `carrierId` and `target`) before building any `SecurityContext`; a `null` snapshot, a verification failure (integrity or freshness), or a carrier mismatch is wrapped into a typed `IdentityReconstructionException` (propagating the codec's `SnapshotDegradationReason`, defaulting to `DECODE_FAILED` for a carrier mismatch) before any context is built. The reconstructed identity is built from the verified snapshot's `content()` (`IdentitySnapshotContent`, not the envelope directly). The reconstructed `AuthenticationState` always carries empty evidence, the content's carried `assurance` as-is, and the reconstruction marker attributes (`identity.reconstructed=true`, `identity.reconstructed.mode` = `RESUME`/`DEFERRED`, `identity.reconstructed.capturedAt`, and `identity.reconstructed.authenticatedAt` = the content's original authentication instant, so a chained re-capture preserves it). Both entry points assemble the resulting context via `SecurityContexts.assembleReconstructed(...)` — this class has no dependency on any Dagger-provided context factory.

**Reconstructed authority is attribution-only** (FR-ID-CA-010). Both entry points assemble the context with `authorization()` = `AuthorizationClaims.empty()`; the snapshot's `authorizationClaims` are audit lineage recorded at capture time (who/what the principal *was*) and are never presented as the reconstructed context's *current* authority, so a principal demoted or revoked during the pause never resumes with frozen standing. Live re-resolution (`PrincipalAuthorityResolver`, Mode 2) and an explicit captured-authority opt-in (`resumeWithCapturedAuthority`, Mode 3) are opt-in decorators layered on top — see below.

**The minted `ReconstructionMarker` carries no principal of its own** (`new ReconstructionMarker(ReconstructedAuthorityMode.ATTRIBUTION_ONLY)` — both entry points). Mode 2 (`ReconstructedAuthorityResolvingAuthorizer`, below) resolves current authority directly from the returned context's `SecurityIdentity#actor()`, not from the marker: for `resumeAsPrincipal` that actor is the snapshot's own `content.actor()` — the principal being resumed; for `deferredExecution` that actor is `executingService`'s own actor — the executing service, **never** the subject-of-record (subject-authority evaluation / impersonation is explicitly out of v1 scope per FR-ID-DG-006). The subject-of-record (the snapshot's subject when present, else its actor) is carried on `SecurityIdentity#subject()` as attribution only.

---

### ReconstructedAuthorityResolvingAuthorizer

`Authorizer` decorator (`dev.vertique.security.runtime.authz`) implementing Mode 2 (and the Mode-3 passthrough) of reconstructed-context authorization — it re-resolves a verified reconstruction's **current** authority live, at authorization time, instead of trusting the snapshot's captured claims, *except* when the reconstruction's own `ReconstructionMarker#mode()` is `CAPTURED`.

```java
public ReconstructedAuthorityResolvingAuthorizer(Authorizer inner, PrincipalAuthorityResolver resolver);
```

Present only when `SecurityAuthzModule` sees a bound `PrincipalAuthorityResolver` — the outermost authorizer in the wired chain (`ReconstructedAuthorityResolvingAuthorizer` → `NarrowingAuthorizer` → `DefaultAuthorizer`; see Core Concepts above). Behavior turns strictly on `SecurityContext.reconstruction()` — never the descriptive `identity.reconstructed=true` string attribute:

| `reconstruction()` | Behavior |
|---|---|
| Empty (normal, live-authored context) | Passes through to the wrapped inner authorizer unchanged; the resolver is never consulted |
| Present, mode `CAPTURED` | The context's own captured claims are trusted as current authority as-is; the resolver is never consulted and the context is never rebuilt |
| Present, mode `ATTRIBUTION_ONLY` | The context's own **actor**'s (`SecurityContext#identity()#actor()`) `PrincipalKey` — **never** `identity().subject()` — is resolved **exactly once** per `authorize(...)` call (never cached across dispatches); the resolved claims replace the authorization dimension in an **evaluation-only** rebuild via `SecurityContexts.assembleReconstructed(...)` (re-attaching the same marker); the original context and its holder are never mutated |

Per FR-ID-DG-006, v1 delegation resolves the intersection of the acting principal's own authority and any grant scope — subject-authority evaluation (impersonation) is out of v1 scope, so both `resumeAsPrincipal` (actor == the resumed principal) and `deferredExecution` (actor == the executing service) resolve the *acting* principal's own current authority, never the subject-of-record's.

**Fails closed, and bounded.** `SecurityAuthzModule` wraps every bound `PrincipalAuthorityResolver` in `TimeoutPrincipalAuthorityResolver` (`identity.authz.resolutionTimeoutMs`, default 5000ms) before handing it to this class, so a resolver whose returned `Future` never completes cannot hang Mode-2 authorization indefinitely. A resolver failure, timeout, or ambiguous result denies with `AuthzReasonCodes#AUTHORITY_RESOLUTION_FAILED` rather than falling back to the snapshot's captured claims or propagating the failure; the frozen snapshot's own `authorization()` is never a trust source in the live re-resolution path. On a resolver failure or timeout, the inner `NarrowingAuthorizer` chain (and every narrower folded into it) is never invoked at all — the deny is produced entirely at this outer layer.

Every decision this class produces for a reconstructed context is stamped with `ReconstructedAuthorityMode.DECISION_ATTRIBUTE` (alias `AUTHORITY_MODE_ATTRIBUTE`) carrying the mode name that produced it, in `AuthorizationDecision#safeAttributes()` — the source `AuthorizationDecisionEvent#authorityMode()` (see `dev.vertique:vertique-security-core`) reads when present. Non-reconstructed passthrough decisions are never stamped.

---

### InMemoryPrincipalAuthorityResolver

Framework-shipped, in-memory `PrincipalAuthorityResolver` reference implementation (`dev.vertique.security.runtime.authz`). Holds a fixed, immutable `PrincipalKey → AuthorizationClaims` map supplied at construction — the same dependency-clean-default shape as `InMemoryDelegationGrantValidator`/`InMemoryPolicyDefinitionSource` — plus a separate fixed set of keys seeded as **ambiguous**.

```java
public InMemoryPrincipalAuthorityResolver(
    Map<PrincipalKey, AuthorizationClaims> claimsByPrincipal, Set<PrincipalKey> ambiguousPrincipals);
public InMemoryPrincipalAuthorityResolver(Map<PrincipalKey, AuthorizationClaims> claimsByPrincipal); // no ambiguous keys
```

`resolve(key)` fails closed (a *failed* `Future`) for a key present in the ambiguous set, or absent from the seeded claims map entirely. A seeded key with no claims resolves normally to `AuthorizationClaims.empty()` (a resolvable principal with no current authority), never a failure. This type is not wired by any framework Dagger module — an application binds it (or a durable-store-backed implementation) explicitly to opt into Mode 2.

---

### TimeoutPrincipalAuthorityResolver, PrincipalAuthorityResolutionConfig, PrincipalAuthorityResolutionConfigModule

`PrincipalAuthorityResolver` decorator (`dev.vertique.security.runtime.authz`) that bounds every delegate `resolve(...)` call with an operator-configured timeout, so a delegate resolver whose returned `Future` never completes cannot hang Mode-2 authorization indefinitely.

```java
public final class TimeoutPrincipalAuthorityResolver implements PrincipalAuthorityResolver {
    public TimeoutPrincipalAuthorityResolver(PrincipalAuthorityResolver delegate, Vertx vertx, long timeoutMs);
}

public record PrincipalAuthorityResolutionConfig(long resolutionTimeoutMs) {
    public static final long DEFAULT_RESOLUTION_TIMEOUT_MS = 5_000L;
    public static PrincipalAuthorityResolutionConfig defaults();
}
```

`SecurityAuthzModule` wraps **every** installed `PrincipalAuthorityResolver` in this decorator before handing it to `ReconstructedAuthorityResolvingAuthorizer` — a resolver is bounded whether or not the application remembers to wrap it itself. Each call races the delegate's returned `Future` against a one-shot, non-recurring `Vertx#setTimer` deadline local to that single invocation (scheduling.md's raw-timer discipline), cancelled the instant the delegate settles. On timeout the returned `Future` fails (never a bare exception), which `ReconstructedAuthorityResolvingAuthorizer`'s `.recover(...)` maps to `AuthzReasonCodes#AUTHORITY_RESOLUTION_FAILED` — the same fail-closed path as any other resolver failure. **The timeout does not cancel the delegate's underlying work** — it is a `Future`-race, not cooperative cancellation; a delegate backed by a blocking store call or in-flight network request keeps running after this decorator has given up on it. Operators whose resolver performs I/O should also configure a transport-level timeout on that I/O.

`SecurityAuthzModule` declares `Optional<PrincipalAuthorityResolutionConfig>` via `@BindsOptionalOf`, defaulting to `PrincipalAuthorityResolutionConfig#defaults()` (`DEFAULT_RESOLUTION_TIMEOUT_MS` = 5000ms) when no module binds one. `PrincipalAuthorityResolutionConfigModule` is the opt-in companion that config-drives `resolutionTimeoutMs` from `identity.authz.resolutionTimeoutMs` instead of the hardcoded default:

```yaml
identity:
  authz:
    resolutionTimeoutMs: 5000
```

Installing `PrincipalAuthorityResolutionConfigModule` has no effect unless the application has also bound a `PrincipalAuthorityResolver` (the separate Mode-2 opt-in) — with no resolver bound, the configured timeout is simply unused.

---

### IdentitySnapshotCodec

Encodes and decodes `IdentitySnapshot` instances to and from a signed JSON byte representation (`dev.vertique.security.runtime`).

```java
public class IdentitySnapshotCodec {
    public IdentitySnapshotCodec(SnapshotHmac hmac, SnapshotFreshnessPolicy freshnessPolicy);
    public IdentitySnapshotCodec(SnapshotHmac hmac); // no budgets, 30s skew, systemUTC — unit fixtures
    public byte[] encode(IdentitySnapshot snapshot);
    public IdentitySnapshot decode(byte[] encoded);
    public void verifyIntegrity(IdentitySnapshot snapshot);
    public void verifyForUse(IdentitySnapshot snapshot);
    Instant now(); // freshness policy's clock — the durable encoder's issuedAt time source
    Instant envelopeExpiry(Instant capturedAt); // the durable encoder's expiresAt time source
}
```

- **`encode`** recomputes the `integrity()` tag using the codec's active signing key before serializing — any tag value the caller supplied is replaced, since the codec is the sole source of an authoritative signature.
- **`decode`** is the fail-closed counterpart: rejects an unknown-newer `schemaVersion` (`SUPPORTED_SCHEMA_VERSION = 2`), verifies the snapshot's HMAC tag, and runs the injected `SnapshotFreshnessPolicy` against the decoded snapshot's temporal envelope before returning it.
- **`verifyIntegrity`** is the single public HMAC-verification entry point — extracted from `decode`'s private verification step so `decode`-time and reconstruction-time (mint-time) verification can never drift independently. It re-verifies integrity only, never freshness.
- **`verifyForUse`** is the canonical **reconstruction-time** verification entry point: `verifyIntegrity` **plus** a fresh `SnapshotFreshnessPolicy.check` call, re-evaluated against the codec's *current* clock. `DefaultIdentityReconstruction`/`DefaultCapturedAuthorityReconstruction` call this — never bare `verifyIntegrity` — before minting any `SecurityContext`, so a snapshot that verified fresh at decode but has since aged past its effective expiry while retained in memory is still rejected fail-closed at the mint. Every reconstruction path therefore enforces both dimensions twice: once at `decode` (receive-side durable-carriage decode) and again at `verifyForUse` (reconstruction/mint).
- **`envelopeExpiry(capturedAt)`** computes the `expiresAt` the durable encoder signs into a fresh envelope: `capturedAt + maxSnapshotLifetime` when configured, else a documented 24-hour default — anchored on the immutable content `capturedAt`, never on `issuedAt`, so a chained re-encode cannot renew a snapshot's expiry past what the original capture already earned.
- The canonical payload signed/verified is the envelope's `content`/`carrier`/`issuedAt`/`expiresAt` fields plus `algorithm`/`keyId`, with `integrity.tag` replaced by a fixed placeholder so the tag never signs over itself. Canonicalization sorts map entries and object properties and pins `Instant`/`BigDecimal` formatting so a snapshot signed on one node verifies byte-identically on any other.
- All failures throw `IdentitySnapshotCodecException`, carrying a typed `reason()` (`SnapshotDegradationReason`) — see `SnapshotFreshnessPolicy` below for the `EXPIRED`/`MALFORMED_TEMPORAL` reasons the freshness check can add.

---

### SnapshotFreshnessPolicy

Fail-closed freshness policy applied by `IdentitySnapshotCodec.decode` after HMAC verification — the F5 replay defense's freshness half (ADR-0166).

```java
public record SnapshotFreshnessPolicy(
    Optional<Duration> maxCarrierLifetime,
    Optional<Duration> maxSnapshotLifetime,
    Duration clockSkew,
    Clock clock) {
    public void check(IdentitySnapshot snapshot); // throws IdentitySnapshotCodecException, fail-closed
}
```

`check(snapshot)` rejects a snapshot whose temporal envelope is malformed, impossibly future-dated (beyond `clockSkew`), or past its **effective expiry** — the three-term minimum:

```
effectiveExpiry = min(
    expiresAt,
    issuedAt + maxCarrierLifetime,           // only when configured
    content.capturedAt + maxSnapshotLifetime // only when configured
)
accept iff now <= effectiveExpiry + clockSkew
```

The snapshot-lifetime term is anchored on the *immutable* `content.capturedAt()`, never `issuedAt`, so a chained re-encode (which mints a fresh `issuedAt` for a new carrier) cannot renew authority already aged out. With both budgets absent — the default before an operator configures `IdentitySnapshotConfig`'s `maxCarrierLifetimeMs`/`maxSnapshotLifetimeMs` — only the signed `expiresAt` bounds the snapshot. The policy reapplies its *current* budgets on every decode (it never trusts a previously-signed expiry alone), so an operator can retroactively tighten already-persisted rows by lowering a budget; a snapshot is never re-signed or refreshed on this path. A malformed/future-dated envelope throws with reason `MALFORMED_TEMPORAL`; a past-expiry envelope throws with reason `EXPIRED`.

---

### SnapshotHmac, SnapshotHmacConfig, SnapshotHmacKeyConfig, SnapshotHmacException

Keyset-based HMAC signer/verifier for `IdentitySnapshot` envelopes, and its config-record backing (`dev.vertique.security.runtime`).

```java
public class SnapshotHmac {
    public SnapshotHmac(Map<String, String> keysById, String activeKeyId);
    public String sign(byte[] payload, String keyId, String algorithm);
    public boolean verify(byte[] payload, String keyId, String algorithm, String tag);
    public String activeKeyId();
}

public record SnapshotHmacConfig(SnapshotHmacKeyConfig active, List<SnapshotHmacKeyConfig> previous)
public record SnapshotHmacKeyConfig(String keyId, String secretRef)
```

- **`SnapshotHmac`** holds the active signing key plus zero or more previous (rotated-out) keys, indexed by `keyId`. `sign` always signs under the caller-supplied `keyId` (in practice the active key); `verify` looks the signing key up by the tag's own `keyId`, so a snapshot signed under a key since demoted to "previous" still verifies within the rotation grace window. A `keyId` absent from the keyset throws `SnapshotHmacException` (fail-closed) rather than silently failing; a tag that simply does not match is a normal `false` result. The MAC algorithm named on a snapshot (attacker-influenced, since it originates from the app-writable durable store) is checked against a server-side allowlist — `HmacSHA256`, `HmacSHA384`, `HmacSHA512` — **before** any `Mac.getInstance` call, so an algorithm-confusion downgrade to a weaker or bogus primitive is rejected fail-closed.
- **`SnapshotHmacConfig`** is the root config record deserialized from `identity.snapshot.hmacKeys` via the injected `ConfigParser` (see `config.md`). `active` is required; a `previous` key reusing the active key's `keyId`, and two `previous` keys sharing a `keyId`, both throw `ConfigurationException` at parse time (the duplicate check runs before the id→secret map is built, so a colliding raw secret can never leak into the startup log). Messages name only the offending `keyId`, never a secret.
- **`SnapshotHmacKeyConfig`** carries `keyId` and `secretRef` (the resolved secret material). `secretRef` is used directly as HMAC key material and must be at least 32 UTF-8 bytes (HMAC-SHA256 key strength); a shorter value throws `ConfigurationException` at parse time. `secretRef` is `@JsonProperty(access = WRITE_ONLY)` and `toString()` is overridden to redact it — it is never logged or re-serialized.
- **`SnapshotHmacException`** carries a `Kind` — `UNKNOWN_KEY` ("keyset populated but this key id is absent"), `KEY_UNAVAILABLE` ("no key material configured at all"), or `UNSUPPORTED_ALGORITHM` (the requested MAC algorithm is outside the server allowlist) — which `IdentitySnapshotCodec` maps to the matching `SnapshotDegradationReason`.

---

### IdentitySnapshotCarriageModule

Dagger module (`dev.vertique.security.runtime`) that wires the config-backed identity-snapshot keyset and freshness policy, and registers the snapshot's durable-carriage encoder/decoder pair into `vertique-context`'s multibinding sets.

Reads the `identity.snapshot` config section and provides `IdentitySnapshotConfig`, `SnapshotHmac`, `SnapshotFreshnessPolicy` (the config's `maxCarrierLifetime`/`maxSnapshotLifetime`/`clockSkew` budgets over `Clock.systemUTC()`), `IdentitySnapshotCodec` (backed by both), `IdentitySnapshotFactory` (`DefaultIdentitySnapshotFactory`), the `IdentitySnapshotCapture` ingress seam constructed with the config's `captureEnabled` kill-switch (satisfying rest-security's `@BindsOptionalOf IdentitySnapshotCapture`), the `IdentitySnapshotDegradationPolicy`, and contributes `IdentitySnapshotDurableEncoder`/`Decoder` `@IntoSet` into `ContextRuntimeModule`'s `DurableContextMetadataEncoder`/`Decoder` sets (mirrors `vertique-correlation`'s `CorrelationContextModule`). Applications wiring identity-snapshot durable carriage must include this module — without it, the encoder/decoder pair is never registered and the carriage path is inert.

Config path `identity.snapshot`:

```yaml
identity:
  snapshot:
    captureEnabled: true
    onDegradation: FAIL
    hmacKeys:
      active:
        keyId: key-2026-07
        secretRef: ${IDENTITY_SNAPSHOT_HMAC_KEY}
      previous:
        - keyId: key-2026-06
          secretRef: ${IDENTITY_SNAPSHOT_HMAC_KEY_PREVIOUS}
    maxCarrierLifetimeMs: 3600000
    maxSnapshotLifetimeMs: 86400000
    clockSkewMs: 30000
    carriageRequirements:
      delayed-job: REQUIRED
      cron: FORBIDDEN
```

---

### IdentitySnapshotConfig, IdentitySnapshotDegradationPolicy

`IdentitySnapshotConfig` is the root config record for the `identity.snapshot` section:

```java
public record IdentitySnapshotConfig(
    boolean captureEnabled,
    IdentitySnapshotDegradationPolicy onDegradation,
    SnapshotHmacConfig hmacKeys,
    @Nullable Long maxCarrierLifetimeMs,
    @Nullable Long maxSnapshotLifetimeMs,
    long clockSkewMs,
    Map<String, CarriageRequirement> carriageRequirements) {
    public CarriageRequirement carriageRequirementFor(String targetKind); // defaults to OPTIONAL
    public Optional<Duration> maxCarrierLifetime();
    public Optional<Duration> maxSnapshotLifetime();
    public Duration clockSkew();
}
```

- `captureEnabled` defaults to `true`; `onDegradation` defaults to `FAIL`; `hmacKeys` (`SnapshotHmacConfig`) is required — signing/verification cannot proceed without at least an active key.
- `maxCarrierLifetimeMs`/`maxSnapshotLifetimeMs` back `SnapshotFreshnessPolicy`'s carrier-/snapshot-lifetime budgets (milliseconds); `null` = unbounded/absent until an operator configures them, **except** that the compact constructor requires both to be non-`null` the moment any `carriageRequirements` entry is `REQUIRED` — a never-expiring `REQUIRED` snapshot would otherwise be a standing bearer credential. Violating this throws `ConfigurationException` at provider time.
- `clockSkewMs` defaults to `30000` (30s).
- `carriageRequirements` maps a durable-target-kind string (e.g. `"delayed-job"`, `"cron"`, `"outbox-relay"`) to a `CarriageRequirement`; `carriageRequirementFor(kind)` defaults an unlisted kind to `OPTIONAL`. See `CarriageRequirement` in `dev.vertique:vertique-security-core`.

`IdentitySnapshotDegradationPolicy` is the enum governing what happens when a deferred execution's carried snapshot is present but fails HMAC/freshness/carrier verification:

| Value | Behavior |
|-------|----------|
| `FAIL` (default) | Fail the deferred dispatch outright when the carried snapshot cannot be verified |
| `CONTINUE_WITHOUT_IDENTITY` | Emit `IdentitySnapshotDegradationEvent` and let the dispatch proceed with no verified subject |

---

### IdentitySnapshotDurableEncoder, IdentitySnapshotDurableDecoder, IdentitySnapshotContext

The durable-carriage pair for `IdentitySnapshotContext` (`dev.vertique.security.runtime`), registered into `vertique-context`'s `DurableContextMetadataEncoder`/`Decoder` multibinding sets by `IdentitySnapshotCarriageModule`.

- **`IdentitySnapshotContext`** — `record IdentitySnapshotContext(Optional<IdentitySnapshotContent> content, Optional<IdentitySnapshot> snapshot, Optional<SnapshotDegradationReason> unverifiableReason) implements ContextValue`; the `ContextHolder`-bindable wrapper carried across durable execution boundaries (delayed jobs, inbox/outbox, workflow resume), modelling both sides of the schema-v2 content/envelope split with three mutually-exclusive states. The compact constructor enforces an exactly-one invariant: precisely one of `content` / `snapshot` / `unverifiableReason` is present. Three factories build it: `of(IdentitySnapshotContent)` for the **producer-side captured-content** state (the encoder assembles and signs the envelope around it); `verified(IdentitySnapshot)` for a **verified** snapshot (decoded, HMAC-, freshness-, and carrier-verified on the receive side); `unverifiable(SnapshotDegradationReason)` for a **present-but-unverifiable** context that retains only the typed failure reason (never the snapshot, so an unverifiable snapshot can never yield a reconstructed identity). The `verified()` accessor reports whether the `snapshot` state is bound.
- **`IdentitySnapshotDurableEncoder`** — resolves the content to sign (the producer-side captured `content`, or — for a receive-side re-encode across a durable boundary — the already-bound verified `snapshot`'s `content()`, so a re-encode re-signs the *same* content for the new carrier, preserving its immutable `capturedAt`), assembles the schema-v2 `IdentitySnapshot` envelope (`carrier` from `DurableEncodeContext#carrier()`, `issuedAt` from the codec's clock, `expiresAt` from `codec.envelopeExpiry(capturedAt)`), signs it via `IdentitySnapshotCodec`, and writes the resulting bytes, base64-encoded, under the `"identity-snapshot"` namespace alongside a `present` companion flag.
  - **Pre-boundary-wiring default carrier.** Until a boundary supplies a real `DurableCarrierDescriptor`, the encoder signs a fixed, boundary-independent `"unbound"` sentinel carrier (`UNBOUND_CARRIER_ID` / `UNBOUND_TARGET`) rather than nothing.
  - **Doomed-expiry detection (F5).** When `DurableEncodeContext#fireTime()` is present (the delayed-job schedule path) and the freshly-computed `expiresAt` would not be after it, the envelope being signed would already be expired by the time the durable row fires. `IdentitySnapshotDegradationPolicy.FAIL` throws `DurableEncodeRejectedException` (rejecting the enqueue); `CONTINUE_WITHOUT_IDENTITY` logs a warning and returns `DurableMetadata.empty()` — the row persists with no `identity-snapshot` namespace rather than a snapshot guaranteed to fail closed at first decode.
  - A present-but-unverifiable receive-side context has nothing legitimate to re-encode: the encoder emits no `identity-snapshot` namespace at all (`DurableMetadata.empty()`).
- **`IdentitySnapshotDurableDecoder`** — pairs with the encoder: decodes, HMAC-verifies, and freshness-checks via `IdentitySnapshotCodec.decode`, then confirms the decoded snapshot's signed `carrier()` matches the expected carrier for the receiving dispatch (`DurableDecodeContext#carrier()`, or the same `"unbound"` sentinel when the boundary supplies none). Decoding is fail-closed and never throws, but it distinguishes **absent** from **present-but-unverifiable** so a tampered, stale, or transplanted snapshot cannot silently bypass the receive-side degradation gate:
  - **Absent** — the namespace body is missing, or its `present` companion flag is not `true`: `ContextDecodeResult.empty()`. Nothing is bound (the reconstruction initializer falls to its no-snapshot path).
  - **Verified** — the codec decodes and verifies the snapshot, its carrier is not the `"unbound"` sentinel (see below), and the carrier matches the expected carrier: a `ContextDecodeResult` carrying `IdentitySnapshotContext.verified(...)`.
  - **Present-but-unverifiable** — the `present` flag is `true` but the payload is malformed base64/JSON, fails the codec's HMAC/freshness check (`IdentitySnapshotCodecException`), its signed carrier does not match the expected carrier, or its signed carrier *is* the `"unbound"` sentinel (F3a fail-closed backstop, next paragraph): a `ContextDecodeResult` carrying a **bound** `IdentitySnapshotContext.unverifiable(reason)` value (mapped from the codec's typed `SnapshotDegradationReason`, or `DECODE_FAILED` for a carrier mismatch/sentinel) **plus** one diagnostic warning. Binding the value — rather than dropping it as a value-less failure — is what routes the degradation to `IdentitySnapshotReconstructionInitializer`'s marker path so the non-droppable degradation event and the `FAIL`/`CONTINUE_WITHOUT_IDENTITY` policy are enforced.
  - **F3a fail-closed sentinel backstop.** A boundary not yet wired with a real per-row carrier signs the identical `"unbound"` sentinel into every snapshot it emits, and the decoder's expected carrier defaults to that same sentinel when the boundary supplies none — so the ordinary carrier-match check alone would be a no-op for such a boundary (every sentinel-signed snapshot "matches" every other). The decoder therefore treats a signed carrier equal to the sentinel as unconditionally unverifiable regardless of what the expected carrier resolves to, so a sentinel-signed snapshot can never be transplanted between rows of a not-yet-wired boundary.

Binding an `IdentitySnapshotContext` does not, by itself, imply the wrapped snapshot has been verified for the current binding — a bound context may be a `verified()==false` present-but-unverifiable marker. Decode-time verification (HMAC + freshness + carrier) happens in the decoder; reconstruction re-verifies integrity, freshness (via `IdentitySnapshotCodec#verifyForUse`, re-evaluated against the codec's current clock), and carrier again at the mint (see `IdentityReconstruction` in `security-core.md`).

---

### IdentitySnapshotReconstructionInitializer, IdentitySnapshotReconstructionModule

The receive-side wiring that reconstructs a `SecurityContext` from a durably-carried snapshot at every `SERVICE_DISPATCH` receive (delayed-job execution, inbox handler dispatch, and any other event-bus service dispatch).

**`IdentitySnapshotReconstructionInitializer`** is an `InboundContextInitializer` implementing the frozen binding-precedence rule. Cases 4 and 5 additionally resolve the dispatch's durable-target kind (`DeferredExecutionOrigin#kind()` when an origin is present, else the initialization context's `boundary()` string) and look up its `CarriageRequirement` via the injected `IdentitySnapshotConfig` — the "expected-but-absent carriage detection" defense (ADR-0166):

1. **Case 1 (live)** — a live `SecurityContext` is already bound → no-op (idempotent).
2. **Case 2f (`FORBIDDEN` refusal)** — runs *before* case 2/3 verification: a *present* `IdentitySnapshotContext` (verified or unverifiable) whose resolved target-kind is `CarriageRequirement#FORBIDDEN` is refused outright — only an `EXPECTED_ABSENT` `SnapshotDegradationMarker` is bound, no `SecurityContext`, regardless of whether the snapshot would otherwise verify.
3. **Case 2 (reconstruct)** — a **verified** `IdentitySnapshotContext` is bound → `IdentityReconstruction.deferredExecution(...)` reconstructs the context (re-verifying integrity and carrier per FR-ID-CA-008 as defense-in-depth; not freshness), which is bound. A verified snapshot is HMAC-trusted, so this case mints regardless of whether a `DeferredExecutionOrigin` is present.
4. **Case 3 (degrade)** — an `IdentitySnapshotContext` is bound but the identity could not be verified. Per the origin-or-verified mint-evidence rule, an unverifiable snapshot mints a service context **only** when a `DeferredExecutionOrigin` proves the dispatch is deferred execution: origin present → a service-only context (no subject) is minted for the executing identity alongside a `SnapshotDegradationMarker`; origin absent → **only** the marker is bound, no `SecurityContext` — minting one here would let an attacker who writes a garbage, unverifiable durable row with no origin marker end up strictly better off under `CONTINUE_WITHOUT_IDENTITY` than writing nothing at all. Either way the marker lets the async degradation gate emit the non-droppable degradation event and apply the configured `FAIL`/`CONTINUE_WITHOUT_IDENTITY` policy. This covers two sources of the same fail-closed outcome: a decoder-detected `unverifiable(reason)` context, and a snapshot that verified at decode but whose reconstruction re-verification nonetheless throws `IdentityReconstructionException` (defense-in-depth).
5. **Case 4 (bounded mint)** — no `IdentitySnapshotContext` is bound **but a `DeferredExecutionOrigin` proves the dispatch is deferred execution** (a durable delayed job, a cron trigger, or an outbox relay) → a service context (no subject) is minted for the origin-resolved executing identity. When the resolved target-kind is `CarriageRequirement#REQUIRED`, an `EXPECTED_ABSENT` `SnapshotDegradationMarker` is bound *in addition to* the minted context — a `REQUIRED` target dispatching with no snapshot at all is a degradation, not a silent gap.
6. **Case 5 (fail closed)** — no `IdentitySnapshotContext` **and** no `DeferredExecutionOrigin` — an ordinary context-empty dispatch with no evidence of deferred execution → for `OPTIONAL`/`FORBIDDEN` target-kinds, **nothing is bound** (fail closed); for a `REQUIRED` target-kind, an `EXPECTED_ABSENT` marker is bound instead of the plain no-op (still no `SecurityContext` — no origin proves deferred execution — but the missing expected carriage is no longer silent). `ServiceAuthorizationInterceptor` then treats the unbound context as `AUTHENTICATION_REQUIRED` **for `@RequiresAction`-gated operations — ungated operations run without a bound context, exactly as if the reconstruction module were not installed** (ADR-0165). Minting a SYSTEM context here would over-broadly privilege any context-empty service dispatch, so the initializer refuses.

Cases 2 and 4 always leave a `SecurityContext` bound; case 3 leaves one bound only when a `DeferredExecutionOrigin` is present; cases 2f and 5 never mint a `SecurityContext`. The `DeferredExecutionOrigin` provenance gate (case 4 vs. case 5, and case 3's origin-present vs. origin-absent split) is the security boundary that keeps the bounded mint from privileging an ordinary context-empty (or unproven-deferred) dispatch as SYSTEM — the provenance is bound on the send side by each deferred-execution boundary (`DelayedJobPoller`, `CronJobDispatcher`, `ServiceOutboxDestinationHandler`) into its FQCN-keyed dispatch-context map and reinstated before the initializers run; case 2f is a separate, unconditional boundary overriding even case 2's HMAC-trusted mint evidence once the target-kind is declared `FORBIDDEN`. The executing service identity is resolved from the `DeferredExecutionOrigin` via the `ServiceIdentityResolver` seam (falling back to a boundary-keyed system identity when a snapshot is present but no origin is bound); the mint honors the resolved actor's `PrincipalType` — a `SYSTEM` actor via `SecurityContexts.system(...)`, a `SERVICE` actor via `SecurityContexts.unauthenticated(...)` (stamping `custom("system")` on a SERVICE actor would misattribute it as system-acting; ADR-0163), any other actor type rejected.

**`IdentitySnapshotReconstructionModule`** is the Dagger module (`includes = {IdentitySnapshotCarriageModule, PrivilegedIdentityModule, ContextRuntimeModule}`) that contributes the initializer into the `Set<InboundContextInitializer>` multibinding and declares `ServiceIdentityResolver` as an overridable `@BindsOptionalOf` seam — it provides **no** concrete resolver binding, so absent an application binding the initializer falls back to its built-in default (`SystemIdentities.scheduledJob(origin.reference())`). An application overrides by binding its own `@Provides ServiceIdentityResolver` (present ⇒ used, with no duplicate-binding collision since the framework binds no concrete resolver). Applications wiring identity-snapshot durable carriage into deferred execution must include this module (and supply the required `identity.snapshot.hmacKeys` config) alongside their `DelayedJobModule`/inbox-outbox module and `DispatchModule`.

---

### PrivilegedIdentityModule

Dagger `@Module` (`dev.vertique.security.runtime`) that provides `IdentityReconstruction` to framework infrastructure components only.

```java
@Module
public abstract class PrivilegedIdentityModule {
    @Provides @Singleton
    static IdentityReconstruction identityReconstruction(IdentitySnapshotCodec codec);
}
```

Kept deliberately out of the general `SecurityEventsModule`/`SecurityAuthzModule` wiring: an application that includes this module is doing so visibly, so accidental general-purpose injection of the reconstruction service is caught at code review by an unexpected module in the component's module list, rather than being silently available everywhere. Must always be installed alongside `IdentitySnapshotCarriageModule` — it wires `IdentityReconstruction` to the shared, config-backed `IdentitySnapshotCodec` singleton that module provides.

---

### CapturedAuthorityReconstructionModule, DefaultCapturedAuthorityReconstruction, CapturedAuthorityActivation, CapturedAuthorityConfig

Opt-in Dagger module and default implementation for Mode 3 — captured-authority reconstruction (`dev.vertique.security.runtime`).

```java
@Module
public abstract class CapturedAuthorityReconstructionModule {
    @Provides @Singleton
    static CapturedAuthorityActivation capturedAuthorityActivation(
        IdentitySnapshotCodec codec, IdentitySnapshotConfig config, CapturedAuthorityConfig captured,
        SecurityEventEmitter emitter);
    @Provides @Singleton
    static CapturedAuthorityConfig capturedAuthorityConfig(@VertxConfig JsonObject config, ConfigParser parser);
}
```

**Structural never-default, and the audited seam is structurally unavoidable.** This module is deliberately **not** included by `PrivilegedIdentityModule`, `IdentitySnapshotReconstructionModule`, or any other general wiring, and there is no `@BindsOptionalOf` fallback for `CapturedAuthorityReconstruction` anywhere in the framework — absent this module the type is simply unbound, so an accidental dependency on it fails the Dagger graph rather than silently resolving to a degraded implementation. The module goes one step further: it exposes **only** `CapturedAuthorityActivation` — there is no `@Provides` (and `CapturedAuthorityReconstruction` has no `@Inject` constructor) for the raw `CapturedAuthorityReconstruction` seam anywhere in the framework, so no injectable binding exists that could bypass the activation-audit event. The internally-constructed `DefaultCapturedAuthorityReconstruction` collaborator is a private implementation detail of the `capturedAuthorityActivation` provider, never itself a Dagger binding — the only way any framework or application code can put captured authority into effect is through `CapturedAuthorityActivation`. Must always be installed alongside `IdentitySnapshotCarriageModule` (for the shared `IdentitySnapshotCodec`/`IdentitySnapshotConfig`) and `SecurityEventsModule` (for `CapturedAuthorityActivation`'s `SecurityEventEmitter`).

**Startup `REQUIRED` validation.** For every target kind named in `CapturedAuthorityConfig#allowedTargetKinds()`, the provider checks `IdentitySnapshotConfig#carriageRequirementFor(kind)` resolves to `CarriageRequirement#REQUIRED` — a captured-authority target that is merely `OPTIONAL`/`FORBIDDEN` would be a standing bearer-credential hole. A violation throws `ConfigurationException` naming the offending kind at startup, not at first use. Config path `identity.snapshot.capturedAuthority`:

```yaml
identity:
  snapshot:
    carriageRequirements:
      outbox-relay: REQUIRED
    capturedAuthority:
      allowedTargetKinds:
        - outbox-relay
```

**`DefaultCapturedAuthorityReconstruction`** is the provided implementation, backed by the shared `IdentitySnapshotCodec` and the validated allowlist. Both entry points re-verify the snapshot's integrity **and current freshness** via `IdentitySnapshotCodec#verifyForUse` (never bare `verifyIntegrity`) before confirming the carrier and allowlist, mirroring `DefaultIdentityReconstruction`'s fail-closed re-verification.

**`CapturedAuthorityActivation`** (`@Singleton`, plain `@Inject` constructor — not bound by an explicit `@Provides`) is the sanctioned Mode-3 entry point consuming infrastructure calls instead of `CapturedAuthorityReconstruction` directly — which, per the module javadoc above, has no injectable binding to call directly in the first place:

```java
public Future<SecurityContext> activateResume(IdentitySnapshot snapshot, DurableCarrierDescriptor expectedCarrier);
public Future<SecurityContext> activateDeferred(
    SecurityIdentity executingServiceIdentity, IdentitySnapshot snapshot, DurableCarrierDescriptor expectedCarrier);
```

Both methods invoke the matching `CapturedAuthorityReconstruction` method, then build and emit a `CapturedAuthorityActivatedEvent` via `SecurityEventEmitter` and resolve only once that emission completes. This is an **emission-ordering** guarantee, not a durable or acknowledged-delivery one (ADR-0170): since `SecurityEventEmitter.emit` always succeeds and its future resolving means every registered `SecurityEventObserver` has been invoked and settled, a caller awaiting either method is guaranteed the audit event was **emitted to and settled by** every observer before it observes the reconstructed `SecurityContext` — but per `SecurityEventEmitter`'s AC-SE-6 per-observer isolation, an observer that fails (including one that fails to durably persist the event) is caught, logged, and counted as settled the same as one that succeeds; there is no fail-closed acknowledgement channel back to the caller. A reconstruction failure (fail-closed integrity/freshness/carrier/allowlist denial, all synchronously thrown by `CapturedAuthorityReconstruction`) surfaces here as a **failed** `Future`, never a thrown exception. The event's `correlation` is always `CorrelationContext.unbound()` and its `origin` mirrors `ctx.origin()` (always empty for a Mode-3 reconstruction).

---

### ServiceIdentityResolver

Functional seam (`dev.vertique.security.runtime`) that resolves the executing `SecurityIdentity` to use as the actor when `IdentitySnapshotReconstructionInitializer` mints a service-only or reconstructed `SecurityContext` for a deferred dispatch, from the `DeferredExecutionOrigin` provenance that proved the dispatch is deferred.

```java
@FunctionalInterface
public interface ServiceIdentityResolver {
    SecurityIdentity resolve(DeferredExecutionOrigin origin);
}
```

The `DeferredExecutionOrigin` carries the provenance `kind` (`delayed-job` / `cron` / `outbox-relay`) and a `reference` — the specific job-type / cron-name / event-type — that becomes the resolved identity's audit reason. The seam is bound via `@BindsOptionalOf` with no concrete framework binding: absent an application resolver the built-in default maps `origin.reference()` to `SystemIdentities.scheduledJob(...)`; an application overrides by binding its own `@Provides ServiceIdentityResolver` (present ⇒ used), so the choice of executing identity stays with the boundary that knows its own semantics.

---

### IdentitySnapshotCapture

Producer-side gate (`dev.vertique.security.runtime`) that captures the currently authenticated identity as an `IdentitySnapshotContext` and binds it into the `ContextHolder`, honored only when the global `identity.snapshot.captureEnabled` kill-switch is on. Provided as a `@Provides @Singleton` binding by `IdentitySnapshotCarriageModule` — **not** an `@Inject` constructor — so rest-security's `@BindsOptionalOf IdentitySnapshotCapture` resolves to `Optional.empty()` unless carriage is installed (Dagger forbids `@BindsOptionalOf` of an unqualified `@Inject` type).

```java
public final class IdentitySnapshotCapture {
    public IdentitySnapshotCapture(ContextHolder holder, IdentitySnapshotFactory factory, boolean captureEnabled);
    public ContextHolder.Scope captureFrom(SecurityContext live);
}
```

- **`captureFrom(SecurityContext live)`** is the ingress seam `IdentityResolutionMiddleware` calls, and the type's sole public method: it evaluates the `captureEnabled` kill-switch **first** — when disabled it returns the shared `ContextScopes.noop()` scope *without* consulting the factory, so the no-capture hot path pays nothing. An eligibility gate then checks the live context: an anonymous actor (`PrincipalType.ANONYMOUS`) or a `NONE`-kind primary authentication method also short-circuits to `ContextScopes.noop()` without consulting the factory, since an unauthenticated identity carries no attribution worth persisting. Otherwise it captures the credential-free `IdentitySnapshotContent` via the injected `IdentitySnapshotFactory`, binds it as `IdentitySnapshotContext.of(content)`, and returns the bind `ContextHolder.Scope` so the caller unwinds it with the request lifecycle.

**Opt-in install pattern.** Schedule-time capture and execute-side reconstruction are not framework-default-on (they require a mandatory HMAC key — see ADR-0162). An application activates the whole path by including `IdentitySnapshotReconstructionModule` (which transitively pulls `IdentitySnapshotCarriageModule` — capture + codec + durable encoder/decoder — and `PrivilegedIdentityModule` — reconstruction) and supplying the `identity.snapshot.hmacKeys` config section. With that in place, `captureFrom(...)` binds an `IdentitySnapshotContext` at REST ingress and the existing registry-driven `DurableContextPropagator.mergeCaptured(...)` in the delayed-job and outbox schedule paths carries it automatically across the `DELAYED_JOB` / `OUTBOX` / `OUTBOX_SERVICE` boundaries, where `IdentitySnapshotReconstructionInitializer` reconstructs it on receive. Without the include, rest-security's `Optional<IdentitySnapshotCapture>` is empty and the REST hot path is inert (ADR-0164).

---

#### Invariants & Gotchas (identity-snapshot durable carriage)

- **HMAC is mandatory and always-on — there is no unsigned mode.** `IdentitySnapshot.integrity()` is a required, non-`Optional` field; `IdentitySnapshotCodec` is the single signer/verifier, and `verifyIntegrity` is reused identically by `decode` and by `DefaultIdentityReconstruction`/`DefaultCapturedAuthorityReconstruction` so the checks can never drift independently.
- **Freshness is re-checked at both decode and reconstruction.** `SnapshotFreshnessPolicy.check` runs inside `IdentitySnapshotCodec.decode` (so `IdentitySnapshotDurableDecoder` enforces it on every receive-side decode) **and** inside `IdentitySnapshotCodec#verifyForUse` — the method every reconstruction path (`DefaultIdentityReconstruction`/`DefaultCapturedAuthorityReconstruction`) calls before minting any `SecurityContext`, re-evaluated against the codec's *current* clock. `verifyIntegrity` alone (the method `verifyForUse` composes) still checks integrity only; reconstruction code must call `verifyForUse`, never bare `verifyIntegrity`, so a snapshot that decoded fresh minutes ago but is reconstructed later is re-aged and rejected fail-closed if it has since expired.
- **Carrier binding is checked at two independent points.** `IdentitySnapshotDurableDecoder` checks it at decode (against `DurableDecodeContext#carrier()`); `DefaultIdentityReconstruction`/`DefaultCapturedAuthorityReconstruction` check it again at reconstruction (against the caller-supplied `expectedCarrier`) — both against the same signed `SnapshotCarrierBinding`, so a decode-time pass does not exempt reconstruction from re-checking it.
- **Fail-closed on unknown key or untrusted algorithm.** `SnapshotHmac.verify`/`sign` throw `SnapshotHmacException` when the tag's `keyId` is not present in the configured keyset (`UNKNOWN_KEY`), when no key material is configured at all (`KEY_UNAVAILABLE`), or when the named MAC algorithm is outside the server allowlist `HmacSHA256`/`HmacSHA384`/`HmacSHA512` (`UNSUPPORTED_ALGORITHM`, checked before `Mac.getInstance`) — each a distinct fail-closed condition from a simple tag mismatch (`verify` returning `false`), and callers must not treat the exception as "not verified", only as "verification could not be determined".
- **Keyset rotation grace window.** `SnapshotHmacConfig` supports exactly one `active` signing key plus zero or more `previous` (verification-only) keys, indexed by `keyId`; a snapshot signed under a key since demoted to "previous" still verifies until the operator retires that key from `previous`. A `previous` key reusing the active key's `keyId`, a duplicate `keyId` across two `previous` keys, or any `secretRef` shorter than 32 UTF-8 bytes fails config parsing.
- **A `REQUIRED` carriage requirement forces finite freshness budgets.** `IdentitySnapshotConfig`'s compact constructor throws `ConfigurationException` if any `carriageRequirements` entry is `REQUIRED` while `maxCarrierLifetimeMs`/`maxSnapshotLifetimeMs` is unset — a never-expiring `REQUIRED` snapshot would otherwise be a standing bearer credential.
- **`PrivilegedIdentityModule` is a distinct Dagger module from `SecurityEventsModule`/`SecurityAuthzModule`.** It must be installed only on framework infrastructure components — job execution, inbox/outbox, workflow resume — never on general request-handling components. Installing it makes the component's module list a legible security-review artifact: a reviewer sees `PrivilegedIdentityModule` and knows that component can mint `SecurityContext`s from unauthenticated, second-hand data (a durably-carried snapshot), not from live verified credentials.
- `PrivilegedIdentityModule` must always be paired with `IdentitySnapshotCarriageModule` in the same component — it wires `IdentityReconstruction` to that module's shared, config-backed `IdentitySnapshotCodec` singleton and has no config-backed codec of its own.

---

## Extension Points

### Contributing an ActionContributor

```java
// In a Dagger module
@Provides @IntoSet
static ActionContributor workflowActions(WorkflowActionContributor c) { return c; }
```

### Contributing a PolicyDefinitionSource

```java
@Provides @IntoSet
static PolicyDefinitionSource myPolicies(MyPolicySource s) { return s; }
```

### Contributing a RolePolicyResolver

```java
@Provides @IntoSet
static RolePolicyResolver myResolver(MyRolePolicyResolver r) { return r; }
```

### Contributing an AuthorizationNarrower

```java
@Provides @IntoSet
static AuthorizationNarrower myNarrower(MyNarrower n) { return n; }
```

### Contributing a SecurityEventObserver

```java
@Provides @IntoSet
static SecurityEventObserver auditObserver(SecurityAuditObserver o) { return o; }
```

---

## Dependencies

- `dev.vertique:vertique-security-core` — all SPI interfaces and value types
- `dev.vertique:vertique-core` — `OrderedExtension`, exception roots, Dagger infrastructure

---

## Related ADRs

- ADR-0113: Federated Action and Policy Authorship for Framework Authorization — governs the `ActionRegistry`/`ActionContributor`/`PolicyDefinitionSource`/`RolePolicyResolver` model this module implements.
- ADR-0114: Enforcement-Layer Emission Ownership for Authorization Decisions — establishes that `Authorizer` is a pure decision function; `SecurityEventEmitter` and `SecurityEventsModule` are the canonical emission infrastructure for surface PEPs.
- ADR-0162: Identity Snapshot Reconstruction Trust Boundary and Mandatory HMAC — governs `IdentitySnapshotCodec`'s mandatory HMAC envelope, the keyset rotation model, and `DefaultIdentityReconstruction`/`PrivilegedIdentityModule`'s fail-closed, privileged-module reconstruction boundary this module implements.
- ADR-0163: Static `SecurityContexts` Assembly over an Injectable Factory — explains why `DefaultIdentityReconstruction` assembles contexts via the static `SecurityContexts` facade rather than an injected factory.
- ADR-0164: Identity-Snapshot Capture Site — SecurityContext Assembly (Ingress), Opt-In — governs `IdentitySnapshotCapture`'s ingress capture seam, the opt-in `IdentitySnapshotReconstructionModule` install, and why capture binds an `IdentitySnapshotContext` at `SecurityContext` assembly rather than in the durable encoder or per schedule path.
- ADR-0165: Deferred-Execution Provenance and Bounded SYSTEM-Minting — governs the `DeferredExecutionOrigin` provenance value, `IdentitySnapshotReconstructionInitializer`'s origin-gated bounded mint (Case 4) / fail-closed no-provenance path (Case 5), the `@BindsOptionalOf` `ServiceIdentityResolver(DeferredExecutionOrigin)` override seam, and why a per-handler SERVICE identity is minted via `SecurityContexts.unauthenticated(...)` rather than `system(...)`.
- ADR-0166: Identity Snapshot F5 Replay Defense (Row-Carrier Binding + Freshness) — establishes the durable-carrier binding and freshness envelope `IdentitySnapshotCodec` signs over and verifies, closing the cross-target/cross-dispatch replay gap in the reconstruction trust boundary.
- ADR-0167: Delegation Grant Lifecycle and Fail-Closed Validator — governs `InMemoryDelegationGrantValidator`'s expiry/scope checks and the fail-closed `GRANT_LOOKUP_FAILED` contract every `DelegationGrantValidator` implementation must uphold.
- ADR-0168: Authorization Narrowing Composition (Intersection, No-Widen, Agreement Invariant) — governs `NarrowingAuthorizer`/`NarrowingIntrospector`'s ordered fold, the no-widen guard, and the annotate-only introspection contract this module implements.
- ADR-0169: Mode 2 Live Authority Re-Resolution for Reconstructed Contexts — governs `ReconstructedAuthorityResolvingAuthorizer`'s per-request re-resolution, the evaluation-only context rebuild via `SecurityContexts.assembleReconstructed`, and the fail-closed `AUTHORITY_RESOLUTION_FAILED` contract.
- ADR-0170: Mode 3 Captured-Authority Reconstruction — Structural Never-Default — governs `CapturedAuthorityReconstructionModule`'s structural isolation from `PrivilegedIdentityModule`, the startup `CarriageRequirement#REQUIRED` allowlist validation, and `CapturedAuthorityActivation`'s emit-and-await audit contract.
- ADR-0171: Trust-Bearing InvocationOrigin Propagated from Ingress Boundaries — governs where `InvocationOrigin` is seeded (REST/service/Camel ingress) and how `DefaultIdentitySnapshotFactory`'s `originSummary()` reads it back.
- ADR-0172: Minimum-Assurance Policy Hooks and STEP_UP_REQUIRED — governs `AssuranceRequirementNarrower`'s three-condition step-up gate, the `identity.assurance` config shape, and the `@AssuranceClock`-qualified freshness-decay clock.
