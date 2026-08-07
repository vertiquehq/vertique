<!--
SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
SPDX-License-Identifier: EUPL-1.2
-->

# Security Runtime Module

> **Status:** Alpha
> **Package:** `dev.vertique.security.runtime` (identity-snapshot durable carriage + reconstruction), `dev.vertique.security.runtime.authz`, `dev.vertique.security.runtime.events`
> **Artifact:** `vertique-security-runtime`
> **Depends on:** security-core, core, context, config-core

Default in-process authorization engine, event fan-out wiring, and identity-snapshot durable-carriage/reconstruction pipeline for the Vertique security model. This module implements the API and SPI contracts declared in `dev.vertique:vertique-security-core` and supplies the Dagger modules that wire them. It carries no dependency on any transport module or Vert.x Web.

It also provides the opt-in authorization-narrowing composition and the reconstructed-context authority modes — each behind its own opt-in Dagger module, degrading to a passthrough of the base engine when not installed.

Config-backed policy and role-resolver contributions live in `dev.vertique:vertique-security-config`.

---

## When To Use It

Include `vertique-security-runtime` in any application that uses the authorization engine or needs to emit/observe security lifecycle events. Include both `SecurityAuthzModule` and `SecurityEventsModule` in the Dagger component:

```java
@Component(modules = {
    VertxModule.class,           // required: SecurityAuthzModule needs a Vertx binding
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

### Choosing the optional modules

| Install | To get |
|---|---|
| `SecurityAuthzModule` + `SecurityEventsModule` | The base engine and event fan-out — the normal minimum |
| `DelegationEnforcementModule` | Delegation-grant scope enforcement; **you must also bind a `DelegationGrantValidator`** |
| `AssuranceRequirementModule` | Minimum-assurance step-up gating driven by `identity.assurance` |
| `PrincipalAuthorityResolutionConfigModule` | Config-driven timeout for Mode-2 live authority re-resolution (no effect without a bound `PrincipalAuthorityResolver`) |
| `IdentitySnapshotCarriageModule` | Identity-snapshot capture, codec, and the durable encoder/decoder pair |
| `IdentitySnapshotReconstructionModule` | The full durable-carriage path (transitively includes carriage + privileged reconstruction) |
| `PrivilegedIdentityModule` | `IdentityReconstruction` on a framework infrastructure component; always pair with `IdentitySnapshotCarriageModule` |
| `CapturedAuthorityReconstructionModule` | Mode-3 captured authority; never installed transitively — installing it is the opt-in |

---

## Core Concepts

### Startup Ordering (FR-008)

`SecurityAuthzModule` guarantees the following construction order through the Dagger dependency graph:

1. **`ActionRegistry`** is built first from all contributed `ActionContributor`s. Duplicate actions (same canonical value from two contributors) fail fast with an `IllegalStateException` naming both sources.
2. The **policy catalogue** is built next. This is the single place it is merged and validated:
   - Every contributed `PolicyDefinitionSource` is validated against the registry polymorphically via `PolicyDefinitionSource.validateAgainst(ActionRegistry)` — no `instanceof` on concrete subtypes.
   - Duplicate policy names across sources fail fast, naming both offenders (FR-018).
   - All contributed `RolePolicyResolver`s are merged into a single composite resolver (union of results).
3. **`Authorizer`** and **`AuthorizationIntrospector`** are built last from that same shared resolved state.

Because both the authorizer and the introspector consume the same resolved state, their verdicts are guaranteed to agree by construction — the agreement invariant documented on `AuthorizationIntrospector` is a structural guarantee, not a behavioral promise.

### Fail-Closed Design

The default authorizer wraps the entire role→policy→action resolution in a try/catch. Any `RuntimeException` from a misbehaving resolver or policy source produces a deny decision with `AuthzReasonCodes.INTERNAL_AUTHZ_ERROR` rather than propagating. The engine never throws to the caller and never returns a failed `Future`. An unparseable action string and a `null` context on the convenience overload both take the same path.

The evaluation order and the reason code each step produces are specified in `dev.vertique:vertique-security-core` under Core Concepts.

### Event Fan-Out Isolation

`SecurityEventEmitter` fans out to all registered `SecurityEventObserver`s in parallel and waits for **every** observer to settle, regardless of individual outcomes. A synchronous throw, a returned `null` `Future`, and an asynchronous failure are each caught, logged at WARN, and counted as settled. One misbehaving observer cannot prevent others from receiving the event, and the returned `Future<Void>` always succeeds.

### Narrowing Composition & Reconstructed-Authority Modes

`SecurityAuthzModule` decorates the base engine in two independent, opt-in layers, outermost first:

```
Mode-2 live authority re-resolution   (present only when a PrincipalAuthorityResolver is bound)
  → narrowing fold                    (folds Set<AuthorizationNarrower>, always present)
    → base role/policy engine
```

With no narrowers installed and no `PrincipalAuthorityResolver` bound, the exposed `Authorizer`/`AuthorizationIntrospector` behave identically to the base engine — every opt-in layer is a pure decorator that degrades to a no-op passthrough when absent.

The narrowing layer folds the contributed `Set<AuthorizationNarrower>` — sorted once in `OrderedExtension#comparator()` order; a duplicate `(priority, orderKey)` pair fails startup naming both classes — over the base decision:

- **No-widen guard.** A narrower's candidate decision that turns a deny into a permit is discarded; the current deny is kept and a framework-integrity error is logged naming the offending narrower. A `null` candidate fails the fold with a `NullPointerException` naming the narrower.
- **Empty-set passthrough.** With zero contributed narrowers, both decorators pass every request straight through to the base engine unchanged.
- **Agreement invariant preserved.** Capability introspection folds `requirementFor` across the same ordered narrower set and collects **every** requirement it finds — not merely the first, since two independent narrowers (e.g. a delegation-scope narrower and an assurance-level narrower) may each gate the same action for unrelated reasons. Membership never changes, so `allowedActions(ctx) == capabilities(ctx).stream().map(ActionCapability::action)` continues to hold.
- **Annotate-only introspection.** A narrower's `requirementFor` is never a live pass/fail — it reports the same requirement for every actor regardless of whether that actor's concrete request would currently satisfy it; only `narrow` (at authorize-time) evaluates a specific request.
- **Reconstructed contexts are rejected outright.** The framework's exposed `AuthorizationIntrospector` binding throws `ReconstructedContextIntrospectionUnsupportedException` for both `allowedActions` and `capabilities` when `ctx.reconstruction()` is present, *before* consulting the base introspector or any narrower: a reconstructed context's current authority is resolved live at authorize-time (Mode 2), so a synchronous introspection answer computed from its currently-held claims could silently disagree with what `authorize()` would decide for the same actor.

The Mode-2 decorator is outermost and present only when an application binds a `PrincipalAuthorityResolver`. It triggers strictly off `SecurityContext.reconstruction()` — never the descriptive `identity.reconstructed=true` attribute — and never consults the resolver for a non-reconstructed or Mode-3 `CAPTURED` context.

---

## Key Classes

### SecurityAuthzModule

Abstract Dagger `@Module` (`dev.vertique.security.runtime.authz`). Declares four empty-by-default `@Multibinds` sets plus two optional bindings, and provides the entire engine stack. It requires a `Vertx` binding in the component (used to bound Mode-2 resolution).

**Multibinding declarations:**

| Set type | Populated by |
|----------|-------------|
| `Set<ActionContributor>` | Subsystem modules via `@Provides @IntoSet ActionContributor`; always contains at least the framework's built-in contributor |
| `Set<PolicyDefinitionSource>` | `AuthzConfigModule` and application modules via `@Provides @IntoSet PolicyDefinitionSource` |
| `Set<RolePolicyResolver>` | `AuthzConfigModule` and application modules via `@Provides @IntoSet RolePolicyResolver`; always contains at least one entry (the default empty-mapping resolver) |
| `Set<AuthorizationNarrower>` | Opt-in narrower modules (`DelegationEnforcementModule`, `AssuranceRequirementModule`) via `@Provides @IntoSet AuthorizationNarrower`; may be empty |

**Optional bindings:**

| Binding | Populated by |
|---------|-------------|
| `Optional<PrincipalAuthorityResolver>` (`@BindsOptionalOf`, no default) | An application `@Provides PrincipalAuthorityResolver` binding — presence **is** the Mode-2 opt-in |
| `Optional<PrincipalAuthorityResolutionConfig>` (`@BindsOptionalOf`, defaults to `PrincipalAuthorityResolutionConfig#defaults()`) | `PrincipalAuthorityResolutionConfigModule`; unused unless a `PrincipalAuthorityResolver` is also bound |

**Provided bindings:**

| Binding | Scope | Built from |
|---------|-------|-----------|
| `ActionRegistry` | `@Singleton` | All `ActionContributor`s |
| `Authorizer` | `@Singleton` | The base engine wrapped by the narrowing decorator; when a `PrincipalAuthorityResolver` is present, that resolver is first wrapped in the timeout decorator and the whole chain further wrapped by the Mode-2 authorizer |
| `AuthorizationIntrospector` | `@Singleton` | The base introspector wrapped by the narrowing decorator over the same `Set<AuthorizationNarrower>` |

The module also contributes a built-in `ActionContributor` `@IntoSet`, reserving `authz.action.list` and `authz.action.introspect` in every application:

| Action | Purpose |
|--------|---------|
| `authz.action.list` | Authorizes access to the action-registry introspection endpoint |
| `authz.action.introspect` | Authorizes access to the per-subject allowed-action introspection endpoint |

The `authz` subsystem prefix is reserved for framework use. Application subsystems must use a different leading segment.

---

### SecurityEventsModule

Abstract Dagger `@Module` (`dev.vertique.security.runtime.events`). Declares the single `@Multibinds Set<SecurityEventObserver>` for the framework. This is the one canonical declaration — surfaces (REST, WebSocket, services) and integrations (audit, OpenTelemetry, Micrometer) contribute observers via `@Provides @IntoSet SecurityEventObserver` without re-declaring the set.

`SecurityEventEmitter` is a `@Singleton` with an `@Inject` constructor — any component that includes `SecurityEventsModule` can inject it directly without an explicit `@Provides` for it.

---

### SecurityEventEmitter

`@Singleton` fan-out emitter (`dev.vertique.security.runtime.events`). Six typed `emit` overloads — one per event type in `dev.vertique:vertique-security-core` — each fan out to all registered `SecurityEventObserver`s in parallel:

```java
public Future<Void> emit(CredentialAcceptedEvent event);
public Future<Void> emit(CredentialRejectedEvent event);
public Future<Void> emit(AuthorizationDecisionEvent event);
public Future<Void> emit(ChannelLifecycleEvent event);
public Future<Void> emit(IdentitySnapshotDegradationEvent event);
public Future<Void> emit(CapturedAuthorityActivatedEvent event);
```

```java
// Inject and use in an enforcement layer (PEP)
@Inject SecurityEventEmitter emitter;

emitter.emit(new AuthorizationDecisionEvent(
    Instant.now(), correlationCtx, originOpt, request, decision));
```

The returned `Future<Void>` **always succeeds** — per-observer failures are caught and logged (AC-SE-6 isolation). The caller does not need to compose on it to protect the auth flow; composing on it only means "every observer has settled".

**Observer invocation ordering is not guaranteed.** Observers must be idempotent and self-ordered if ordering within a downstream pipeline matters.

---

### InMemoryPolicyDefinitionSource, InMemoryRolePolicyResolver

Programmatic reference implementations for applications that define authorization data in code rather than config.

- `InMemoryPolicyDefinitionSource` — backed by a `List<PolicyDefinition>` supplied at construction.
- `InMemoryRolePolicyResolver` — backed by a `Map<String, List<String>>` (role → policy names) supplied at construction. Also used as the default empty-mapping resolver contributed `@IntoSet` by `SecurityAuthzModule`.

---

### DelegationEnforcementNarrower, DelegationEnforcementModule

Framework-shipped `AuthorizationNarrower` (priority 100, requirement kind `"delegation"`) that bounds a delegated evaluation to the intersection of the actor's base authority and the delegation grant's scope.

**Non-delegated contexts pass through unchanged** — both `SecurityIdentity.subject()` and `SecurityIdentity.delegation()` must be present for this narrower to act.

**Framework-scheduled deferred work is not grant-backed delegation.** A context whose `DelegationContext#kind()` is `DelegationContext#DEFERRED_EXECUTION_KIND` **and** whose `SecurityContext#reconstruction()` is present records a framework-mediated scheduling relationship, not a grant a principal requested — its authority is the executing service's own, already resolved live by Mode 2. `narrow` recognizes that pair and passes `base` through unchanged, **without ever consulting `DelegationGrantValidator`**: validating the synthetic scheduling id as a grant would deny every reconstructed deferred action, which FR-ID-DG-006 explicitly excludes from grant-scope intersection. `requirementFor` reports `Optional.empty()` for the same contexts, keeping the narrow-deny/requirement-present agreement consistent. Both conditions are required — a live-authored context carrying a `deferred-execution` kind is still grant-validated, so the bypass cannot be induced by populating a delegation kind alone.

For a genuinely grant-backed delegated context:

| Base decision | Grant lookup result | Narrowed outcome |
|---|---|---|
| PERMIT | Grant permits (scope + direction match, not expired) and echoes the requested `grantId` | Permit survives |
| PERMIT | Grant denies (not found, expired, out of scope) | Denied, with the grant's own `DelegationReasonCodes` reason surfaced as the decision's `reasonCode` |
| PERMIT | Grant permits but echoes a different `grantId` | Denied with `GRANT_LOOKUP_FAILED` — a validator cannot authorize a grant other than the one asked about |
| PERMIT | `DelegationGrantValidator` returns a failed `Future` | Denied with `GRANT_LOOKUP_FAILED` |
| DENY | (not looked up) | Base deny survives — this narrower never adds authority |

**Scope mapping (frozen):** `scopeKind` is the requested action's `"<subsystem>.<resource>"` segments (parsed via `ActionRef.parse`); `scopeRef` is the request's `ResourceRef#id()`. An unparseable action string fails closed to `AuthzReasonCodes#INTERNAL_AUTHZ_ERROR`.

**Audit visibility:** every decision for a delegated context — permit or deny — carries `delegation.subject` (`"<PrincipalType>:<id>"`) and `delegation.authorityId` (the evaluated grant id) in `AuthorizationDecision#safeAttributes()`, never the subject's non-authoritative `attributes()`.

`requirementFor` is annotate-only and synchronous: it never calls `DelegationGrantValidator.validate`, reporting every action as gated by the active grant for a delegated actor without a concrete resource to evaluate against.

`DelegationEnforcementModule` is the opt-in Dagger module that contributes the narrower `@IntoSet AuthorizationNarrower`. **No default `DelegationGrantValidator` binding is supplied** — the installing application must also bind one, or the Dagger graph fails to compile; a default in-memory validator with no grants would silently deny every delegated call rather than surfacing a missing grant store as a startup error.

```java
@Provides @Singleton
static DelegationGrantValidator delegationGrantValidator() {
    return new InMemoryDelegationGrantValidator(myGrants);
}
```

---

### InMemoryDelegationGrantValidator

Framework-shipped, in-memory `DelegationGrantValidator` reference implementation (`dev.vertique.security.runtime`). Holds a fixed, immutable set of `DelegationGrant`s supplied at construction and ships the expiry and scope checks the framework default requires.

