<!--
SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
SPDX-License-Identifier: EUPL-1.2
-->

# Security Core Module

> **Status:** Alpha
> **Package:** `dev.vertique.security` (identity/auth/context), `dev.vertique.security.authz` (authorization model), `dev.vertique.security.events` (event records + observer SPI), `dev.vertique.security.origin` (network origin), `dev.vertique.security.resolver` (identity resolver SPI), `dev.vertique.security.channel` (channel SPI), `dev.vertique.security.verification` (verification sources)
> **Artifact:** `vertique-security-core`
> **Depends on:** core (ContextValue, correlation, extension ordering, exception roots), Vert.x core (Future), Jakarta Annotations

Pure API, SPI, and value-type module for the Vertique security model. This module carries the typed identity/authentication/authorization contracts, the action-policy model, all event record types, and the SPI interfaces that the runtime module implements and application code extends. It contains no pipeline execution, no Dagger wiring, and no Vert.x Web dependency — only interfaces, records, and annotations.

The runtime implementation lives in `dev.vertique:vertique-security-runtime`. Config-backed authorization adapters live in `dev.vertique:vertique-security-config`.

---

## When To Use It

Include `vertique-security-core` when a module needs to:

- Read the typed security model from a `SecurityContext` (identity, authentication state, authorization claims, network origin)
- Implement a custom `SecurityIdentityResolver`, `Authorizer`, `PolicyDefinitionSource`, `RolePolicyResolver`, `ActionContributor`, or `ChannelIdentityManager`
- Declare or reference an `ActionRef` for `@RequiresAction` enforcement
- Observe security lifecycle events by implementing `SecurityEventObserver`
- Embed a `VerificationSource` discriminator in a credential-accepted event

Add `vertique-security-runtime` to get the default engine wiring and the `SecurityEventEmitter`.

---

## Package Layout

| Package | Contents |
|---------|----------|
| `dev.vertique.security` | `SecurityContext`, `SecurityIdentity`, `PrincipalRef`, `PrincipalType`, `AuthenticationState`, `AuthMethod`, `AuthMethodKind`, `AuthenticationEvidence`, `AuthenticationAssurance`, `DelegationContext`, `ClientRef`, `TokenAttributes`, `SecurityContextSnapshot`, `DefaultAuthMethod`, `SystemIdentities`, `SecurityContexts`, `IdentitySnapshot`, `IdentitySnapshotContent`, `IdentitySnapshotFactory`, `IdentityReconstruction`, `IdentityReconstructionException`, `SnapshotCarrierBinding`, `SnapshotIntegrity`, `DelegationSummary`, `CarriageRequirement`, `SnapshotDegradationMarker`, `SnapshotDegradationReason`, `DelegationGrant`, `DelegationGrantValidator`, `DelegationGrantDecision`, `DelegationReasonCodes`, `ReconstructionMarker`, `CapturedAuthorityReconstruction` |
| `dev.vertique.security.authz` | `ActionRef`, `ActionPattern`, `ActionDefinition`, `ActionRegistry`, `ActionContributor`, `Authorizer`, `AuthorizationIntrospector`, `AuthorizationDecision`, `AuthorizationRequest`, `AuthorizationPolicy`, `AuthorizationClaims`, `AuthorityClaim`, `AuthorityKind`, `Effect`, `PolicyDefinition`, `PolicyDefinitionSource`, `PolicyStatement`, `RolePolicyResolver`, `ResourceRef`, `AuthzReasonCodes`, `RequiresAction`, `AuthorizationNarrower`, `RequirementDescriptor`, `ActionCapability`, `InvocationOrigin`, `PrincipalAuthorityResolver`, `PrincipalKey`, `ReconstructedAuthorityMode` |
| `dev.vertique.security.events` | `SecurityEventObserver`, `CredentialAcceptedEvent`, `CredentialRejectedEvent`, `AuthorizationDecisionEvent`, `ChannelLifecycleEvent` (sealed), `ChannelOpenedEvent`, `ChannelIdentityRefreshedEvent`, `ChannelClosedEvent`, `IdentitySnapshotDegradationEvent`, `CapturedAuthorityActivatedEvent` |
| `dev.vertique.security.origin` | `RequestOrigin`, `TlsFacts` |
| `dev.vertique.security.resolver` | `SecurityIdentityResolver`, `SecurityIdentityResolutionContext`, `AuthenticationEvidenceCollector`, `IdentityResolutionError`, `IdentityResolutionException` |
| `dev.vertique.security.channel` | `ChannelIdentityManager`, `ChannelBinding` |
| `dev.vertique.security.verification` | `VerificationSource` (sealed), plus the seven permit records |

---

## Core Concepts

### Identity Model

A `SecurityContext` is the request-scoped container for all security facts. It extends `ContextValue`, so it is stored in the Vert.x `ContextLocal` and accessible throughout the request lifecycle. It has four pillars:

- **`identity()`** — who is making the request: `SecurityIdentity` with a mandatory `actor` (`PrincipalRef`), optional `subject` (for PSD2/delegation), optional `delegation` context, and optional OAuth `client` reference.
- **`authentication()`** — how identity was established: `AuthenticationState` carrying the primary `AuthMethod`, ordered evidence list (`AuthenticationEvidence`), assurance level (`AuthenticationAssurance`), and safe token metadata.
- **`authorization()`** — what authority claims the principal holds: `AuthorizationClaims` keyed by `AuthorityKind` (ROLE, SCOPE, PERMISSION, etc.).
- **`origin()`** — the network envelope captured before auth: peer address, forwarded chain, client IP after trusted-proxy policy, scheme, host, and optional TLS facts.

### Action-Policy Authorization Model

The authorization model is a three-layer role→policy→action resolution governed by ADR-0113:

1. **Actions** — stable, three-segment canonical identifiers (`<subsystem>.<resource>.<verb>`, e.g. `cms.content.read`). Contributed by subsystems via `ActionContributor`; the `ActionRegistry` is the authoritative catalogue.
2. **Policies** — named sets of `PolicyStatement`s, each granting `Effect.ALLOW` on a set of `ActionPattern`s (exact or trailing-wildcard). Contributed via `PolicyDefinitionSource`.
3. **Role→Policy mapping** — `RolePolicyResolver` maps the principal's `ROLE` claims to applicable policy names. The authorization engine unions all resolvers' outputs.
4. **Narrowing (opt-in)** — an ordered chain of `AuthorizationNarrower`s composes on top of the base engine's permit/deny, applying additional narrower-only constraints (e.g. delegation-grant scope, minimum-assurance step-up) without touching role/policy resolution. See ADR-0168.

The `Authorizer` evaluates these layers in order: role presence → policy name resolution → policy existence → `ALLOW` statement match. Every outcome is a succeeded `Future<AuthorizationDecision>` — the engine is a pure decision function and never emits events (see ADR-0114). Installed `AuthorizationNarrower`s run after this base decision, composed by the runtime's `NarrowingAuthorizer`/`NarrowingIntrospector`, and may only narrow it further — never widen a deny into a permit.

---

## Key Classes

### SecurityContext

Interface extending `ContextValue`. The four pillars (`identity()`, `authentication()`, `authorization()`, `origin()`) are described above. The `snapshot()` default method pins the current facts into an immutable `SecurityContextSnapshot` — callers should capture a snapshot before crossing a thread boundary to avoid a later context rebind replacing the fact records from under off-thread projection.

A second default method, `reconstruction()`, returns the typed, unforgeable `ReconstructionMarker` present only on a framework verified-reconstruction; every normal, live-authored context returns `Optional.empty()`. See `ReconstructedAuthorityMode`/`ReconstructionMarker` below.

```java
// Read-only use in a request handler
SecurityContext ctx = /* from ContextHolder */;
SecurityIdentity identity = ctx.identity();
String userId = identity.actor().id();

// Snapshot before crossing a thread boundary (e.g. for audit)
SecurityContextSnapshot snap = ctx.snapshot();
```

---

### SecurityIdentity

Immutable aggregate of identity information. The `actor` is always non-null; `subject`, `delegation`, and `client` are `Optional` and express delegation patterns.

```java
public record SecurityIdentity(
    PrincipalRef actor,
    Optional<PrincipalRef> subject,
    Optional<DelegationContext> delegation,
    Optional<ClientRef> client)
```

Typed factory methods:

| Factory | Actor type enforced |
|---------|---------------------|
| `SecurityIdentity.user(PrincipalRef)` | `PrincipalType.USER` |
| `SecurityIdentity.service(PrincipalRef)` | `PrincipalType.SERVICE` |
| `SecurityIdentity.anonymous()` | `PrincipalType.ANONYMOUS`, id `"anonymous"` |

`SYSTEM` identities must be created via `SystemIdentities` — there is intentionally no `system(...)` factory on this class.

---

### SecurityContexts

Static, transport-neutral factory for assembling `SecurityContext` instances outside any REST middleware — the general-assembly API for jobs, cron triggers, message handlers, and any other framework or application code that needs a real `SecurityContext` without going through the REST identity-resolution pipeline. Mirrors `SystemIdentities`: a plain static factory, not a Dagger-provided singleton — see ADR-0163 for why.

```java
public final class SecurityContexts {
    public static SecurityContext system(SecurityIdentity serviceIdentity);
    public static SecurityContext unauthenticated(SecurityIdentity identity);
    public static SecurityContext assemble(
        SecurityIdentity identity,
        AuthenticationState auth,
        AuthorizationClaims claims,
        Optional<RequestOrigin> origin);
}
```

| Factory | Use case | Authentication dimension |
|---------|----------|---------------------------|
| `system(SecurityIdentity)` | Framework-scheduled work with no external trigger and no asserted external principal (cron, delayed jobs) | `DefaultAuthMethod.custom("system")`, empty evidence |
| `unauthenticated(SecurityIdentity)` | Non-HTTP ingress with an asserted principal but no verified credential (file/queue drops) | `DefaultAuthMethod.none()`, empty evidence, no assurance, no tokens |
| `assemble(identity, auth, claims, origin)` | General-purpose assembly from already-known identity/authentication/authorization facts | caller-supplied |

**Invariants & Gotchas:**

- None of these methods emit `SecurityEventObserver` events — a static method cannot invoke an injected emitter by construction, so consuming flows decide whether and how to audit.
- Snapshot capture (§`IdentitySnapshotFactory` below) is deliberately **not** on this class — it lives on a separate framework-internal capture interface (fixed implementation for V1) provided only where snapshot capture is wired, so the general-assembly API and the snapshot-capture seam remain different types with different Dagger placement.
- The concrete type `SecurityContexts` builds — `DefaultSecurityContext` — is package-private; callers use `SecurityContexts` and the `SecurityContext` interface, never the concrete record directly.
- `SecurityContexts.assemble(...)` takes `authorization()` verbatim from its `claims` argument — it makes no authority decision of its own. Reconstruction (`IdentityReconstruction`, below) exploits this by passing `AuthorizationClaims.empty()`, so a reconstructed context carries no frozen authority; a snapshot's claims stay attribution-only (FR-ID-CA-010).

---

### PrincipalRef

Immutable reference to an authenticated or delegated principal.

```java
public record PrincipalRef(PrincipalType type, String id, Map<String, Object> attributes)
```

**Construction rules:** `type` and `id` are required (non-null, non-blank). A null `attributes` map is treated as empty. The attributes map is defensively copied at construction.

**Durable capture caveat:** `attributes` (and the analogous attribute maps on `ClientRef` and `AuthorityClaim`) is request-scoped provenance only and is non-authoritative for identity — it does not survive identity-snapshot durable capture. The capture interface (`IdentitySnapshotFactory`, fixed framework implementation `DefaultIdentitySnapshotFactory` in `dev.vertique:vertique-security-runtime`) projects subject/client/claim attribute maps to empty unconditionally; the sole survivor is a bounded (≤ 256 chars) `system.reason` string on a `SYSTEM`-typed actor. Durable principal identity is the trust-domain-unique `(PrincipalType, id)` tuple (PRD identity-002 **FR-ID-CA-012**): applications using tenant-, issuer-, or realm-local identifiers must namespace or otherwise qualify `id`/`clientId` before constructing the ref — e.g. `new PrincipalRef(PrincipalType.USER, "urn:example:tenant:acme:user:01J…", Map.of("displayName", "alice"))` — because the framework treats ids as opaque and never reconstructs identity scope from attributes.

`PrincipalType` enum: `USER`, `SERVICE`, `SYSTEM`, `ANONYMOUS`.

---

### DelegationGrant, DelegationGrantDecision, DelegationReasonCodes

Immutable, audit-safe record of an explicit authority delegation from a `grantor` principal to a `grantee` principal over a bounded scope, and the outcome of evaluating one against an (actor, subject, scope) triple.

```java
public record DelegationGrant(
    String grantId, PrincipalRef grantor, PrincipalRef grantee,
    String scopeKind, String scopeRef, Instant expiresAt, String evidenceRef)

public record DelegationGrantDecision(
    boolean permitted, String reasonCode, String grantId, Optional<Instant> expiryUsed)
```

A grant answers "who may act, on whose behalf, over what, until when" — never "why", and never carries the evidence that established it: `evidenceRef` is a **reference** (e.g. a consent-record id) pointing at evidence held elsewhere, never the evidence content or credential material — the same credential-free discipline `IdentitySnapshot` enforces for durable identity carriage. Grant-backed delegation is carried on the existing `DelegationContext#authorityId()` — the grant id, and only the grant id; no parallel delegation representation is introduced.

`DelegationGrantDecision#grantId()` always echoes the grant id evaluated, even on a deny (`GRANT_NOT_FOUND`, `GRANT_LOOKUP_FAILED`), so callers and audit output can correlate a decision to the grant reference. `expiryUsed()` carries the checked `expiresAt` when a grant was found, `Optional.empty()` otherwise. Evaluating whether a grant currently authorizes a triple is the job of `DelegationGrantValidator` (below), not this record — a `DelegationGrant` is a pure value.

`DelegationReasonCodes` is the frozen, non-instantiable vocabulary for `DelegationGrantDecision#reasonCode()`:

| Constant | Meaning |
|----------|---------|
| `GRANT_VALID` | The grant currently authorizes the evaluated (actor, subject, scope) triple |
| `GRANT_NOT_FOUND` | No grant with the requested id exists |
| `GRANT_EXPIRED` | A grant was found but its `expiresAt` has already passed |
| `GRANT_OUT_OF_SCOPE` | A grant was found but doesn't match the requested actor/subject direction or `scopeKind`/`scopeRef` |
| `GRANT_LOOKUP_FAILED` | The grant lookup itself failed or timed out — fail-closed, never surfaced as a failed `Future` |

---

### ActionRef

Immutable, canonical action identifier. Three lowercase segments — `subsystem`, `resource`, `verb` — each matching `^[a-z][a-z0-9]*$`. The canonical string form is `"<subsystem>.<resource>.<verb>"`.

```java
// Construction
ActionRef read = ActionRef.of("cms", "content", "read");
ActionRef parsed = ActionRef.parse("cms.content.read");

// String form
String canonical = read.value(); // "cms.content.read"
```

#### Invariants & Gotchas

- The compact constructor enforces the segment grammar. Uppercase characters, leading digits, empty segments, and a segment count other than three all fail at construction.
- `ActionRef.parse(String)` uses `split("\\.", -1)` with limit `-1` so trailing dots (`"a.b."`) and interior double-dots (`"a..b"`) are rejected by the segment count or grammar check rather than being silently trimmed.
- The `authz` subsystem prefix is reserved for framework built-in actions. Application subsystems must use a different leading segment.

---

### ActionPattern

Immutable pattern matched against an `ActionRef` during policy evaluation.

Two shapes:
- **Exact canonical** — three valid `ActionRef` segments, e.g. `"cms.content.read"`. Matches the identical action only.
- **Trailing-suffix wildcard** — one or more valid leading segments followed by a terminal `"*"`, e.g. `"cms.content.*"` (any verb) or `"cms.*"` (any resource and verb).

A bare `"*"` with no leading segment is rejected — it would be a blanket allow.

```java
ActionPattern exact    = new ActionPattern("cms.content.read");
ActionPattern wildcard = new ActionPattern("cms.content.*");

exact.isWildcard();           // false
wildcard.isWildcard();        // true
wildcard.matches(read);       // true  (read = ActionRef.of("cms","content","read"))
```

---