```java
public InMemoryDelegationGrantValidator(Collection<DelegationGrant> grants);
public InMemoryDelegationGrantValidator(Collection<DelegationGrant> grants, Clock clock); // deterministic under test
```

`validate(actor, subject, scopeKind, scopeRef, grantId)` maps `actor` to the grant's `grantee()` and `subject` to the grant's `grantor()` — mirroring `IdentityReconstruction.deferredExecution`'s actor/subject direction. Principal matching compares only `(type, id)`, never `attributes` (FR-ID-CA-012).

Evaluation order, first match wins: unknown id → `GRANT_NOT_FOUND`; direction or scope mismatch → `GRANT_OUT_OF_SCOPE`; `expiresAt` reached or passed → `GRANT_EXPIRED`; otherwise `GRANT_VALID`. Any exception during evaluation is caught and mapped to `GRANT_LOOKUP_FAILED`; `validate` never returns a failed `Future`.

---

### AssuranceRequirementNarrower, AssuranceRequirement, AssuranceRequirementConfig, AssuranceRequirementModule

Framework-shipped `AuthorizationNarrower` (priority 200, requirement kind `"ASSURANCE"`, runs after `DelegationEnforcementNarrower`) that denies an assurance-gated action with `AuthzReasonCodes#STEP_UP_REQUIRED` when the context's authentication does not meet the action's configured minimum-assurance requirement.

```java
public record AssuranceRequirement(int minProviderLevel, Duration maxAge)

public record AssuranceRequirementConfig(Map<ActionPattern, AssuranceRequirement> patterns) {
    public Optional<AssuranceRequirement> requirementFor(ActionRef action);
}
```

`patterns()` maps a parsed `ActionPattern` — the same action-pattern grammar `PolicyStatement` authors policies with: exact (`"cms.content.delete"`) or trailing-suffix wildcard (`"payments.refund.*"`, `"payments.*"`) — to the `AssuranceRequirement` gating a matching action, via `requirementFor(ActionRef)` (FR-ID-AR-003; not an exact-string lookup). An action matching no configured pattern is not assurance-gated at all.

**Overlapping patterns combine strictest-wins**: when more than one configured pattern matches the same action, the effective requirement takes the higher `minProviderLevel` and the shorter `maxAge` of every match — a wildcard can never weaken a more-specific sibling rule, or vice versa.

A blank or malformed pattern key, a negative `minProviderLevel`, and a missing or non-positive `maxAgeMs` each fail config parsing fast with `ConfigurationException`, never silently ungating a typo'd pattern. `minProviderLevel` defaults to `0` when omitted; `maxAgeMs` is required.

For a gated action with a base PERMIT, `narrow` denies with `STEP_UP_REQUIRED` on any of three conditions, each stamped with a distinct `assurance.reason` value in `AuthorizationDecision#safeAttributes()` (alongside `assurance.required.minLevel` and `assurance.required.maxAgeMs`, echoing the unmet requirement):

| Condition | `assurance.reason` |
|---|---|
| The context is a framework verified reconstruction (`SecurityContext.reconstruction()` present) — checked **first**, short-circuiting the checks below, since a reconstructed context carries no fresh, live authentication event of its own | `RECONSTRUCTED_NEEDS_STEP_UP` |
| No `AuthenticationAssurance` present, or its `providerLevel()` (default `0`) is below `minProviderLevel` | `BELOW_MIN` |
| `authTime()` absent, or older than `maxAge` measured against the narrower's injected `@AssuranceClock Clock` | `DECAYED` |

A base DENY passes through untouched, and an unparseable action string fails closed to `INTERNAL_AUTHZ_ERROR`.

`requirementFor` is annotate-only: it reports the configured requirement for every gated action regardless of whether the given context would currently satisfy it. For a reconstructed context this method is never reached through introspection at all — the framework's introspector throws `ReconstructedContextIntrospectionUnsupportedException` before consulting any narrower; a caller needing the `RECONSTRUCTED_NEEDS_STEP_UP` answer for such a context calls `Authorizer#authorize(...)` instead.

`AssuranceRequirementModule` is the opt-in Dagger module: it provides the parsed `AssuranceRequirementConfig`, contributes the narrower `@IntoSet AuthorizationNarrower`, and provides the `@AssuranceClock`-qualified `Clock` (`Clock.systemUTC()`) the narrower consumes — qualified so it coexists with any other `Clock` binding in the same component without a duplicate-binding error. With no configured `identity.assurance.actions` entries, the installed narrower is a no-op passthrough.

---

### InMemoryPrincipalAuthorityResolver

Framework-shipped, in-memory `PrincipalAuthorityResolver` reference implementation (`dev.vertique.security.runtime.authz`). Holds a fixed, immutable `PrincipalKey → AuthorizationClaims` map supplied at construction, plus a separate fixed set of keys seeded as **ambiguous**.

```java
public InMemoryPrincipalAuthorityResolver(
    Map<PrincipalKey, AuthorizationClaims> claimsByPrincipal, Set<PrincipalKey> ambiguousPrincipals);
public InMemoryPrincipalAuthorityResolver(Map<PrincipalKey, AuthorizationClaims> claimsByPrincipal); // no ambiguous keys
```