### AuthorizationRequest

Immutable request record passed to the authorization engine.

```java
public record AuthorizationRequest(
    SecurityContext securityContext,
    String action,
    ResourceRef resource,
    InvocationOrigin origin,
    Map<String, Object> context)
```

The 5-arg constructor is canonical and takes an explicit `InvocationOrigin`. A 4-arg convenience constructor — `AuthorizationRequest(SecurityContext, String, ResourceRef, Map)` — omits `origin` and delegates to the canonical constructor with `InvocationOrigin.unspecified()`, so call sites that don't seed an invocation origin keep compiling unchanged.

`context` and `origin` are both **advisory input, never a trust source** — the authoritative identity and authorities always come from `securityContext`. Callers must not place sensitive data (tokens, credentials, PII) in either, since the engine may surface them in audit output.

---

### InvocationOrigin

Transport-neutral descriptor of the invocation/transport boundary an `AuthorizationRequest` was raised through — the authz-time counterpart to the pre-auth network envelope carried by `SecurityContext.origin()` (`RequestOrigin`). `RequestOrigin` captures network-envelope facts (peer IP, scheme, TLS) before authentication runs; `InvocationOrigin` identifies *which ingress kind* dispatched the request into authorization, so a policy may discriminate on it (e.g. deny a sensitive action invoked via an unattended integration boundary, permit it from an interactive REST session).

```java
public record InvocationOrigin(String kind, Map<String, Object> attributes) implements ContextValue {
    public static InvocationOrigin of(String kind);
    public static InvocationOrigin unspecified(); // kind = "unspecified"
}
```

`kind` is a transport-neutral ingress identifier drawn from `DispatchBoundary` values (e.g. `"rest"`, `"external"`, `"delayed-job"`, `"kafka"`, `"workflow"`) — centralizing the string constants there avoids typos. Implements `ContextValue` so an ingress boundary can bind a real `InvocationOrigin` as the ambient invocation origin on `ContextHolder`, and downstream authorization/snapshot-capture code reads it back via `contextHolder.current(InvocationOrigin.class)`. A caller that doesn't seed one gets `unspecified()`. Service dispatch (`ServiceMethodInvoker`) is root-ingress-aware rather than an unconditional overwrite: an upstream `InvocationOrigin` already present is preserved as-is, a propagated `DeferredExecutionOrigin` (delayed-job/cron/outbox-relay) is mapped faithfully to an `InvocationOrigin` of the same `kind()` so deferred work stays distinguishable from an ordinary call, and only when neither is present does it seed its own boundary (`DispatchBoundary#SERVICE_DISPATCH`) — see `ServiceMethodInvoker` in `dev.vertique:vertique-services` for the full precedence.

**Invariants & Gotchas:**

- `attributes` is advisory input, never a trust source — mirrors `AuthorizationRequest#context()`. Must not carry sensitive data; the engine may surface it in audit output.
- `attributes` is bounded to `MAX_ATTRIBUTES` (16) entries, each value's `String.valueOf(...)` form bounded to `MAX_VALUE_LENGTH` (1024) characters. Exceeding either throws `IllegalArgumentException`.
- The identity-snapshot capture path's `originSummary` reads the ambient `InvocationOrigin`'s `kind()` — see `DefaultIdentitySnapshotFactory` in `dev.vertique:vertique-security-runtime`.

---

### Authorizer

Transport-neutral authorization engine. Returns a `Future<AuthorizationDecision>` — always a succeeded future. Denials are reported as decisions with `permitted == false`; the engine never throws to signal a deny and never emits authorization events (emission belongs to the PEP — see ADR-0114).

```java
public interface Authorizer {
    Future<AuthorizationDecision> authorize(AuthorizationRequest request);
    Future<AuthorizationDecision> authorize(SecurityContext ctx, ActionRef action, ResourceRef resource);
}
```

The convenience overload accepts a `null` `ctx` and fails closed rather than throwing.

---

### AuthorizationIntrospector

Set-valued inverse of `Authorizer`: answers "which registered actions are permitted for this actor?". Used to back capability-style introspection endpoints.

```java
public interface AuthorizationIntrospector {
    Set<ActionRef> allowedActions(SecurityContext ctx);
    Set<ActionCapability> capabilities(SecurityContext ctx);
}
```

**Agreement invariant:** the default implementation (`DefaultAuthorizationIntrospector` in `vertique-security-runtime`) is built on the same `AuthzResolution` singleton as `DefaultAuthorizer`, so the two surfaces cannot diverge: an action is in `allowedActions` iff the authorizer permits it.

**`capabilities(ctx)`** pairs each allowed action with **every** `RequirementDescriptor` an installed `AuthorizationNarrower` reports for it (see `ActionCapability` below) — not merely the first, since two independent narrowers (e.g. a delegation-scope narrower and an assurance-level narrower) may each gate the same action for unrelated reasons. Membership is identical to `allowedActions` — `capabilities` only *annotates* an action with its requirements, it never filters one out based on them: `allowedActions(ctx)` always equals `capabilities(ctx).stream().map(ActionCapability::action)`. With no narrowers installed, every `ActionCapability` carries an empty requirement set.

**Unsupported for a reconstructed context.** Both `allowedActions(ctx)` and `capabilities(ctx)` throw `ReconstructedContextIntrospectionUnsupportedException` when `ctx.reconstruction()` is present, *before* consulting the base introspector or any narrower — a reconstructed context's current authority is resolved **live**, at authorize-time, by Mode 2 (`ReconstructedAuthorityResolvingAuthorizer`), so a synchronous introspection answer computed from the context's currently-held claims could silently disagree with what `authorize()` would actually decide for the same actor. The framework's exposed `AuthorizationIntrospector` binding (`NarrowingIntrospector` in `vertique-security-runtime`) enforces this uniformly regardless of which base introspector or narrower set is installed. A caller needing an authorization answer for a reconstructed context calls `Authorizer#authorize(AuthorizationRequest)` for the specific action instead.

---

### ActionCapability, RequirementDescriptor

Value types backing `AuthorizationIntrospector.capabilities(SecurityContext)`.

```java
public record RequirementDescriptor(String kind, String detail)

public record ActionCapability(ActionRef action, Set<RequirementDescriptor> requirements)
```

`RequirementDescriptor` is an audit-safe description of a requirement an `AuthorizationNarrower` places on an action for a given actor — `kind` a stable machine-readable category (e.g. `"delegation"`, `"ASSURANCE"`), `detail` a human-readable description. Like `AuthorizationDecision#safeAttributes()`, both are intended to be shown to a caller (e.g. in a capability-introspection response), so implementations must never place sensitive data (tokens, credentials, PII) in either.

`ActionCapability` pairs a permitted `ActionRef` with **every** requirement gating it — one entry per narrower that reports one for this actor/action, not merely the first, since two independent narrowers may each place an unrelated condition on the same action. An empty `requirements` set means the action is unconstrained by any installed `AuthorizationNarrower`; a non-empty set means the capability exists but is conditionally gated — a caller still evaluates the concrete `Authorizer.authorize(AuthorizationRequest)` decision to learn whether a particular request satisfies every gate.

---

### AuthorizationDecision

Immutable result of a policy evaluation.

```java
public record AuthorizationDecision(
    boolean permitted,
    String reasonCode,
    Optional<String> policyId,
    Optional<String> policyVersion,
    Map<String, Object> safeAttributes)
```

Convenience factories: `AuthorizationDecision.permit(reasonCode)`, `AuthorizationDecision.deny(reasonCode)`.

**`safeAttributes` contract:** this map is intended for audit logs and error responses — callers must not place sensitive data in it and must not read it back as a trust source for a subsequent decision.

---

### AuthzReasonCodes

Constants holder (not an enum) for machine-readable reason codes on `AuthorizationDecision`. The set is a deliberate superset of what the default engine produces, reserving codes for enforcement-layer concerns.

| Constant | Produced by |
|----------|-------------|
| `PERMITTED` | Default engine — action allowed |
| `ROLE_MISSING` | Default engine — no `ROLE` claim present |
| `ROLE_POLICY_MISSING` | Default engine — roles map to no policy name |
| `POLICY_NOT_FOUND` | Default engine — mapped policy name absent from catalogue |
| `ACTION_NOT_REGISTERED` | Default engine — action absent from `ActionRegistry` |
| `ACTION_NOT_ALLOWED` | Default engine — policies resolved but none allows the action |
| `INTERNAL_AUTHZ_ERROR` | Default engine — unexpected error; fails closed |
| `AUTHENTICATION_REQUIRED` | Enforcement layer (reserved) |
| `STEP_UP_REQUIRED` | Opt-in `AssuranceRequirementNarrower` (`vertique-security-runtime`) — assurance-gated action, unmet minimum-assurance requirement |
| `DENY_ALL` | Enforcement layer (reserved) |
| `SCOPE_MISSING` / `SCOPE_INSUFFICIENT` | Enforcement layer (reserved) |
| `POLICY_INVALID` / `INSTANCE_ELIGIBILITY_FAILED` | Future evaluators (reserved) |
| `AUTHORITY_RESOLUTION_FAILED` | Opt-in `ReconstructedAuthorityResolvingAuthorizer` (Mode 2, `vertique-security-runtime`) — `PrincipalAuthorityResolver` failed, timed out, or returned an ambiguous result |

---

### @RequiresAction

Method- or type-level annotation declaring that a JAX-RS resource method, WebSocket endpoint, or service method requires an action authorization check.

```java
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface RequiresAction {
    String value(); // canonical three-segment action, e.g. "cms.content.read"
}
```

**AND-composition:** `@RequiresAction` AND-composes with `@RolesAllowed` and `@Authorized` — both the role/scope gate and the action gate must pass. Combining with `@PermitAll` or `@DenyAll` is a conflict rejected at compile time and at startup.

**Fail-closed invariant:** any surface that does not enforce `@RequiresAction` must reject its presence at startup. An unenforceable annotation is a startup error, never silently ignored.

---

### SecurityEventObserver

SPI for observing security lifecycle events. All methods are `default` returning `Future.succeededFuture()` — implementations opt in only to the events they need.

```java
public interface SecurityEventObserver {
    default Future<Void> onCredentialAccepted(CredentialAcceptedEvent event)   { ... }
    default Future<Void> onCredentialRejected(CredentialRejectedEvent event)   { ... }
    default Future<Void> onAuthorizationDecided(AuthorizationDecisionEvent event) { ... }
    default Future<Void> onChannelLifecycle(ChannelLifecycleEvent event)        { ... }
}
```

**Failure isolation:** one observer's failure must not prevent other observers from receiving the event and must not alter the authentication or authorization result that produced it. The `SecurityEventEmitter` (in `vertique-security-runtime`) enforces this.

**Long-running work:** all observer methods run on the Vert.x event loop. Blocking or CPU-intensive work must be offloaded:

```java
@Override
public Future<Void> onCredentialAccepted(CredentialAcceptedEvent event) {
    return vertx.executeBlocking(() -> {
        auditDatabase.insert(event);
        return null;
    });
}
```

---

### Event Records

All event records carry `occurredAt` (`Instant`), `correlation` (`CorrelationContext`), and `origin` (`Optional<RequestOrigin>`). All fields are validated non-null by compact constructors.

| Record | Fired when |
|--------|-----------|
| `CredentialAcceptedEvent` | Framework accepts verified inbound credential material and resolves a `SecurityIdentity` |
| `CredentialRejectedEvent` | Framework rejects presented credential material before identity resolution |
| `AuthorizationDecisionEvent` | Authorization policy produces a permit or deny decision; carries `AuthorizationRequest` + `AuthorizationDecision` |
| `ChannelOpenedEvent` | Long-lived channel established with an authenticated identity |
| `ChannelIdentityRefreshedEvent` | Channel identity refreshed; carries both new and prior `SecurityContext` |
| `ChannelClosedEvent` | Channel closed by peer, server, or expiry; carries `reasonCode` |
| `IdentitySnapshotDegradationEvent` | A durably-carried `IdentitySnapshot` is present but cannot be reconstructed (bad HMAC, unknown/unavailable key, decode failure, incompatible schema version, or a stale/malformed temporal envelope) — or a `CarriageRequirement#REQUIRED` durable target dispatched with no snapshot at all (`EXPECTED_ABSENT`); carries `reasonCode` and a best-effort recovered `origin` |
| `CapturedAuthorityActivatedEvent` | Mode-3 captured authority is actually put into effect — a durably-captured snapshot's frozen claims are presented as a reconstructed context's current authority; emitted-and-awaited by `CapturedAuthorityActivation` (`vertique-security-runtime`), never by `CapturedAuthorityReconstruction` itself (which stays event-silent) |

`AuthorizationDecisionEvent` carries two derived accessors beyond its record components: `invocationOrigin()` surfaces the embedded request's `InvocationOrigin` (the transport-neutral ingress boundary the request was raised through), and `authorityMode()` derives the `ReconstructedAuthorityMode` that governed the decision — from an explicit `ReconstructedAuthorityMode.DECISION_ATTRIBUTE` stamp on `AuthorizationDecision#safeAttributes()` when present (the only source for `LIVE_RESOLVED`, an evaluation outcome a decorator stamps, never a marker value), else from the request's security context's intrinsic `reconstruction()` marker (covering `CAPTURED`/`ATTRIBUTION_ONLY` contexts no decorator stamped), else `Optional.empty()` for a non-reconstructed request with no stamp.

`ChannelLifecycleEvent` is a sealed interface; use a sealed `switch` to dispatch on subtypes:

```java
switch (event) {
    case ChannelOpenedEvent e            -> handleOpened(e);
    case ChannelIdentityRefreshedEvent e -> handleRefreshed(e);
    case ChannelClosedEvent e            -> handleClosed(e);
}
```

---

### IdentitySnapshot, IdentitySnapshotContent, SnapshotCarrierBinding, SnapshotIntegrity, DelegationSummary

Schema v2 durable carriage of a `SecurityContext`'s full identity dimension, for carrying identity across a durability boundary (a scheduled job, an outbox relay, or a workflow resume) without ever carrying live credential material. Schema v2 splits the captured identity dimension (`IdentitySnapshotContent`) from the signed, row-bound envelope (`IdentitySnapshot`) that carries it — the F5 replay defense (see ADR-0166).

```java
public record IdentitySnapshot(
    int schemaVersion,
    IdentitySnapshotContent content,
    SnapshotCarrierBinding carrier,
    Instant issuedAt,
    Instant expiresAt,
    SnapshotIntegrity integrity)

public record IdentitySnapshotContent(
    PrincipalRef actor,
    Optional<PrincipalRef> subject,
    Optional<DelegationSummary> delegation,
    Optional<ClientRef> client,
    String authenticationMethodKind,
    Instant authenticatedAt,
    Optional<AuthenticationAssurance> assurance,
    List<AuthorityClaim> authorizationClaims,
    String originSummary,
    Instant capturedAt)

public record SnapshotCarrierBinding(String carrierId, DurableTarget target)

public record SnapshotIntegrity(String algorithm, String keyId, String tag)

public record DelegationSummary(String kind, Optional<String> authorityId)
```

- **`IdentitySnapshot`** is the immutable, signed, credential-free durable envelope. `content` is the captured identity dimension (below); `carrier` pins the snapshot to the specific durable row it was written for; `issuedAt`/`expiresAt` bound its temporal validity; `integrity` is the mandatory MAC over the whole envelope. `schemaVersion` is `2` for this schema — it must be strictly positive, and decode-time compatibility (rejecting an unknown-newer version) is enforced by the runtime codec, not by this constructor.
- **`IdentitySnapshotContent`** is the captured identity dimension itself — everything needed to reconstruct a delegated identity structure (actor, subject, delegation, client, authentication method *kind*, assurance, authorization claims, origin), plus the immutable `capturedAt` capture instant — with deliberately no evidence/token component; `authenticationMethodKind()` records only the normalized kind of the original method, never the credential itself. `IdentitySnapshotFactory.capture(...)` (below) produces this content directly — capture cannot legitimately produce a carrier binding, temporal bounds, or a signature, so it no longer returns a to-be-signed sentinel envelope; the durable encoder assembles and signs the `IdentitySnapshot` envelope around this content.
- **`SnapshotCarrierBinding`** binds a signed `IdentitySnapshot` to the specific durable row-carrier it was written for: `carrierId` is a framework-generated identity allocated *before* the durable row is persisted (e.g. a delayed-job execution UUID, an outbox `carrier_id`, a workflow-timer id), and `target` (a `DurableTarget` — `kind`, `address`, optional `messageType` — from `dev.vertique.core.context`) names the durable destination that row was written for. Both are signed into the envelope so receive-side reconstruction can confirm the snapshot is being reinstated against the same carrier identity it was encoded for, rather than a replayed or transplanted one.
- **`SnapshotIntegrity`** is the mandatory signed envelope proving a snapshot was produced by the framework's durable codec and has not been tampered with. `tag` is a base64url-encoded MAC computed over the snapshot's canonical bytes plus `keyId` and `algorithm`; `keyId` addresses the signing key so a keyset can rotate (active + previous keys). The durable codec in `vertique-security-runtime` is the single HMAC signer that computes the authoritative tag at encode time — the to-be-signed envelope the encoder assembles carries a placeholder algorithm/keyId/tag which the codec replaces.
- **`DelegationSummary`** mirrors the shape of `DelegationContext` but is deliberately narrowed to `kind` and `authorityId` only — no delegation reason or free-form attributes — so a snapshot never carries more than the minimal facts needed to reconstruct the delegated identity structure later.