`resolve(key)` fails closed (a *failed* `Future`) for a key present in the ambiguous set, or absent from the seeded claims map entirely. A seeded key with no claims resolves normally to `AuthorizationClaims.empty()` (a resolvable principal with no current authority), never a failure. This type is not wired by any framework Dagger module — an application binds it (or a durable-store-backed implementation) explicitly to opt into Mode 2.

---

### Mode-2 authority re-resolution

When an application binds a `PrincipalAuthorityResolver`, `SecurityAuthzModule` installs the outermost `Authorizer` decorator implementing Mode 2 — re-resolving a verified reconstruction's **current** authority live, at authorization time, instead of trusting the snapshot's captured claims. Behavior turns strictly on `SecurityContext.reconstruction()`:

| `reconstruction()` | Behavior |
|---|---|
| Empty (normal, live-authored context) | Passes through to the wrapped inner authorizer unchanged; the resolver is never consulted and no decision is stamped |
| Present, mode `CAPTURED` | The context's own captured claims are trusted as current authority as-is; the resolver is never consulted and the context is never rebuilt |
| Present, mode `ATTRIBUTION_ONLY` | The context's own **actor**'s (`SecurityContext#identity()#actor()`) `PrincipalKey` — **never** `identity().subject()` — is resolved **exactly once** per `authorize(...)` call (never cached across dispatches); the resolved claims replace the authorization dimension in an **evaluation-only** rebuild; the original context and its holder are never mutated |

Per FR-ID-DG-006, v1 delegation resolves the intersection of the acting principal's own authority and any grant scope — subject-authority evaluation (impersonation) is out of v1 scope, so both `resumeAsPrincipal` (actor == the resumed principal) and `deferredExecution` (actor == the executing service) resolve the *acting* principal's own current authority, never the subject-of-record's.

**Fails closed, and bounded.** A resolver failure, timeout, or ambiguous result denies with `AuthzReasonCodes#AUTHORITY_RESOLUTION_FAILED` rather than falling back to the snapshot's captured claims or propagating the failure; the frozen snapshot's own `authorization()` is never a trust source in the live re-resolution path. On a resolver failure or timeout the inner narrowing chain (and every narrower folded into it) is never invoked at all — the deny is produced entirely at the outer layer.

Every decision this layer produces for a reconstructed context is stamped with `ReconstructedAuthorityMode.DECISION_ATTRIBUTE` carrying the mode name that produced it, in `AuthorizationDecision#safeAttributes()` — the source `AuthorizationDecisionEvent#authorityMode()` reads when present.

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
    public Duration resolutionTimeout();
}
```

`SecurityAuthzModule` wraps **every** installed `PrincipalAuthorityResolver` in this decorator before handing it to the Mode-2 authorizer — a resolver is bounded whether or not the application remembers to wrap it itself. Each call races the delegate's returned `Future` against a one-shot, non-recurring timer local to that single invocation, cancelled the instant the delegate settles. On timeout the returned `Future` fails (never a bare exception), which the Mode-2 layer maps to `AuthzReasonCodes#AUTHORITY_RESOLUTION_FAILED` — the same fail-closed path as any other resolver failure.

**The timeout does not cancel the delegate's underlying work** — it is a `Future`-race, not cooperative cancellation; a delegate backed by a blocking store call or in-flight network request keeps running after this decorator has given up on it. Operators whose resolver performs I/O should also configure a transport-level timeout on that I/O.

A non-positive `timeoutMs` (in the constructor or in config) is rejected: the constructor throws `IllegalArgumentException`, the config record throws `ConfigurationException`.

`PrincipalAuthorityResolutionConfigModule` is the opt-in companion that config-drives `resolutionTimeoutMs` from `identity.authz.resolutionTimeoutMs` instead of the hardcoded default. Installing it has no effect unless the application has also bound a `PrincipalAuthorityResolver` — with no resolver bound, the configured timeout is simply unused.

---

### IdentitySnapshotCarriageModule

Dagger module (`dev.vertique.security.runtime`) that wires the config-backed identity-snapshot keyset and freshness policy, and registers the snapshot's durable-carriage encoder/decoder pair into `dev.vertique:vertique-context`'s multibinding sets.

It reads the `identity.snapshot` config section and provides `IdentitySnapshotConfig`, the HMAC signer, the freshness policy, the snapshot codec, the `IdentitySnapshotFactory`, the `IdentitySnapshotCapture` ingress seam (constructed with the config's `captureEnabled` kill-switch, satisfying rest-security's `@BindsOptionalOf IdentitySnapshotCapture`), the `IdentitySnapshotDegradationPolicy`, and contributes the durable encoder/decoder pair `@IntoSet` into the `DurableContextMetadataEncoder`/`Decoder` sets.

Applications wiring identity-snapshot durable carriage must include this module — without it, the encoder/decoder pair is never registered and the carriage path is inert.

`IdentitySnapshotFactory` is provided by an unconditional `@Provides` binding with no `@BindsOptionalOf` seam: an application `@Provides` of that type is rejected at compile time as a duplicate Dagger binding.

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

| Key | Default | Constraint |
|---|---|---|
| `identity.snapshot.captureEnabled` | `true` | — |
| `identity.snapshot.onDegradation` | `FAIL` | `FAIL` or `CONTINUE_WITHOUT_IDENTITY` |
| `identity.snapshot.hmacKeys` | — | **Required**; signing/verification cannot proceed without at least an active key |
| `identity.snapshot.hmacKeys.active.keyId` | — | Required, non-blank |
| `identity.snapshot.hmacKeys.active.secretRef` | — | Required, ≥ 32 UTF-8 bytes; never logged or re-serialized |
| `identity.snapshot.hmacKeys.previous[]` | empty | Each `keyId` must be distinct from the active key's and from every other `previous` key; each `secretRef` ≥ 32 UTF-8 bytes |
| `identity.snapshot.maxCarrierLifetimeMs` | unset (unbounded) | Required when any `carriageRequirements` entry is `REQUIRED` |
| `identity.snapshot.maxSnapshotLifetimeMs` | unset (unbounded) | Required when any `carriageRequirements` entry is `REQUIRED` |
| `identity.snapshot.clockSkewMs` | `30000` | — |
| `identity.snapshot.carriageRequirements.<targetKind>` | `OPTIONAL` for an unlisted kind | `REQUIRED`, `OPTIONAL`, or `FORBIDDEN` |
| `identity.snapshot.capturedAuthority.allowedTargetKinds[]` | empty | Each entry non-blank; each must resolve to `REQUIRED` in `carriageRequirements` |

Every violation above throws `ConfigurationException` at provider time — including the **`REQUIRED` forces finite budgets** rule, which exists because a never-expiring `REQUIRED` snapshot would be a standing bearer credential.

`IdentitySnapshotDegradationPolicy` governs what happens when a deferred execution's carried snapshot is present but fails verification:

| Value | Behavior |
|-------|----------|
| `FAIL` (default) | Fail the deferred dispatch outright when the carried snapshot cannot be verified |
| `CONTINUE_WITHOUT_IDENTITY` | Emit `IdentitySnapshotDegradationEvent` and let the dispatch proceed with no verified subject |

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

**Keyset rotation.** Exactly one `active` signing key plus zero or more `previous` (verification-only) keys, indexed by `keyId`. A snapshot signed under a key since demoted to `previous` still verifies until the operator retires that key. The MAC algorithm named on a snapshot is checked against a server-side allowlist — `HmacSHA256`, `HmacSHA384`, `HmacSHA512` — before any MAC is constructed, so an algorithm-confusion downgrade is rejected fail-closed.

---

### IdentitySnapshotCapture

Producer-side gate (`dev.vertique.security.runtime`) that captures the currently authenticated identity and binds it into the `ContextHolder`, honored only when the global `identity.snapshot.captureEnabled` kill-switch is on. Provided as a `@Provides @Singleton` binding by `IdentitySnapshotCarriageModule` — **not** an `@Inject` constructor — so rest-security's `@BindsOptionalOf IdentitySnapshotCapture` resolves to `Optional.empty()` unless carriage is installed.

```java
public final class IdentitySnapshotCapture {
    public IdentitySnapshotCapture(ContextHolder holder, IdentitySnapshotFactory factory, boolean captureEnabled);
    public ContextHolder.Scope captureFrom(SecurityContext live);
}
```

`captureFrom(SecurityContext live)` is the ingress seam and the type's sole public method. It evaluates the `captureEnabled` kill-switch **first** — when disabled it returns a shared no-op scope *without* consulting the factory, so the no-capture hot path pays nothing. An eligibility gate then checks the live context: an anonymous actor (`PrincipalType.ANONYMOUS`) or a `NONE`-kind primary authentication method also short-circuits to the no-op scope without consulting the factory, since an unauthenticated identity carries no attribution worth persisting. Otherwise it captures the credential-free content, binds it, and returns the bind `ContextHolder.Scope` so the caller unwinds it with the request lifecycle.

**Capture is credential-free by enforcement** (FR-ID-CA-003 / NFR-ID2-003), and the projection is **dimension-aware**: subject, client, and claim `attributes` maps are projected to empty unconditionally; the actor map retains only `system.reason`, and only when the actor is `SYSTEM`-typed and the value is a bounded (≤ 256 chars) `String`. The bound *limits* — it does not *prevent* — what trusted in-process code can place under that key; `SystemIdentities` is the sanctioned source. Structural fields (`type`, `id`, claim components, delegation kind and authority id) are unaffected. Durable principal identity is the trust-domain-unique `(PrincipalType, id)` tuple, never attributes (FR-ID-CA-012).

`authenticatedAt` derivation precedence: the assurance `authTime` when present; else the first (decisive) `AuthenticationEvidence`'s `verifiedAt`; else — for a reconstructed live context — the `identity.reconstructed.authenticatedAt` marker attribute (so a chained re-capture preserves the original authentication instant); else the capture instant (synthetic system/unauthenticated/anonymous contexts only).

**Opt-in install pattern.** Schedule-time capture and execute-side reconstruction are not framework-default-on — they require a mandatory HMAC key. An application activates the whole path by including `IdentitySnapshotReconstructionModule` (which transitively pulls `IdentitySnapshotCarriageModule` and `PrivilegedIdentityModule`) and supplying the `identity.snapshot.hmacKeys` config section. With that in place, capture binds a snapshot context at REST ingress, the existing durable-context propagation carries it across the `DELAYED_JOB` / `OUTBOX` / `OUTBOX_SERVICE` boundaries, and the receive-side initializer reconstructs it. Without the include, rest-security's `Optional<IdentitySnapshotCapture>` is empty and the REST hot path is inert.