#### Invariants & Gotchas

- Every `IdentitySnapshot` carries a mandatory `integrity()` envelope — the snapshot is never valid without one; there is no unsigned mode at the type level.
- A `null` `authorizationClaims` list on `IdentitySnapshotContent` is treated as an empty list and defensively copied; `subject`, `delegation`, `client`, and `assurance` are required (non-null) `Optional` references.
- **Content/envelope split.** `IdentitySnapshotFactory.capture(...)` returns `IdentitySnapshotContent` only, never a whole `IdentitySnapshot` — see `IdentitySnapshotFactory` below. Reconstruction (below) is the only consumer that *acts* on a snapshot's contents — capturing or carrying a snapshot never by itself grants or removes authorization standing.
- **Freshness is a three-term minimum, re-checked at both decode and reconstruction.** `IdentitySnapshotCodec.decode` (in `vertique-security-runtime`) rejects a snapshot past `min(expiresAt, issuedAt + maxCarrierLifetime, content.capturedAt + maxSnapshotLifetime)` — the latter two terms only when an operator has configured the corresponding budget. The snapshot-lifetime budget is anchored on the *immutable* `content.capturedAt()`, never `issuedAt`, so a chained re-encode (which mints a fresh `issuedAt` for a new carrier) cannot renew authority past what the original capture already earned. Every reconstruction entry point (`IdentityReconstruction`/`CapturedAuthorityReconstruction`, below) re-verifies both integrity *and* current freshness via `IdentitySnapshotCodec.verifyForUse`, re-evaluated against the codec's clock at the moment of reconstruction — a snapshot that decoded fresh but has since aged past its effective expiry while retained in memory is rejected fail-closed here too, not only at the original decode.
- **Carrier binding is enforced at reconstruction**, independently of the decode-time carrier check the durable decoder already performs: a snapshot whose signed `carrier()` does not match the receive-side's expected carrier is rejected fail-closed (F5 replay defense, ADR-0166).
- `authorizationClaims` are **attribution only** — audit lineage of what the principal was authorized for at capture time. They are never restored as the reconstructed context's *current* authority (a reconstructed `authorization()` is empty); see `IdentityReconstruction` below (FR-ID-CA-010).

---

### CarriageRequirement

Operator-declared expectation for whether a durable dispatch target must carry a verified identity snapshot — the config-side half of the F5 "expected-but-absent carriage detection" defense (ADR-0166).

```java
public enum CarriageRequirement { REQUIRED, OPTIONAL, FORBIDDEN }
```

| Value | Meaning |
|-------|---------|
| `REQUIRED` | Every dispatch on this durable target must carry a verified snapshot; a dispatch that arrives with none is an `EXPECTED_ABSENT` degradation (`SnapshotDegradationReason`), not a silently-tolerated gap. Declaring any target-kind `REQUIRED` forces both `maxCarrierLifetimeMs` and `maxSnapshotLifetimeMs` to be configured (`IdentitySnapshotConfig` in `dev.vertique:vertique-security-runtime`) — a never-expiring `REQUIRED` snapshot would otherwise be a standing bearer credential. |
| `OPTIONAL` | Carriage may be absent on this durable target — the default for an unlisted target-kind. |
| `FORBIDDEN` | This durable target never carries identity (e.g. a system-scheduled cron trigger with no originating principal) — an absent snapshot is expected and never flagged. A **present** snapshot on a `FORBIDDEN` target — verified or not — is refused fail-closed rather than reconstructed, since the target is declared to never carry identity and verification status is irrelevant once one arrives at all. |

Resolved per durable-target kind via `IdentitySnapshotConfig#carriageRequirementFor(String)` (`vertique-security-runtime`), keyed on the `DeferredExecutionOrigin#kind()` / durable-target-kind string (e.g. `"delayed-job"`, `"cron"`, `"outbox-relay"`); an unlisted kind resolves to `OPTIONAL`.

---

### ReconstructedAuthorityMode, ReconstructionMarker

Select and carry how a reconstructed `SecurityContext`'s current authority is determined.

```java
public enum ReconstructedAuthorityMode { ATTRIBUTION_ONLY, LIVE_RESOLVED, CAPTURED }

public record ReconstructionMarker(ReconstructedAuthorityMode mode)
```

`ReconstructionMarker` is the typed, **unforgeable** verified-reconstruction signal `SecurityContext.reconstruction()` returns — present only for a context produced by the framework's verified-reconstruction path (`IdentityReconstruction`) or the Mode-2 authorizer's own evaluation-context rebuild, both of which go through `SecurityContexts.assembleReconstructed(...)`. A Mode-2 authorizer MUST key off this typed accessor to decide whether a context is a verified reconstruction — **never** the descriptive `identity.reconstructed=true` string attribute on `AuthenticationState#safeAttributes()`, which any code populating `safeAttributes` could set on an otherwise live-authored context.

**Carries no principal of its own.** Per FR-ID-DG-006, v1 delegation resolves the intersection of the context's own actor authority and any grant scope — subject-authority evaluation (impersonation) is explicitly out of v1 scope. A Mode-2 authorizer (`ReconstructedAuthorityResolvingAuthorizer` in `vertique-security-runtime`) therefore derives the `PrincipalKey` to re-resolve directly from `SecurityContext#identity()#actor()` — **never** from this marker and **never** from `SecurityIdentity#subject()`, so both `resumeAsPrincipal` (actor == the resumed principal) and `deferredExecution` (actor == the executing service) resolve the acting principal's own current authority; the subject-of-record, when present, stays on `SecurityIdentity#subject()` as attribution only.

`ReconstructedAuthorityMode` values:

| Value | Meaning |
|-------|---------|
| `ATTRIBUTION_ONLY` | Phase-1 default: the snapshot's captured claims are audit lineage only; `SecurityContext#authorization()` is always `AuthorizationClaims.empty()`. |
| `LIVE_RESOLVED` | An **evaluation outcome**, never a marker value — current authority was re-resolved live via `PrincipalAuthorityResolver` (Mode 2). Must never appear as a `ReconstructionMarker#mode()`; the compact constructor throws `IllegalArgumentException` if it is passed. |
| `CAPTURED` | Mode 3: the snapshot's captured claims are trusted and carried forward as-is as current authority. |

`ReconstructedAuthorityMode.DECISION_ATTRIBUTE` (`"authz.authority.mode"`) is the `AuthorizationDecision#safeAttributes()` key an authority-mode decorator stamps, so an embedding `AuthorizationDecisionEvent` can distinguish which strategy produced a given decision — see `AuthorizationDecisionEvent#authorityMode()` above.

---

### VerificationSource (sealed)

Sealed interface discriminating the mechanism used to verify an authentication credential. Carried in `CredentialAcceptedEvent` and safe to persist in audit records.

| Permit | `"type"` discriminator | Mechanism |
|--------|------------------------|-----------|
| `JwksVerificationSource` | `"jwks"` | JWT signature verification via JSON Web Key Set |
| `IntrospectionVerificationSource` | `"introspection"` | OAuth 2.0 token introspection endpoint |
| `ApiKeyRegistryVerificationSource` | `"api-key-registry"` | Application-managed API key registry |
| `MtlsTrustStoreVerificationSource` | `"mtls-trust-store"` | Mutual TLS trust store |
| `HmacSecretResolverVerificationSource` | `"hmac-secret-resolver"` | HMAC shared-secret resolver |
| `BasicCredentialVerifierVerificationSource` | `"basic-credential-verifier"` | Basic authentication credential verifier |
| `CustomVerificationSource` | `"custom"` | Application-defined mechanism |

---

## Extension Points

### ActionContributor

Subsystems implement this SPI to declare their actions into the framework-wide `ActionRegistry`. Called once at startup when the registry is built; duplicates and grammar violations fail fast.

```java
public interface ActionContributor {
    Collection<ActionDefinition> actions();
}
```

**Registration:**

```java
@Provides @IntoSet
static ActionContributor cmsActions(CmsActionContributor c) { return c; }
```

```java
@Singleton
public final class CmsActionContributor implements ActionContributor {
    private static final List<ActionDefinition> ACTIONS = List.of(
        new ActionDefinition(ActionRef.of("cms", "content", "read")),
        new ActionDefinition(ActionRef.of("cms", "content", "write"))
    );

    @Override
    public Collection<ActionDefinition> actions() { return ACTIONS; }
}
```

---

### PolicyDefinitionSource

Modules or applications implement this SPI to contribute named `PolicyDefinition`s to the authorization engine. Called once at startup; the default `validateAgainst(ActionRegistry)` implementation validates all contributed patterns against the registry — exact patterns must be registered, wildcards must match at least one action.

```java
public interface PolicyDefinitionSource {
    Collection<PolicyDefinition> policies();
    default void validateAgainst(ActionRegistry registry) { /* ... validates all patterns ... */ }
}
```

**Registration:**

```java
@Provides @IntoSet
static PolicyDefinitionSource myPolicies(MyPolicySource s) { return s; }
```

---

### RolePolicyResolver

Maps role names (from `AuthorityKind.ROLE` claims) to policy names. Multiple implementations are contributed via `@IntoSet`; the authorization engine merges (union) all results.

```java
public interface RolePolicyResolver {
    Set<String> policiesForRoles(Set<String> roles);
}
```

---

### AuthorizationNarrower

SPI that composes on top of the base `Authorizer`/`AuthorizationIntrospector` pair to apply an additional, narrower-only constraint (e.g. a delegation-scoped restriction, a minimum-assurance step-up gate) without touching the base role/policy engine. Extends `OrderedExtension`.

```java
public interface AuthorizationNarrower extends OrderedExtension {
    Future<AuthorizationDecision> narrow(AuthorizationRequest request, AuthorizationDecision base);
    Optional<RequirementDescriptor> requirementFor(SecurityContext ctx, ActionRef action);
}
```

**Registration:**

```java
@Provides @IntoSet
static AuthorizationNarrower myNarrower(MyNarrower n) { return n; }
```

`narrow(request, base)` participates in the async per-request decision path: given the decision so far (the base `Authorizer`'s verdict, or a previous narrower's result), it may turn a permit into a deny, or annotate an existing deny, but **must never turn a deny into a permit**. `requirementFor(ctx, action)` participates in the sync per-action introspection path, independent of any specific resource: it reports whether this narrower places an additional, describable condition on the action for the given actor, annotating a capability rather than removing it.

Multiple installed narrowers fold in `OrderedExtension#comparator()` order (phase, then ascending `priority()`, then `orderKey()`) via the runtime's `NarrowingAuthorizer`/`NarrowingIntrospector`. Two narrowers sharing the same `(priority, orderKey)` pair fail startup naming both. With no narrowers installed, the composed `Authorizer`/`AuthorizationIntrospector` are behavior-identical to the base engine.

Two framework-shipped narrowers ship behind their own opt-in Dagger module — see `dev.vertique:vertique-security-runtime` for `DelegationEnforcementNarrower` (priority 100, delegation-grant scope enforcement) and `AssuranceRequirementNarrower` (priority 200, minimum-assurance step-up gating).

#### Invariants & Gotchas

- **No-widen guard.** The runtime composition enforces the no-widen invariant structurally: a candidate decision that widens a current deny into a permit is discarded and the current deny is kept, with a framework-integrity error logged naming the offending narrower.
- **Annotate-only introspection.** `requirementFor` never performs the concrete, resource-specific evaluation `narrow` does — it reports that an action is *conditionally gated*, not whether a specific request currently satisfies the gate.

---

### DelegationGrantValidator

Application-implemented SPI answering whether a `DelegationGrant` authorizes a given (actor, subject, scope) triple at evaluation time.

```java
public interface DelegationGrantValidator {
    Future<DelegationGrantDecision> validate(
        PrincipalRef actor, PrincipalRef subject, String scopeKind, String scopeRef, String grantId);
}
```