---

### PrivilegedIdentityModule

Dagger `@Module` (`dev.vertique.security.runtime`) that provides `IdentityReconstruction` to framework infrastructure components only.

Kept deliberately out of the general `SecurityEventsModule`/`SecurityAuthzModule` wiring: an application that includes this module is doing so visibly, so accidental general-purpose injection of the reconstruction service is caught at code review by an unexpected module in the component's module list, rather than being silently available everywhere. Install it only on framework infrastructure components — job execution, inbox/outbox, workflow resume — never on general request-handling components.

Must always be installed alongside `IdentitySnapshotCarriageModule` — it wires `IdentityReconstruction` to the shared, config-backed codec singleton that module provides, and has no codec of its own.

---

### IdentitySnapshotReconstructionModule, ServiceIdentityResolver

`IdentitySnapshotReconstructionModule` is the receive-side wiring that reconstructs a `SecurityContext` from a durably-carried snapshot at every service-dispatch receive (delayed-job execution, inbox handler dispatch, and any other event-bus service dispatch). It includes `IdentitySnapshotCarriageModule`, `PrivilegedIdentityModule`, and the context runtime module, contributes an `InboundContextInitializer` into the framework's multibinding set, and declares `ServiceIdentityResolver` as an overridable `@BindsOptionalOf` seam.

Applications wiring identity-snapshot durable carriage into deferred execution must include this module (and supply the required `identity.snapshot.hmacKeys` config) alongside their delayed-job/inbox-outbox module and dispatch module.

**Observable outcomes.** For each inbound dispatch the initializer binds one of the following, resolving the durable-target kind from the dispatch's `DeferredExecutionOrigin#kind()` when present (else the boundary string) and looking up its `CarriageRequirement`:

| Situation | `SecurityContext` bound? | `SnapshotDegradationMarker` bound? |
|---|---|---|
| A live `SecurityContext` is already bound | unchanged (idempotent no-op) | no |
| Any snapshot present on a `FORBIDDEN` target kind | **no** — refused before verification | yes (`EXPECTED_ABSENT`) |
| Verified snapshot, target not `FORBIDDEN` | yes — reconstructed identity | no |
| Present-but-unverifiable snapshot **and** a deferred-execution origin | yes — service-only context (no subject) | yes (typed reason) |
| Present-but-unverifiable snapshot, **no** origin | **no** | yes (typed reason) |
| No snapshot **and** a deferred-execution origin | yes — service context for the origin-resolved identity | only when the target kind is `REQUIRED` |
| No snapshot **and** no origin | **no** | only when the target kind is `REQUIRED` |

The deferred-execution-origin provenance gate is the security boundary that keeps the bounded mint from privileging an ordinary context-empty (or unproven-deferred) dispatch: an attacker who writes a garbage, unverifiable durable row with no origin marker must not end up better off under `CONTINUE_WITHOUT_IDENTITY` than by writing nothing at all. When nothing is bound, `@RequiresAction`-gated service operations are treated as `AUTHENTICATION_REQUIRED`; **ungated operations run without a bound context, exactly as if the reconstruction module were not installed.**

`ServiceIdentityResolver` is the functional seam that resolves the executing identity used as the actor when a service-only or reconstructed context is minted:

```java
@FunctionalInterface
public interface ServiceIdentityResolver {
    SecurityIdentity resolve(DeferredExecutionOrigin origin);
}
```

The `DeferredExecutionOrigin` carries the provenance `kind` (`delayed-job` / `cron` / `outbox-relay`) and a `reference` — the specific job-type / cron-name / event-type — that becomes the resolved identity's audit reason. The seam is bound via `@BindsOptionalOf` with **no** concrete framework binding: absent an application resolver the built-in default maps `origin.reference()` to `SystemIdentities.scheduledJob(...)`; an application overrides by binding its own `@Provides ServiceIdentityResolver` (present ⇒ used, no duplicate-binding collision). The mint honors the resolved actor's `PrincipalType` — a `SYSTEM` actor via `SecurityContexts.system(...)`, a `SERVICE` actor via `SecurityContexts.unauthenticated(...)` (stamping `custom("system")` on a `SERVICE` actor would misattribute it as system-acting), any other actor type rejected.

---

### CapturedAuthorityReconstructionModule, CapturedAuthorityActivation, CapturedAuthorityConfig

Opt-in Dagger module and activation seam for Mode 3 — captured-authority reconstruction (`dev.vertique.security.runtime`).

**Structural never-default, and the audited seam is structurally unavoidable.** This module is deliberately **not** included by `PrivilegedIdentityModule`, `IdentitySnapshotReconstructionModule`, or any other general wiring, and there is no `@BindsOptionalOf` fallback for `CapturedAuthorityReconstruction` anywhere in the framework — absent this module the type is simply unbound, so an accidental dependency on it fails the Dagger graph rather than silently resolving to a degraded implementation.

The module goes one step further: it exposes **only** `CapturedAuthorityActivation`. There is no binding anywhere in the framework for the raw `CapturedAuthorityReconstruction` seam, so no injectable binding exists that could bypass the activation-audit event — the module's provider constructs the reconstruction collaborator directly and hands it to `CapturedAuthorityActivation`'s constructor, which is deliberately **not** an `@Inject` constructor. Must always be installed alongside `IdentitySnapshotCarriageModule` (for the shared codec and config) and `SecurityEventsModule` (for the emitter).