Asynchronous because storage and revocation lookup can require I/O (mirroring `Authorizer`'s contract). **Fail-closed:** a lookup failure or timeout is never a failed `Future` — it maps to a deny with reason `DelegationReasonCodes#GRANT_LOOKUP_FAILED`, so callers can `compose`/`map` the result without a `recover()` for the "storage is down" case. `actor` maps to the grant's `grantee()` (the principal exercising the grant); `subject` maps to the grant's `grantor()` (the principal on whose behalf).

Expiry and scope checks ship in the framework's `InMemoryDelegationGrantValidator` default (`vertique-security-runtime`); storage and revocation lookup live behind this seam for consumer-owned durable implementations.

---

### SecurityIdentityResolver

Chain-of-responsibility SPI for resolving a `SecurityIdentity` from accumulated authentication evidence. Implements `OrderedExtension` — ordered by phase, then ascending `priority()`, then `orderKey()` (defaults to class name).

```java
public interface SecurityIdentityResolver extends OrderedExtension {
    default int priority() { return 100; } // < 100 runs before framework defaults
    default String id()    { return getClass().getName(); }
    Future<Optional<SecurityIdentity>> resolve(SecurityIdentityResolutionContext context);
}
```

Return `Optional.empty()` to pass control to the next resolver in the chain. Return a failed `Future` only for genuine errors — it propagates to the caller and does not advance the chain.

---

### ChannelIdentityManager

Per-runtime registry SPI for long-lived connection identity management (WebSocket, SSE, future transports). The default implementation lives in `vertique-rest-security`. Transport-specific bindings live in their owning modules.

```java
public interface ChannelIdentityManager {
    Future<Void> register(String channelId, SecurityContext ctx, ChannelBinding binding);
    Future<Void> refreshIdentity(String channelId, SecurityContext newCtx);
    Future<Void> closeChannel(String channelId, String reasonCode);
    Future<Void> deregister(String channelId, String fallbackReasonCode);
    Optional<SecurityContext> current(String channelId);
}
```

**`refreshIdentity` composability:** the returned `Future` completes only after the rebind has taken effect on the channel's owning execution context. Callers must `compose` on it before depending on the new identity.

**`deregister` is the single cleanup owner:** it removes the channel from the registry, cancels the expiry timer, determines the effective close reason, emits `ChannelClosedEvent`, and calls `ChannelBinding.releaseResources()`. It is idempotent — an unregistered channel returns a succeeded future without emitting any event.

---

### IdentitySnapshotFactory

**Framework-internal interface, not a swap-in extension point.** Listed here because capture is a distinct concern from the general-assembly `SecurityContexts` statics, with its own Dagger placement — not because an application is expected to substitute an implementation. For V1 it is a fixed, framework-wired implementation: `IdentitySnapshotCarriageModule` (in `vertique-security-runtime`) installs `DefaultIdentitySnapshotFactory` as this type's sole implementation via an unconditional `@Provides` binding, with no `@BindsOptionalOf` seam — an application `@Provides` of this type is rejected at compile time as a duplicate Dagger binding. It captures the credential-free `IdentitySnapshotContent` of a live `SecurityContext`'s full identity dimension, suitable for the durable encoder to assemble and sign into an `IdentitySnapshot` envelope for carrying across a durability boundary.

```java
public interface IdentitySnapshotFactory {
    IdentitySnapshotContent capture(SecurityContext live);
}
```

Named `capture` rather than `snapshot` to avoid colliding with `SecurityContext.snapshot()`, which produces the distinct, non-serializable, in-memory `SecurityContextSnapshot`. `capture` returns **content only** — no carrier binding, no temporal bounds, no integrity envelope — because capture cannot legitimately produce any of those; they belong to the durable envelope. The durable encoder (`IdentitySnapshotDurableEncoder` in `vertique-security-runtime`) assembles the `IdentitySnapshot` envelope around this content, and `IdentitySnapshotCodec` computes the authoritative integrity tag at encode time, not this capture step. The default implementation, `DefaultIdentitySnapshotFactory`, lives in `vertique-security-runtime`. Because `IdentitySnapshotCodec` performs no independent content re-validation of a snapshot's attribute maps at encode time, any substitute implementation hand-wired outside `IdentitySnapshotCarriageModule` is solely responsible for upholding the credential-free attribute projection invariant described in the `PrincipalRef` durable capture caveat above.

---

### IdentityReconstruction

Privileged SPI that reconstructs a `SecurityContext` from a credential-free `IdentitySnapshot` captured across a durability boundary — the identity-minting trust boundary of the snapshot pipeline.

```java
public interface IdentityReconstruction {
    SecurityContext resumeAsPrincipal(IdentitySnapshot snapshot, DurableCarrierDescriptor expectedCarrier);
    SecurityContext deferredExecution(
        SecurityIdentity executingService, IdentitySnapshot snapshot, DurableCarrierDescriptor expectedCarrier);
    static boolean isReconstructed(SecurityContext ctx);
}
```

| Method | Used when |
|--------|-----------|
| `resumeAsPrincipal(snapshot, expectedCarrier)` | The original principal itself is being resumed (e.g. a workflow resuming exactly as the identity that scheduled it) — the snapshot's actor, subject, delegation, and client are carried over unchanged |
| `deferredExecution(executingService, snapshot, expectedCarrier)` | A system component (a scheduled job, an outbox relay handler) executes work on behalf of the identity that originally scheduled it — the executing service is always the actor, never impersonated as the original principal; the subject-of-record is the snapshot's subject when present, else the snapshot's actor |
| `isReconstructed(ctx)` | Reports whether `ctx` carries the reconstruction marker — reads strictly `ctx.authentication().safeAttributes().get("identity.reconstructed")`, never authorization or origin |

`expectedCarrier` (`DurableCarrierDescriptor`, from `dev.vertique.core.context`) is the durable row-carrier the receiving dispatch was actually written for — the trusted receive-side fact reconstruction checks the snapshot's signed `carrier()` against (same `carrierId` and `target`, or reconstruction fails closed): the F5 replay defense, ADR-0166.

#### Invariants & Gotchas

- **Fail-closed, never partial.** Both entry points re-verify the snapshot's `SnapshotIntegrity` envelope *and* its current freshness (via the shared `IdentitySnapshotCodec.verifyForUse`) and confirm the snapshot's signed `carrier()` matches `expectedCarrier`, before minting any context. A `null` snapshot, a snapshot that fails integrity or freshness verification, or a carrier mismatch throws `IdentityReconstructionException` before any `SecurityContext` is built — there is never a partial or unbound result. Freshness (the three-term expiry minimum — see `IdentitySnapshot` above) is enforced both at decode (`IdentitySnapshotCodec.decode`) *and* again here, at reconstruction, re-evaluated against the codec's current clock — closing the replay window a bare integrity check would leave open for a snapshot that decoded fresh but has since aged past its effective expiry while retained in memory (e.g. across a `resumeAsPrincipal`/`deferredExecution` call made some time after an earlier `decode`).
- **Reconstructed authority is attribution-only** (FR-ID-CA-010). Every reconstructed context's `authorization()` is `AuthorizationClaims.empty()`; the snapshot content's `authorizationClaims` are audit lineage (who/what the principal *was* at capture time) and are never presented as current authority, so a principal demoted or revoked during the pause never resumes with frozen standing. Live re-resolution (`PrincipalAuthorityResolver`, Mode 2) and an explicit captured-authority opt-in (`resumeWithCapturedAuthority`, Mode 3) are opt-in decorators layered on top of this default — see `ReconstructedAuthorityMode`/`ReconstructionMarker` and `CapturedAuthorityReconstruction` below.
- **Privileged-module boundary.** The implementation and its Dagger provision live in `vertique-security-runtime`, provided only by `PrivilegedIdentityModule` — a Dagger module distinct from the general `SecurityEventsModule`/`SecurityAuthzModule` wiring. Applications include it only on framework infrastructure components (job execution, inbox/outbox, workflow resume), never on general request-handling components, so a reviewer scanning a component's module list can see it is minting contexts from unauthenticated, second-hand data.
- **`IdentityReconstructionException`** carries a typed `reason()` (`SnapshotDegradationReason`) classifying *which* failure occurred. An integrity-verification failure surfaces the codec's mapped reason (`BAD_HMAC`, `UNKNOWN_KEY`, `KEY_UNAVAILABLE`, `DECODE_FAILED`, or `SCHEMA_INCOMPATIBLE`); a `null` snapshot or a carrier mismatch defaults to `DECODE_FAILED`. So a caller (e.g. the receive-side reconstruction initializer) can bind a precise `SnapshotDegradationMarker` rather than a single catch-all reason code.
- The reconstruction marker (`identity.reconstructed` = `true` in `safeAttributes`) is purely descriptive of how the context's authentication state was produced — setting it never grants or removes authorization standing by itself.

---

### PrincipalAuthorityResolver

Application-implemented SPI that re-resolves a reconstructed context's **acting principal's** current authority from its durable key alone — Mode 2 of the reconstructed-context authorization model.

```java
public interface PrincipalAuthorityResolver {
    Future<AuthorizationClaims> resolve(PrincipalKey key);
}
```

`PrincipalKey` (`record PrincipalKey(PrincipalType type, String id)`) is the capability-minimized durable principal key: it structurally carries **no attributes**, so an implementation cannot resolve scope from request-scoped attributes even by accident — it must consult durable, principal-keyed authority storage (a role/entitlement store, an IdP claim cache), never the reconstructed context's own attributes.

**Resolves the actor, never the subject-of-record.** Per FR-ID-DG-006, v1 delegation resolves only the *acting* principal's own current authority — `ReconstructedAuthorityResolvingAuthorizer` derives the `PrincipalKey` it resolves exclusively from `SecurityContext#identity()#actor()`: for `resumeAsPrincipal` the actor *is* the resumed principal; for `deferredExecution` the actor is the executing service. `SecurityIdentity#subject()`, when present, is never passed to this SPI — subject-authority evaluation (impersonation) is explicitly out of v1 scope; a downstream `AuthorizationNarrower` (e.g. `DelegationEnforcementNarrower`) may still intersect the resolved actor authority against a grant's scope.

**Bounded by an operator-configured timeout.** `SecurityAuthzModule` wraps every installed `PrincipalAuthorityResolver` in `TimeoutPrincipalAuthorityResolver` (`vertique-security-runtime`, `identity.authz.resolutionTimeoutMs`, default 5000ms) before handing it to `ReconstructedAuthorityResolvingAuthorizer` — a delegate whose returned `Future` never completes cannot hang Mode-2 authorization indefinitely; the timeout races the delegate's future and fails the same way a genuine resolver failure would.

**Fails closed.** A missing, malformed, ambiguous, unresolvable, or timed-out principal resolution is (or is mapped to) a *failed* `Future` — the runtime's `ReconstructedAuthorityResolvingAuthorizer` maps any resolver failure or timeout to a deny with reason `AuthzReasonCodes#AUTHORITY_RESOLUTION_FAILED`. A *resolvable* principal that currently holds no authority succeeds with `AuthorizationClaims.empty()` — a normal downstream deny (no matching claim), distinct from a resolution failure.

Binding a `PrincipalAuthorityResolver` is the Mode-2 opt-in itself — see `SecurityAuthzModule`'s `@BindsOptionalOf` seam and `ReconstructedAuthorityResolvingAuthorizer` in `dev.vertique:vertique-security-runtime`. A framework-shipped in-memory reference implementation, `InMemoryPrincipalAuthorityResolver`, is also available there.

---

### CapturedAuthorityReconstruction

Privileged SPI — Mode 3 of the reconstructed-context authorization model — that reconstructs a `SecurityContext` from a credential-free `IdentitySnapshot`, presenting the snapshot's **captured** authorization claims as the reconstructed context's **current** authority.

```java
public interface CapturedAuthorityReconstruction {
    SecurityContext resumeWithCapturedAuthority(
        IdentitySnapshot snapshot, DurableCarrierDescriptor expectedCarrier);
    SecurityContext deferredExecutionWithCapturedAuthority(
        SecurityIdentity executingServiceIdentity, IdentitySnapshot snapshot,
        DurableCarrierDescriptor expectedCarrier);
}
```

This is a **distinct privileged type**, deliberately not a third method on `IdentityReconstruction`: `IdentityReconstruction`'s default reconstruction (Mode 1, above) always mints an empty `authorization()` — the snapshot's claims are audit lineage only. Mode 3 deliberately inverts that guarantee, so it lives on its own interface, wired only by its own opt-in `CapturedAuthorityReconstructionModule` — **never** by `PrivilegedIdentityModule` and never with a fallback binding. An application enables Mode 3 by explicitly installing that module, which is visible at code review.

Both entry points share `IdentityReconstruction`'s fail-closed trust boundary (re-verify integrity, confirm the snapshot's signed carrier matches `expectedCarrier`) and additionally enforce a **per-target-kind allowlist**: the snapshot's durable target kind must be one of the implementation's configured allowed kinds — each required by startup validation to carry a `CarriageRequirement#REQUIRED` carriage requirement, or reconstruction fails closed (a captured-authority target whose carriage is merely `OPTIONAL`/`FORBIDDEN` would let an attacker suppress the snapshot and dispatch un-authorized, or be a standing bearer-credential hole). `deferredExecutionWithCapturedAuthority` preserves the actor/subject split used by `IdentityReconstruction#deferredExecution` — the executing service is always the acting principal, the snapshot's subject-of-record is carried as the delegated subject, never impersonated as the acting principal; deferred-execution infrastructure MUST use this method, never `resumeWithCapturedAuthority`.

**Event-silent.** Like `IdentityReconstruction`, this interface never emits any `SecurityEventObserver` event — a plain interface method cannot invoke an injected emitter by construction. The sanctioned activation seam, `CapturedAuthorityActivation` (`vertique-security-runtime`), invokes this SPI and then emits and awaits `CapturedAuthorityActivatedEvent` once a reconstruction call has actually succeeded.

---

### SnapshotDegradationMarker

`ContextValue` handoff type binding a present-but-unverifiable durable identity snapshot to the async degradation gate.

```java
public record SnapshotDegradationMarker(String reasonCode, Optional<RequestOrigin> origin) implements ContextValue
```

The receive-side initializer for durable identity carriage runs synchronously and cannot itself emit the non-droppable `IdentitySnapshotDegradationEvent` (event emission is async — `SecurityEventEmitter.emit(...)` returns `Future<Void>`). Instead the initializer *detects and binds* this marker onto the `ContextHolder` when a carried snapshot fails HMAC/freshness/carrier verification, cannot be reconstructed, or is expected-but-absent on a `CarriageRequirement#REQUIRED` target; a separate async degradation gate on the service dispatch interceptor chain reads the marker, emits `IdentitySnapshotDegradationEvent`, and applies the configured degradation policy (`FAIL` or `CONTINUE_WITHOUT_IDENTITY`). `reasonCode` is one of `BAD_HMAC`, `UNKNOWN_KEY`, `DECODE_FAILED`, `KEY_UNAVAILABLE`, `SCHEMA_INCOMPATIBLE`, `EXPIRED`, `MALFORMED_TEMPORAL`, `EXPECTED_ABSENT` (`SnapshotDegradationReason`) — the last three added by the F5 replay defense (ADR-0166): `EXPIRED`/`MALFORMED_TEMPORAL` are freshness-policy failures surfaced through a decode failure, and `EXPECTED_ABSENT` is the receive-side detection of a `CarriageRequirement#REQUIRED` target dispatching with no snapshot bound at all. Public so `vertique-services` — where the gate lives, to avoid inverting the shipped services→security-runtime dependency direction — can read this marker off the context holder without depending on security-runtime internals.

`SecurityEventObserver` carries a matching `onIdentitySnapshotDegradation(IdentitySnapshotDegradationEvent)` default method (see Event Records above) for observing this outcome.

---

## Dependencies

- `dev.vertique:vertique-core` — `ContextValue` (for `SecurityContext`), `CorrelationContext`, `OrderedExtension`, exception roots
- `io.vertx:vertx-core` — `Future` (SPI method return types)
- `jakarta.annotation:jakarta.annotation-api` — `@Nullable`

---

## Related ADRs

- ADR-0064: Typed Security Identity Model — establishes `SecurityIdentity`, `PrincipalRef`, `PrincipalType`, `AuthenticationState`, and the four-pillar `SecurityContext` as the canonical identity model.
- ADR-0113: Federated Action and Policy Authorship for Framework Authorization — establishes `ActionRef`/`ActionPattern`/`PolicyDefinition`/`PolicyDefinitionSource`/`RolePolicyResolver` as the action-policy V1 model; mandates federated `ActionContributor` authorship over a framework-owned registry.
- ADR-0114: Enforcement-Layer Emission Ownership for Authorization Decisions — establishes that `Authorizer` is a pure decision function; authorization event emission is the responsibility of each surface's enforcement layer (PEP), not the engine.
- ADR-0115: WebSocket Action Authorization Granularity — governs how `@RequiresAction` applies to WebSocket endpoints and what the PEP enforces at open-time vs. message-time.
- ADR-0162: Identity Snapshot Reconstruction Trust Boundary and Mandatory HMAC — establishes `IdentitySnapshot.integrity()` as a mandatory, always-on HMAC envelope and `IdentityReconstruction` as a privileged, fail-closed trust boundary that re-verifies a snapshot before minting any `SecurityContext`.
- ADR-0163: Static `SecurityContexts` Assembly over an Injectable Factory — establishes `SecurityContexts` as a plain static factory for transport-neutral context assembly, rather than a Dagger-provided `SecurityContextFactory` singleton.
- ADR-0164: Identity-Snapshot Capture Site — SecurityContext Assembly (Ingress), Opt-In — governs where a snapshot is captured (REST ingress, via the `IdentitySnapshotFactory` capture interface, fixed framework implementation in V1) and records the attribution-only reconstruction invariant (`IdentityReconstruction` mints contexts with an empty `authorization()`).
- ADR-0166: Identity Snapshot F5 Replay Defense (Row-Carrier Binding + Freshness) — establishes the durable-carrier binding and freshness envelope `IdentitySnapshot` signs over, closing the cross-target/cross-dispatch replay gap in the reconstruction trust boundary.
- ADR-0167: Delegation Grant Lifecycle and Fail-Closed Validator — establishes `DelegationGrant`/`DelegationGrantValidator`/`DelegationGrantDecision` as the grant-backed delegation model and its fail-closed evaluation contract.
- ADR-0168: Authorization Narrowing Composition (Intersection, No-Widen, Agreement Invariant) — establishes `AuthorizationNarrower` as the ordered, no-widen composition seam over the base `Authorizer`/`AuthorizationIntrospector`.
- ADR-0169: Mode 2 Live Authority Re-Resolution for Reconstructed Contexts — establishes `PrincipalAuthorityResolver` and `ReconstructedAuthorityResolvingAuthorizer` as the opt-in live re-resolution of a reconstructed context's current authority.
- ADR-0170: Mode 3 Captured-Authority Reconstruction — Structural Never-Default — establishes `CapturedAuthorityReconstruction` as a distinct privileged type wired only by its own opt-in module, never a `PrivilegedIdentityModule` fallback.
- ADR-0171: Trust-Bearing InvocationOrigin Propagated from Ingress Boundaries — establishes `InvocationOrigin` as the transport-neutral, advisory-only ingress descriptor seeded at REST/service/Camel boundaries and carried on `AuthorizationRequest`.
- ADR-0172: Minimum-Assurance Policy Hooks and STEP_UP_REQUIRED — establishes the opt-in minimum-assurance gating model (`AssuranceRequirement`/`AssuranceRequirementNarrower` in `vertique-security-runtime`) and `AuthzReasonCodes#STEP_UP_REQUIRED`.