**Startup `REQUIRED` validation.** For every target kind named in `CapturedAuthorityConfig#allowedTargetKinds()`, the provider checks that `IdentitySnapshotConfig#carriageRequirementFor(kind)` resolves to `CarriageRequirement#REQUIRED` — a captured-authority target that is merely `OPTIONAL`/`FORBIDDEN` would be a standing bearer-credential hole. A violation throws `ConfigurationException` naming the offending kind at startup, not at first use.

```yaml
identity:
  snapshot:
    carriageRequirements:
      outbox-relay: REQUIRED
    capturedAuthority:
      allowedTargetKinds:
        - outbox-relay
```

`CapturedAuthorityActivation` is the sanctioned Mode-3 entry point, bound `@Provides @Singleton` by this module:

```java
public Future<SecurityContext> activateResume(IdentitySnapshot snapshot, DurableCarrierDescriptor expectedCarrier);
public Future<SecurityContext> activateDeferred(
    SecurityIdentity executingServiceIdentity, IdentitySnapshot snapshot, DurableCarrierDescriptor expectedCarrier);
```

Both methods invoke the matching `CapturedAuthorityReconstruction` method — which re-verifies the snapshot's integrity **and current freshness**, then confirms the carrier and the allowlist, all fail-closed before minting anything — then build and emit a `CapturedAuthorityActivatedEvent` and resolve only once that emission completes.

The emitted event carries the reconstructed `SecurityContext`'s facts **uncollapsed** — its whole `SecurityIdentity` (actor, subject-of-record, delegation, client) and its `AuthenticationState` — plus the snapshot's whole signed `SnapshotCarrierBinding`, so an audit consumer can attribute the activation rather than receiving one pre-merged principal. `activateResume` stamps `Mode.RESUME` on it and `activateDeferred` stamps `Mode.DEFERRED`; the mode is a typed, non-null component, not an unchecked `safeAttributes` marker. Each call mints a fresh `activationId`, so two activations of the same durable row remain distinguishable as audit source events.

This is an **emission-ordering** guarantee, not a durable or acknowledged-delivery one: since `SecurityEventEmitter.emit` always succeeds and its future resolving means every registered `SecurityEventObserver` has been invoked and settled, a caller awaiting either method is guaranteed the audit event was emitted to and settled by every observer before it observes the reconstructed `SecurityContext` — but per the emitter's per-observer isolation, an observer that fails (including one that fails to durably persist the event) is caught, logged, and counted as settled the same as one that succeeds. There is no fail-closed acknowledgement channel back to the caller.

A reconstruction failure surfaces here as a **failed** `Future`, never a thrown exception. The event's `correlation` is always `CorrelationContext.unbound()` and its `origin` mirrors the reconstructed context's `origin()` (always empty for a Mode-3 reconstruction).

---

## Extension Points

### Contributing an ActionContributor

```java
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

### Opting into Mode-2 live authority re-resolution

Binding the resolver *is* the opt-in — no separate module or flag.

```java
@Provides @Singleton
static PrincipalAuthorityResolver principalAuthorityResolver(MyStoreBackedResolver r) { return r; }
```

### Overriding the deferred-execution service identity

```java
@Provides @Singleton
static ServiceIdentityResolver serviceIdentityResolver() {
    return origin -> SystemIdentities.internal("job:" + origin.reference());
}
```

---

## Exceptions

| Exception | Thrown when |
|---|---|
| `IdentitySnapshotCodecException` | Encode/decode failure, unknown-newer schema version, HMAC mismatch, or a freshness violation — carries a typed `SnapshotDegradationReason` |
| `SnapshotHmacException` | `UNKNOWN_KEY` (keyset populated but this key id is absent), `KEY_UNAVAILABLE` (no key material configured at all), or `UNSUPPORTED_ALGORITHM` (MAC algorithm outside the server allowlist). Each is a distinct fail-closed condition from a simple tag mismatch, and callers must not treat the exception as "not verified", only as "verification could not be determined" |
| `ConfigurationException` | Any `identity.snapshot`, `identity.assurance`, or `identity.authz` constraint violation listed above, at provider time |
| `IllegalStateException` | Duplicate action, duplicate policy name, or duplicate `(priority, orderKey)` narrower pair at startup — each naming both offenders |

`IdentityReconstructionException` and `ReconstructedContextIntrospectionUnsupportedException` are declared in `dev.vertique:vertique-security-core`.

---

## Dependencies

- `dev.vertique:vertique-security-core` — all SPI interfaces and value types
- `dev.vertique:vertique-core` — `OrderedExtension`, `ContextHolder`, durable-context types, the `ConfigParser` seam, exception roots
- `dev.vertique:vertique-context` — the durable-context encoder/decoder multibinding sets the carriage module contributes into
- `dev.vertique:vertique-config-core` — config parsing for the `identity.*` sections
- `io.vertx:vertx-core` — `Future`, and the timer backing the Mode-2 resolution timeout
- `com.google.dagger:dagger` — the `@Module`/`@Multibinds`/`@BindsOptionalOf` wiring this module ships
