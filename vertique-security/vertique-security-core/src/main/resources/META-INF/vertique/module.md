<!--
SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
SPDX-License-Identifier: EUPL-1.2
-->

# Security Core Module

> **Status:** Beta
> **Package:** `dev.vertique.security` (identity/authentication/context), `dev.vertique.security.authz` (authorization model), `dev.vertique.security.events` (event records + observer SPI), `dev.vertique.security.origin` (network origin), `dev.vertique.security.resolver` (identity resolver SPI), `dev.vertique.security.channel` (channel SPI), `dev.vertique.security.verification` (verification sources)
> **Artifact:** `vertique-security-core`
> **Depends on:** core (ContextValue, correlation, extension ordering, exception roots), Vert.x core (Future), Jakarta Annotations

Pure API, SPI, and value-type module for the Vertique security model. This module carries the typed identity/authentication/authorization contracts, the action-policy model, all security event record types, and the SPI interfaces that the runtime module implements and application code extends. It contains no pipeline execution, no Dagger wiring, and no Vert.x Web dependency — only interfaces, records, and annotations.

The runtime implementation lives in `dev.vertique:vertique-security-runtime`. Config-backed authorization adapters live in `dev.vertique:vertique-security-config`.

---

## When To Use It

Include `vertique-security-core` when a module needs to:

- Read the typed security model from a `SecurityContext` (identity, authentication state, authorization claims, network origin)
- Implement a custom `SecurityIdentityResolver`, `Authorizer`, `PolicyDefinitionSource`, `RolePolicyResolver`, `ActionContributor`, `AuthorizationNarrower`, `DelegationGrantValidator`, `PrincipalAuthorityResolver`, or `ChannelIdentityManager`
- Declare or reference an `ActionRef` for `@RequiresAction` enforcement
- Observe security lifecycle events by implementing `SecurityEventObserver`
- Embed a `VerificationSource` discriminator in a credential-accepted event

Add `dev.vertique:vertique-security-runtime` to get the default engine wiring and the `SecurityEventEmitter`.

---

## Core Concepts

### Identity Model

A `SecurityContext` is the request-scoped container for all security facts. It extends `ContextValue`, so it is stored in the Vert.x `ContextLocal` and accessible throughout the request lifecycle. It has four pillars:

- **`identity()`** — who is making the request: `SecurityIdentity` with a mandatory `actor` (`PrincipalRef`), optional `subject` (for PSD2/delegation), optional `delegation` context, and optional OAuth `client` reference.
- **`authentication()`** — how identity was established: `AuthenticationState` carrying the primary `AuthMethod`, ordered evidence list (`AuthenticationEvidence`), assurance level (`AuthenticationAssurance`), and safe token metadata.
- **`authorization()`** — what authority claims the principal holds: `AuthorizationClaims` keyed by `AuthorityKind` (`ROLE`, `GROUP`, `SCOPE`, `PERMISSION`, `ENTITLEMENT`, `CLAIM`).
- **`origin()`** — the network envelope captured before auth: peer address, forwarded chain, client IP after trusted-proxy policy, scheme, host, and optional TLS facts.

### Action-Policy Authorization Model

The authorization model is a three-layer role→policy→action resolution:

1. **Actions** — stable, three-segment canonical identifiers (`<subsystem>.<resource>.<verb>`, e.g. `cms.content.read`). Contributed by subsystems via `ActionContributor`; the `ActionRegistry` is the authoritative catalogue.
2. **Policies** — named sets of `PolicyStatement`s, each granting `Effect.ALLOW` on a set of `ActionPattern`s (exact or trailing-wildcard). Contributed via `PolicyDefinitionSource`.
3. **Role→Policy mapping** — `RolePolicyResolver` maps the principal's `ROLE` claims to applicable policy names. The authorization engine unions all resolvers' outputs.
4. **Narrowing (opt-in)** — an ordered chain of `AuthorizationNarrower`s composes on top of the base engine's permit/deny, applying additional narrower-only constraints (e.g. delegation-grant scope, minimum-assurance step-up) without touching role/policy resolution.

**Resolution order.** The default engine evaluates strictly in this order, returning at the first terminal outcome:

| Step | Outcome when it fires |
|---|---|
| 1. Action string parses as a canonical `ActionRef` | otherwise deny `INTERNAL_AUTHZ_ERROR` |
| 2. Action present in `ActionRegistry` | otherwise deny `ACTION_NOT_REGISTERED` |
| 3. Context holds at least one `ROLE` claim | otherwise deny `ROLE_MISSING` |
| 4. Roles resolve to at least one policy name | otherwise deny `ROLE_POLICY_MISSING` |
| 5. Each resolved policy name in turn: present in the catalogue | the **first** missing name terminates with deny `POLICY_NOT_FOUND` — later names are not consulted |
| 6. Some `ALLOW` statement of a resolved policy matches the action | permit `PERMITTED` |
| 7. No statement matched | deny `ACTION_NOT_ALLOWED` |

Every outcome is a **succeeded** `Future<AuthorizationDecision>` — the engine is a pure decision function, never throws to signal a deny, and never emits events. Installed `AuthorizationNarrower`s run after this base decision and may only narrow it further — never widen a deny into a permit.

### Reconstructed Contexts

A `SecurityContext` reconstructed from a durably-carried `IdentitySnapshot` is not a live-authenticated context. `SecurityContext.reconstruction()` returns a typed, unforgeable `ReconstructionMarker` for exactly those contexts; every normal, live-authored context returns `Optional.empty()`. Three authority dispositions exist — see `ReconstructedAuthorityMode` below. The default (`ATTRIBUTION_ONLY`) always mints an **empty** `authorization()`: a snapshot's captured claims are audit lineage, never restored authority.

---

## Key Classes

### SecurityContext

Interface extending `ContextValue`. The four pillars (`identity()`, `authentication()`, `authorization()`, `origin()`) are described above, plus two default methods.

`snapshot()` pins the current facts into an immutable `SecurityContextSnapshot` — capture a snapshot before crossing a thread boundary so a later context rebind cannot replace the fact records under an off-thread projection. `SecurityContextSnapshot` carries `identity`, `authentication`, and `origin` only — **not** `authorization()`.

`reconstruction()` returns the typed, unforgeable `ReconstructionMarker` present only on a framework verified reconstruction.

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

Typed factory methods, each rejecting a mismatched actor type with `IllegalArgumentException`:

| Factory | Actor type enforced |
|---------|---------------------|
| `SecurityIdentity.user(PrincipalRef)` | `PrincipalType.USER` |
| `SecurityIdentity.service(PrincipalRef)` | `PrincipalType.SERVICE` |
| `SecurityIdentity.anonymous()` | `PrincipalType.ANONYMOUS`, id `"anonymous"` |

`SYSTEM` identities must be created via `SystemIdentities` — there is intentionally no `system(...)` factory on this class.

---

### SystemIdentities

Static factory for `SYSTEM`-typed, actor-only identities. Each factory requires a non-blank `reason`, which is recorded as the actor's `system.reason` attribute — the one attribute that survives identity-snapshot durable capture.

```java
public final class SystemIdentities {
    public static SecurityIdentity workflow(String reason);     // actor id "system:workflow"
    public static SecurityIdentity scheduledJob(String reason); // actor id "system:scheduledJob"
    public static SecurityIdentity internal(String reason);     // actor id "system:internal"
}
```

---

### SecurityContexts

Static, transport-neutral factory for assembling `SecurityContext` instances outside any REST middleware — the general-assembly API for jobs, cron triggers, message handlers, and any other framework or application code that needs a real `SecurityContext` without going through the REST identity-resolution pipeline. Mirrors `SystemIdentities`: a plain static factory, not a Dagger-provided singleton.

```java
public final class SecurityContexts {
    public static SecurityContext system(SecurityIdentity serviceIdentity);
    public static SecurityContext unauthenticated(SecurityIdentity identity);
    public static SecurityContext assemble(
        SecurityIdentity identity,
        AuthenticationState auth,
        AuthorizationClaims claims,
        Optional<RequestOrigin> origin);
    public static SecurityContext assembleReconstructed(
        SecurityIdentity identity,
        AuthenticationState auth,
        AuthorizationClaims claims,
        Optional<RequestOrigin> origin,
        ReconstructionMarker marker);
}
```

| Factory | Use case | Authentication dimension |
|---------|----------|---------------------------|
| `system(SecurityIdentity)` | Framework-scheduled work with no external trigger and no asserted external principal (cron, delayed jobs) | `DefaultAuthMethod.custom("system")`, empty evidence |
| `unauthenticated(SecurityIdentity)` | Non-HTTP ingress with an asserted principal but no verified credential (file/queue drops) | `DefaultAuthMethod.none()`, empty evidence, no assurance, no tokens |
| `assemble(identity, auth, claims, origin)` | General-purpose assembly from already-known identity/authentication/authorization facts | caller-supplied |
| `assembleReconstructed(identity, auth, claims, origin, marker)` | The **only** way to produce a context whose `reconstruction()` is present | caller-supplied |

**Invariants & Gotchas:**

- **`system(...)` is doubly constrained.** It throws `IllegalArgumentException` unless the actor's `PrincipalType` is `SYSTEM` *and* the identity is actor-only (no `subject`, `delegation`, or `client`). Stamping `custom("system")` onto a `SERVICE` actor would misattribute it as system-acting, so use `unauthenticated(...)` for a `SERVICE` actor instead. Both factories assemble with `AuthorizationClaims.empty()` and no origin.
- None of these methods emit `SecurityEventObserver` events — a static method cannot invoke an injected emitter by construction, so consuming flows decide whether and how to audit.
- The concrete types built here are package-private; callers use `SecurityContexts` and the `SecurityContext` interface, never a concrete record. `assembleReconstructed` is the only route to a non-empty `reconstruction()`.
- `assemble(...)` takes `authorization()` verbatim from its `claims` argument and makes no authority decision of its own. Reconstruction exploits this by passing `AuthorizationClaims.empty()`, so a reconstructed context carries no frozen authority (FR-ID-CA-010).

---

### PrincipalRef

Immutable reference to an authenticated or delegated principal.

```java
public record PrincipalRef(PrincipalType type, String id, Map<String, Object> attributes)
```

**Construction rules:** `type` and `id` are required (non-null; `id` non-blank). A null `attributes` map is treated as empty. The attributes map is defensively copied at construction.

**Durable capture caveat:** `attributes` (and the analogous attribute maps on `ClientRef` and `AuthorityClaim`) is request-scoped provenance only and is non-authoritative for identity — it does not survive identity-snapshot durable capture. Capture projects subject/client/claim attribute maps to empty unconditionally; the sole survivor is a bounded (≤ 256 chars) `system.reason` string on a `SYSTEM`-typed actor. Durable principal identity is the trust-domain-unique `(PrincipalType, id)` tuple (**FR-ID-CA-012**): applications using tenant-, issuer-, or realm-local identifiers must namespace or otherwise qualify `id`/`clientId` before constructing the ref — e.g. `new PrincipalRef(PrincipalType.USER, "urn:example:tenant:acme:user:01J…", Map.of("displayName", "alice"))` — because the framework treats ids as opaque and never reconstructs identity scope from attributes.

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

All `DelegationGrant` string components are required and non-blank; `grantor`, `grantee`, and `expiresAt` are required.

A grant answers "who may act, on whose behalf, over what, until when" — never "why", and it never carries the evidence that established it: `evidenceRef` is a **reference** (e.g. a consent-record id) pointing at evidence held elsewhere, never evidence content or credential material. Grant-backed delegation is carried on the existing `DelegationContext#authorityId()` — the grant id and nothing else; no parallel delegation representation is introduced.

`DelegationGrantDecision#grantId()` always echoes the grant id evaluated, even on a deny, so callers and audit output can correlate a decision to its grant. `expiryUsed()` carries the checked `expiresAt` when a grant was found, `Optional.empty()` otherwise. Evaluating whether a grant currently authorizes a triple is `DelegationGrantValidator`'s job — a `DelegationGrant` is a pure value.

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
- `ActionRef.parse(String)` splits with limit `-1`, so trailing dots (`"a.b."`) and interior double-dots (`"a..b"`) are rejected by the segment count or the empty-segment grammar check rather than being silently trimmed. A blank string is rejected outright.
- The `authz` subsystem prefix is reserved for framework built-in actions. Application subsystems must use a different leading segment.

---

### ActionPattern

Immutable pattern matched against an `ActionRef` during policy evaluation.

Two shapes:
- **Exact canonical** — exactly three valid `ActionRef` segments, e.g. `"cms.content.read"`. Matches the identical action only.
- **Trailing-suffix wildcard** — one or two valid leading segments followed by a terminal `"*"`, e.g. `"cms.content.*"` (any verb) or `"cms.*"` (any resource and verb).

```java
ActionPattern exact    = new ActionPattern("cms.content.read");
ActionPattern wildcard = new ActionPattern("cms.content.*");

exact.isWildcard();           // false
wildcard.isWildcard();        // true
wildcard.matches(read);       // true  (read = ActionRef.of("cms","content","read"))
```

#### Invariants & Gotchas

- A bare `"*"` with no leading segment is rejected — it would be a blanket allow.
- `*` is only valid as the **final** segment; `"cms.*.read"` is rejected.
- A pattern that could never match is rejected at construction rather than silently never firing: an exact pattern with a segment count other than three, and a wildcard pattern with more than three total segments (`"a.b.c.*"`), both throw `IllegalArgumentException`.

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

`securityContext`, `action` (non-blank), `resource`, and `origin` are required; a null `context` map is treated as empty and defensively copied.

`context` and `origin` are both **advisory input, never a trust source** — the authoritative identity and authorities always come from `securityContext`. Callers must not place sensitive data (tokens, credentials, PII) in either, since the engine may surface them in audit output.

---

### InvocationOrigin

Transport-neutral descriptor of the invocation/transport boundary an `AuthorizationRequest` was raised through — the authz-time counterpart to the pre-auth network envelope carried by `SecurityContext.origin()` (`RequestOrigin`). `RequestOrigin` captures network-envelope facts (peer IP, scheme, TLS) before authentication runs; `InvocationOrigin` identifies *which ingress kind* dispatched the request into authorization, so a policy may discriminate on it (e.g. deny a sensitive action invoked via an unattended integration boundary, permit it from an interactive REST session).

```java
public record InvocationOrigin(String kind, Map<String, Object> attributes) implements ContextValue {
    public static final int MAX_ATTRIBUTES = 16;
    public static final int MAX_VALUE_LENGTH = 1024;
    public static final String UNSPECIFIED_KIND = "unspecified";

    public static InvocationOrigin of(String kind);
    public static InvocationOrigin unspecified(); // kind = "unspecified"
}
```

`kind` is a non-blank, transport-neutral ingress identifier drawn from `DispatchBoundary` values (e.g. `"rest"`, `"external"`, `"delayed-job"`, `"kafka"`, `"workflow"`) — centralizing the string constants there avoids typos. Implements `ContextValue` so an ingress boundary can bind a real `InvocationOrigin` as the ambient invocation origin on `ContextHolder`, and downstream authorization/snapshot-capture code reads it back via `contextHolder.current(InvocationOrigin.class)`. A caller that doesn't seed one gets `unspecified()`.

Service dispatch is root-ingress-aware rather than an unconditional overwrite: an already-present upstream origin is preserved, a propagated `DeferredExecutionOrigin` is mapped to an origin of the same `kind()` so deferred work stays distinguishable, and only when neither is present does it seed its own boundary. See `dev.vertique:vertique-services` for the full precedence.

**Invariants & Gotchas:**

- `attributes` is advisory input, never a trust source — mirrors `AuthorizationRequest#context()`. Must not carry sensitive data; the engine may surface it in audit output.
- `attributes` is bounded to `MAX_ATTRIBUTES` (16) entries, each value's `String.valueOf(...)` form bounded to `MAX_VALUE_LENGTH` (1024) characters. Exceeding either throws `IllegalArgumentException`.
- The identity-snapshot capture path's `originSummary` reads the ambient `InvocationOrigin`'s `kind()`.

---

### Authorizer

Transport-neutral authorization engine. Returns a `Future<AuthorizationDecision>` — always a succeeded future. Denials are reported as decisions with `permitted == false`; the engine never throws to signal a deny and never emits authorization events (emission belongs to each surface's enforcement point).

```java
public interface Authorizer {
    Future<AuthorizationDecision> authorize(AuthorizationRequest request);
    Future<AuthorizationDecision> authorize(SecurityContext ctx, ActionRef action, ResourceRef resource);
}
```

The convenience overload accepts a `null` `ctx` and fails closed with a deny carrying `AuthzReasonCodes.INTERNAL_AUTHZ_ERROR` rather than throwing. It builds an `AuthorizationRequest` with an empty context map and `InvocationOrigin.unspecified()` — pass the 5-arg `AuthorizationRequest` directly when the ingress boundary matters.

---

### AuthorizationIntrospector

Set-valued inverse of `Authorizer`: answers "which registered actions are permitted for this actor?". Used to back capability-style introspection endpoints.

```java
public interface AuthorizationIntrospector {
    Set<ActionRef> allowedActions(SecurityContext ctx);
    Set<ActionCapability> capabilities(SecurityContext ctx);
}
```

**Agreement invariant:** the framework's implementation is built on the same resolved policy state as the default `Authorizer`, so the two surfaces cannot diverge: an action is in `allowedActions` iff the authorizer permits it.

**`capabilities(ctx)`** pairs each allowed action with **every** `RequirementDescriptor` an installed `AuthorizationNarrower` reports for it — not merely the first, since two independent narrowers may each gate the same action for unrelated reasons. Membership is identical to `allowedActions`: `capabilities` only *annotates*, never filters, so `allowedActions(ctx)` always equals `capabilities(ctx).stream().map(ActionCapability::action)`. With no narrowers installed, every `ActionCapability` carries an empty requirement set.

**Unsupported for a reconstructed context.** Both methods throw `ReconstructedContextIntrospectionUnsupportedException` when `ctx.reconstruction()` is present, *before* consulting the base introspector or any narrower — a reconstructed context's current authority is resolved live, at authorize-time, so a synchronous answer computed from its currently-held claims could silently disagree with what `authorize()` would decide for the same actor. Call `Authorizer#authorize(AuthorizationRequest)` for the specific action instead.

---

### ActionCapability, RequirementDescriptor

Value types backing `AuthorizationIntrospector.capabilities(SecurityContext)`.

```java
public record RequirementDescriptor(String kind, String detail)

public record ActionCapability(ActionRef action, Set<RequirementDescriptor> requirements)
```

`RequirementDescriptor` is an audit-safe description of a requirement an `AuthorizationNarrower` places on an action for a given actor — `kind` a stable, non-blank machine-readable category (e.g. `"delegation"`, `"ASSURANCE"`), `detail` a human-readable description. Like `AuthorizationDecision#safeAttributes()`, both are intended to be shown to a caller (e.g. in a capability-introspection response), so implementations must never place sensitive data (tokens, credentials, PII) in either.

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

`reasonCode` is required and non-blank; `policyId` and `policyVersion` are required `Optional` references; a null `safeAttributes` map is treated as empty and defensively copied.

Convenience factories: `AuthorizationDecision.permit(reasonCode)`, `AuthorizationDecision.deny(reasonCode)`.

**`safeAttributes` contract:** this map is intended for audit logs and error responses — callers must not place sensitive data in it and must not read it back as a trust source for a subsequent decision.

---

### AuthzReasonCodes

Constants holder (not an enum, not instantiable) for machine-readable reason codes on `AuthorizationDecision`. The set is a deliberate superset of what the default engine produces, reserving codes for enforcement-layer concerns.

| Constant | Produced by |
|----------|-------------|
| `PERMITTED` | Default engine — action allowed |
| `ROLE_MISSING` | Default engine — no `ROLE` claim present |
| `ROLE_POLICY_MISSING` | Default engine — roles map to no policy name |
| `POLICY_NOT_FOUND` | Default engine — a mapped policy name is absent from the catalogue |
| `ACTION_NOT_REGISTERED` | Default engine — action absent from `ActionRegistry` |
| `ACTION_NOT_ALLOWED` | Default engine — policies resolved but none allows the action |
| `INTERNAL_AUTHZ_ERROR` | Default engine — unparseable action string, null context, or unexpected error; fails closed |
| `AUTHENTICATION_REQUIRED` | Enforcement layer (reserved) |
| `STEP_UP_REQUIRED` | Opt-in `AssuranceRequirementNarrower` (`dev.vertique:vertique-security-runtime`) — assurance-gated action, unmet minimum-assurance requirement |
| `DENY_ALL` | Enforcement layer (reserved) |
| `SCOPE_MISSING` / `SCOPE_INSUFFICIENT` | Enforcement layer (reserved) |
| `POLICY_INVALID` / `INSTANCE_ELIGIBILITY_FAILED` | Future evaluators (reserved) |
| `AUTHORITY_RESOLUTION_FAILED` | Opt-in live authority re-resolution (Mode 2, `dev.vertique:vertique-security-runtime`) — `PrincipalAuthorityResolver` failed, timed out, or returned an ambiguous result |

A narrower may additionally surface a `DelegationReasonCodes` value as a decision's `reasonCode`.

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

`value()` has **no default** — it is mandatory.

**AND-composition:** `@RequiresAction` AND-composes with `@RolesAllowed` and `@Authorized` — both the role/scope gate and the action gate must pass. Combining with `@PermitAll` or `@DenyAll` is a conflict rejected at compile time and at startup.

**Fail-closed invariant:** any surface that does not enforce `@RequiresAction` must reject its presence at startup. An unenforceable annotation is a startup error, never silently ignored.

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
| `IdentitySnapshotDegradationEvent` | A durably-carried `IdentitySnapshot` is present but cannot be reconstructed (bad HMAC, unknown/unavailable key, decode failure, incompatible schema version, or a stale/malformed temporal envelope) — or a `CarriageRequirement#REQUIRED` durable target dispatched with no snapshot at all (`EXPECTED_ABSENT`); carries a non-blank `reasonCode` and a best-effort recovered `origin` |
| `CapturedAuthorityActivatedEvent` | Mode-3 captured authority is actually put into effect — a durably-captured snapshot's frozen claims are presented as a reconstructed context's current authority; carries the reconstructed identity **uncollapsed**, the activated authorization claims, the activation mode, a per-activation id, and the signed carrier binding |

`AuthorizationDecisionEvent` carries two derived accessors beyond its record components. `invocationOrigin()` surfaces the embedded request's `InvocationOrigin`. `authorityMode()` derives the governing `ReconstructedAuthorityMode` in three steps: an explicit `ReconstructedAuthorityMode.DECISION_ATTRIBUTE` stamp on the decision's `safeAttributes()` (the only source for `LIVE_RESOLVED`, which a marker can never carry), else the request context's intrinsic `reconstruction()` marker mode, else `Optional.empty()`. An unrecognized stamped value yields `Optional.empty()` rather than throwing.

`CapturedAuthorityActivatedEvent` carries the facts an audit consumer needs to attribute a privileged Mode-3 activation, and deliberately does **not** pre-collapse them:

```java
public record CapturedAuthorityActivatedEvent(
    Instant occurredAt, CorrelationContext correlation, Optional<RequestOrigin> origin,
    AuthenticationState authentication, SecurityIdentity identity, AuthorizationClaims authorization,
    CapturedAuthorityActivatedEvent.Mode mode, UUID activationId, SnapshotCarrierBinding carrier)

public enum Mode { RESUME, DEFERRED }
```

- **`identity`** — the whole reconstructed `SecurityIdentity`. On the deferred path `actor()` is the executing service, `subject()` is the captured subject-of-record, and `delegation()` carries the framework-mediated `DEFERRED_EXECUTION_KIND`; on the resume path actor and subject both come from the snapshot's own content. A consumer needing "whose authority is in effect" collapses it itself with `identity().subject().orElse(identity().actor())`.
- **`authentication`** — the reconstructed `AuthenticationState`. Its `primaryMethod()` is the *original captured* method, never a reconstruction marker; the `CAPTURED-RESUME` / `CAPTURED-DEFERRED` marker lives in `safeAttributes()` under `identity.reconstructed.mode`. It is **credential-free by construction** — `evidence()` is always empty and `tokens()` always `Optional.empty()`, because an `IdentitySnapshot` carries no evidence or token component. Observers are arbitrary application code; a `CapturedAuthorityReconstruction` implementation hand-wired outside `CapturedAuthorityReconstructionModule` is solely responsible for upholding that projection.
- **`authorization`** — the captured authority actually put into effect: the frozen claim set installed as the reconstructed context's *current* `authorization()`. Without it a record could state that a privileged activation occurred and for whom, but not which privileges it granted.
- **`mode`** — the typed activation entry point, validated non-null by the compact constructor. Prefer it over the `safeAttributes` marker: `AuthenticationState` copies that map without validating any key, so only the typed component is an enforceable invariant.
- **`activationId`** — minted fresh per activation, so two activations of the same durable row stay distinguishable as audit source events (a safe deduplication key).
- **`carrier`** — the whole signed `SnapshotCarrierBinding` (`carrierId` plus `target`), kept as one unit rather than split, because the binding composes them as a single signed, validated fact.

Delivery is **accepted-for-delivery**, not durable: awaiting the activation seam guarantees emission ordering and that every observer settled, never that any observer persisted the event.

`ChannelLifecycleEvent` is a sealed interface exposing `occurredAt()`, `channelId()`, `securityContext()`, and `correlation()`; use a sealed `switch` to dispatch on subtypes:

```java
switch (event) {
    case ChannelOpenedEvent e            -> handleOpened(e);
    case ChannelIdentityRefreshedEvent e -> handleRefreshed(e);
    case ChannelClosedEvent e            -> handleClosed(e);
}
```

---

### IdentitySnapshot, IdentitySnapshotContent, SnapshotCarrierBinding, SnapshotIntegrity, DelegationSummary

Schema v2 durable carriage of a `SecurityContext`'s full identity dimension, for carrying identity across a durability boundary (a scheduled job, an outbox relay, or a workflow resume) without ever carrying live credential material. Schema v2 splits the captured identity dimension (`IdentitySnapshotContent`) from the signed, row-bound envelope (`IdentitySnapshot`) that carries it — the replay defense.

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

| Type | Role |
|---|---|
| `IdentitySnapshot` | The signed durable envelope. `schemaVersion` is `2` and must be strictly positive; rejecting an unknown-newer version is the runtime codec's job, not this constructor's |
| `IdentitySnapshotContent` | The captured identity dimension plus the immutable `capturedAt` instant, with deliberately **no** evidence or token component — `authenticationMethodKind()` records only the normalized kind of the original method, never the credential |
| `SnapshotCarrierBinding` | `carrierId` is a framework-generated, non-blank identity allocated *before* the durable row is persisted (a delayed-job execution UUID, an outbox `carrier_id`, a workflow-timer id); `target` (a `DurableTarget` from `dev.vertique.core.context`) names the durable destination. Both are signed, so reconstruction can confirm the snapshot is reinstated against the carrier it was encoded for rather than a replayed or transplanted one |
| `SnapshotIntegrity` | A base64url `tag` bound to `keyId` and `algorithm`, all three required and non-blank. `keyId` addresses the signing key so a keyset can rotate (active + previous) |
| `DelegationSummary` | Narrowed to a non-blank `kind` and an optional `authorityId` — no delegation reason, no free-form attributes |

#### Invariants & Gotchas

- Every `IdentitySnapshot` carries a mandatory `integrity()` envelope. There is no unsigned mode at the type level.
- A `null` `authorizationClaims` list is treated as empty and defensively copied; `subject`, `delegation`, `client`, and `assurance` are required (non-null) `Optional` references. Within `assurance`, `AuthenticationAssurance.amr()` is a `SequencedSet<String>` that preserves first-observed encounter order, and that order is load-bearing for cross-process durable-snapshot integrity: the integrity tag signs the serialized `amr` array, so a decoded snapshot must re-serialize its stored element order byte-for-byte to verify on another node.
- **Content/envelope split.** `IdentitySnapshotFactory.capture(...)` returns `IdentitySnapshotContent` only — capture cannot legitimately produce a carrier binding, temporal bounds, or a signature. Capturing or carrying a snapshot never by itself grants or removes authorization standing; only reconstruction acts on its contents.
- **Freshness is a three-term minimum, re-checked at both decode and reconstruction.** The runtime rejects a snapshot past `min(expiresAt, issuedAt + maxCarrierLifetime, content.capturedAt + maxSnapshotLifetime)` — the latter two terms only when an operator configured that budget. The snapshot-lifetime term is anchored on the *immutable* `content.capturedAt()`, never `issuedAt`, so a chained re-encode (which mints a fresh `issuedAt` for a new carrier) cannot renew authority past what the original capture earned. Reconstruction re-evaluates freshness against the current clock, so a snapshot that decoded fresh but has since aged out while held in memory is still rejected.
- **Carrier binding is enforced again at reconstruction**, independently of the decode-time check: a signed `carrier()` that does not match the receive-side's expected carrier is rejected fail-closed.
- `authorizationClaims` are **attribution only** — audit lineage of what the principal was authorized for at capture time. Under the default mode they are never restored as current authority (a reconstructed `authorization()` is empty); see `IdentityReconstruction` below (FR-ID-CA-010).

---

### CarriageRequirement

Operator-declared expectation for whether a durable dispatch target must carry a verified identity snapshot — the config-side half of the expected-but-absent carriage detection.

```java
public enum CarriageRequirement { REQUIRED, OPTIONAL, FORBIDDEN }
```

| Value | Meaning |
|-------|---------|
| `REQUIRED` | Every dispatch on this durable target must carry a verified snapshot; a dispatch that arrives with none is an `EXPECTED_ABSENT` degradation (`SnapshotDegradationReason`), not a silently-tolerated gap. Declaring any target-kind `REQUIRED` forces both `maxCarrierLifetimeMs` and `maxSnapshotLifetimeMs` to be configured — a never-expiring `REQUIRED` snapshot would otherwise be a standing bearer credential. |
| `OPTIONAL` | Carriage may be absent on this durable target — the default for an unlisted target-kind. |
| `FORBIDDEN` | This durable target never carries identity (e.g. a system-scheduled cron trigger with no originating principal) — an absent snapshot is expected and never flagged. A **present** snapshot on a `FORBIDDEN` target — verified or not — is refused fail-closed rather than reconstructed, since the target is declared to never carry identity and verification status is irrelevant once one arrives at all. |

Resolved per durable-target kind by the runtime configuration, keyed on the `DeferredExecutionOrigin#kind()` / durable-target-kind string (e.g. `"delayed-job"`, `"cron"`, `"outbox-relay"`); an unlisted kind resolves to `OPTIONAL`.

---

### SnapshotDegradationReason, SnapshotDegradationMarker

`SnapshotDegradationReason` is the typed classification of why a durably-carried snapshot could not be used:

| Value | Meaning |
|-------|---------|
| `BAD_HMAC` | Integrity tag did not verify |
| `UNKNOWN_KEY` | Tag names a `keyId` absent from the configured keyset |
| `KEY_UNAVAILABLE` | No key material configured at all |
| `DECODE_FAILED` | Malformed payload, or a carrier mismatch |
| `SCHEMA_INCOMPATIBLE` | Snapshot's `schemaVersion` is newer than this runtime supports |
| `EXPIRED` | Past the effective expiry (three-term minimum above) |
| `MALFORMED_TEMPORAL` | Temporal envelope is impossible or future-dated beyond the tolerated clock skew |
| `EXPECTED_ABSENT` | A `CarriageRequirement#REQUIRED` target dispatched with no snapshot bound at all |

`SnapshotDegradationMarker` is the `ContextValue` handoff type binding a present-but-unusable durable identity snapshot to the async degradation gate.

```java
public record SnapshotDegradationMarker(String reasonCode, Optional<RequestOrigin> origin) implements ContextValue
```

The receive-side initializer runs synchronously and cannot itself emit the non-droppable `IdentitySnapshotDegradationEvent` (emission is async). Instead it *detects and binds* this marker onto the `ContextHolder` when a carried snapshot fails HMAC/freshness/carrier verification, cannot be reconstructed, or is expected-but-absent on a `CarriageRequirement#REQUIRED` target. A separate async degradation gate — hosted in `dev.vertique:vertique-services` — reads the marker, emits the event, and applies the configured `FAIL` / `CONTINUE_WITHOUT_IDENTITY` policy. `reasonCode` is a non-blank `SnapshotDegradationReason` name.

---

### ReconstructedAuthorityMode, ReconstructionMarker

Select and carry how a reconstructed `SecurityContext`'s current authority is determined.

```java
public enum ReconstructedAuthorityMode {
    ATTRIBUTION_ONLY, LIVE_RESOLVED, CAPTURED;
    public static final String DECISION_ATTRIBUTE = "authz.authority.mode";
}

public record ReconstructionMarker(ReconstructedAuthorityMode mode)
```

`ReconstructionMarker` is the typed, **unforgeable** verified-reconstruction signal `SecurityContext.reconstruction()` returns — present only for a context produced by the framework's verified-reconstruction path or the Mode-2 authorizer's evaluation-context rebuild, both of which go through `SecurityContexts.assembleReconstructed(...)`. Any code deciding whether a context is a verified reconstruction MUST key off this typed accessor — **never** the descriptive `identity.reconstructed=true` string attribute on `AuthenticationState#safeAttributes()`, which any code populating `safeAttributes` could set on an otherwise live-authored context.

**Carries no principal of its own.** A Mode-2 authorizer derives the `PrincipalKey` to re-resolve from `SecurityContext#identity()#actor()` — never from this marker and never from `SecurityIdentity#subject()` (FR-ID-DG-006).

`ReconstructedAuthorityMode` values:

| Value | Meaning |
|-------|---------|
| `ATTRIBUTION_ONLY` | Default: the snapshot's captured claims are audit lineage only; `SecurityContext#authorization()` is always `AuthorizationClaims.empty()`. |
| `LIVE_RESOLVED` | An **evaluation outcome**, never a marker value — current authority was re-resolved live via `PrincipalAuthorityResolver` (Mode 2). Must never appear as a `ReconstructionMarker#mode()`; the compact constructor throws `IllegalArgumentException` if it is passed. |
| `CAPTURED` | Mode 3: the snapshot's captured claims are trusted and carried forward as-is as current authority. |

`ReconstructedAuthorityMode.DECISION_ATTRIBUTE` (`"authz.authority.mode"`) is the `AuthorizationDecision#safeAttributes()` key an authority-mode decorator stamps, so an embedding `AuthorizationDecisionEvent` can distinguish which strategy produced a given decision — see `AuthorizationDecisionEvent#authorityMode()` above.

---

### VerificationSource (sealed)

Sealed interface discriminating the mechanism used to verify an authentication credential. Carried in `CredentialAcceptedEvent`, Jackson-polymorphic on a `"type"` property, and safe to persist in audit records.

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

`ActionDefinition` is `record ActionDefinition(ActionRef ref)`.

```java
@Singleton
public final class CmsActionContributor implements ActionContributor {
    private static final List<ActionDefinition> ACTIONS = List.of(
        new ActionDefinition(ActionRef.of("cms", "content", "read")),
        new ActionDefinition(ActionRef.of("cms", "content", "write")));

    @Inject CmsActionContributor() {}

    @Override
    public Collection<ActionDefinition> actions() { return ACTIONS; }
}

// In a Dagger module
@Provides @IntoSet
static ActionContributor cmsActions(CmsActionContributor c) { return c; }
```

---

### PolicyDefinitionSource

Modules or applications implement this SPI to contribute named `PolicyDefinition`s to the authorization engine. Called once at startup; the default `validateAgainst(ActionRegistry)` implementation validates all contributed patterns against the registry — exact patterns must be registered, wildcards must match at least one action. A violation throws `IllegalStateException` naming the policy and the offending pattern.

```java
public interface PolicyDefinitionSource {
    Collection<PolicyDefinition> policies();
    default void validateAgainst(ActionRegistry registry) { /* validates all patterns */ }
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

`narrow(request, base)` participates in the async per-request decision path: given the decision so far (the base verdict, or a previous narrower's result), it may turn a permit into a deny or annotate an existing deny, but **must never turn a deny into a permit**. `requirementFor(ctx, action)` participates in the sync per-action introspection path, independent of any specific resource: it reports whether this narrower places an additional, describable condition on the action for the given actor — annotating a capability rather than removing it.

Multiple installed narrowers fold in `OrderedExtension#comparator()` order (phase, then ascending `priority()`, then `orderKey()`). Two narrowers sharing the same `(priority, orderKey)` pair fail startup naming both. With no narrowers installed, the composed pair is behavior-identical to the base engine.

Two framework-shipped narrowers ship behind their own opt-in Dagger module — see `dev.vertique:vertique-security-runtime` for `DelegationEnforcementNarrower` (priority 100) and `AssuranceRequirementNarrower` (priority 200).

#### Invariants & Gotchas

- **No-widen guard.** The runtime composition enforces the no-widen invariant structurally: a candidate decision that widens a current deny into a permit is discarded and the current deny is kept, with a framework-integrity error logged naming the offending narrower.
- **Never return `null`.** A `null` decision from `narrow` fails the fold with a `NullPointerException` naming the offending narrower.
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

#### Invariants & Gotchas

- **A permit must echo the requested `grantId`.** The framework's delegation narrower discards a permitted `DelegationGrantDecision` whose `grantId()` does not equal the `grantId` it asked about, replacing it with a deny carrying `GRANT_LOOKUP_FAILED`. An implementation that permits while echoing a different (or empty) grant id therefore grants nothing.
- Returning a *failed* `Future` is a contract violation, not a deny signal — implementations catch their own storage exceptions and map them to `GRANT_LOOKUP_FAILED`.
- Principal matching should compare only `(PrincipalType, id)`, never `attributes` (**FR-ID-CA-012**).

Expiry and scope checks ship in the framework's `InMemoryDelegationGrantValidator` default (`dev.vertique:vertique-security-runtime`); storage and revocation lookup live behind this seam for consumer-owned durable implementations.

---

### SecurityIdentityResolver

Chain-of-responsibility SPI for resolving a `SecurityIdentity` from accumulated authentication evidence. Implements `OrderedExtension` — ordered by phase, then ascending `priority()`, then `orderKey()`.

```java
public interface SecurityIdentityResolver extends OrderedExtension {
    @Override default int priority()    { return 100; } // < 100 runs before framework defaults
    default String id()                 { return getClass().getName(); }
    @Override default String orderKey() { return id(); }

    Future<Optional<SecurityIdentity>> resolve(SecurityIdentityResolutionContext context);
}
```

`orderKey()` delegates to `id()`, so overriding `id()` alone changes the ordering tiebreak.

Return `Optional.empty()` to pass control to the next resolver in the chain. Return a failed `Future` only for genuine errors — it propagates to the caller and does not advance the chain.

---

### ChannelIdentityManager

Per-runtime registry SPI for long-lived connection identity management (WebSocket, SSE, future transports). The default implementation lives in `dev.vertique:vertique-rest-security`. Transport-specific bindings live in their owning modules.

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

### SecurityEventObserver

SPI for observing security lifecycle events. All methods are `default` returning `Future.succeededFuture()` — implementations opt in only to the events they need.

```java
public interface SecurityEventObserver {
    default Future<Void> onCredentialAccepted(CredentialAcceptedEvent event)                 { ... }
    default Future<Void> onCredentialRejected(CredentialRejectedEvent event)                 { ... }
    default Future<Void> onAuthorizationDecided(AuthorizationDecisionEvent event)            { ... }
    default Future<Void> onChannelLifecycle(ChannelLifecycleEvent event)                     { ... }
    default Future<Void> onIdentitySnapshotDegradation(IdentitySnapshotDegradationEvent e)   { ... }
    default Future<Void> onCapturedAuthorityActivated(CapturedAuthorityActivatedEvent event) { ... }
}
```

**Failure isolation:** one observer's failure must not prevent other observers from receiving the event and must not alter the authentication or authorization result that produced it. The `SecurityEventEmitter` (in `dev.vertique:vertique-security-runtime`) enforces this — a synchronous throw, a returned `null`, and an asynchronous failure are each caught, logged at WARN, and treated as settled.

**Threading and long-running work:** the emitter does **not** force-offload observer work — every observer method runs on whichever thread emitted the event. For the request-driven families that is normally a Vert.x event loop, but `CapturedAuthorityActivatedEvent` is emitted by the Mode-3 activation seam, which job, workflow, and outbox-relay resume paths invoke from their own threads. An observer must therefore never assume an event-loop context, and must offload blocking or CPU-intensive work itself:

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

### PrincipalAuthorityResolver

Application-implemented SPI that re-resolves a reconstructed context's **acting principal's** current authority from its durable key alone — Mode 2 of the reconstructed-context authorization model.

```java
public interface PrincipalAuthorityResolver {
    Future<AuthorizationClaims> resolve(PrincipalKey key);
}
```

`PrincipalKey` (`record PrincipalKey(PrincipalType type, String id)`, `id` non-blank) is the capability-minimized durable principal key: it structurally carries **no attributes**, so an implementation cannot resolve scope from request-scoped attributes even by accident — it must consult durable, principal-keyed authority storage (a role/entitlement store, an IdP claim cache).

**Resolves the actor, never the subject-of-record.** Per **FR-ID-DG-006**, the Mode-2 authorizer derives the key exclusively from `SecurityContext#identity()#actor()`: for `resumeAsPrincipal` that is the resumed principal, for `deferredExecution` the executing service. `SecurityIdentity#subject()` is never passed to this SPI — impersonation is out of v1 scope; a downstream `AuthorizationNarrower` may still intersect the resolved actor authority against a grant's scope.

**Bounded and fail-closed.** The runtime wraps every installed resolver in a timeout decorator (`identity.authz.resolutionTimeoutMs`, default 5000ms), so a delegate whose `Future` never completes cannot hang authorization. The timeout does **not** cancel the delegate's work — a resolver performing I/O should also set a transport-level timeout. Any failure, timeout, or ambiguous result becomes a deny with `AuthzReasonCodes#AUTHORITY_RESOLUTION_FAILED`, never a fallback to the snapshot's captured claims. A *resolvable* principal holding no authority succeeds with `AuthorizationClaims.empty()` — a normal downstream deny, distinct from a resolution failure.

Binding a `PrincipalAuthorityResolver` is the Mode-2 opt-in itself — see `dev.vertique:vertique-security-runtime`, which also ships an in-memory reference implementation.

---

### IdentityReconstruction

Privileged SPI that reconstructs a `SecurityContext` from a credential-free `IdentitySnapshot` captured across a durability boundary — the identity-minting trust boundary of the snapshot pipeline. The implementation is provided only by a dedicated privileged Dagger module in `dev.vertique:vertique-security-runtime`; install it only on framework infrastructure components (job execution, inbox/outbox, workflow resume), never on general request-handling components.

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

`expectedCarrier` (`DurableCarrierDescriptor`, from `dev.vertique.core.context`) is the durable row-carrier the receiving dispatch was actually written for — the trusted receive-side fact reconstruction checks the snapshot's signed `carrier()` against (same `carrierId` and `target`, or reconstruction fails closed).

#### Invariants & Gotchas

- **Fail-closed, never partial.** Both entry points re-verify integrity *and* current freshness and confirm the signed `carrier()` matches `expectedCarrier` before minting anything. A `null` snapshot, a failed verification, or a carrier mismatch throws `IdentityReconstructionException` before any `SecurityContext` is built — there is never a partial or unbound result.
- **Reconstructed authority is attribution-only** (FR-ID-CA-010). Every context these methods mint has `authorization()` == `AuthorizationClaims.empty()` and marker mode `ATTRIBUTION_ONLY`, so a principal demoted or revoked during the pause never resumes with frozen standing. Live re-resolution (Mode 2) and captured authority (Mode 3) are opt-in layers on top of this default.
- **`isReconstructed(ctx)` is a descriptive convenience, not a trust decision.** It reads the `identity.reconstructed` string attribute from `ctx.authentication().safeAttributes()`, which any code populating `safeAttributes` can set. Never use it to decide authority; use `SecurityContext#reconstruction()`, whose marker only the framework's reconstruction path can produce.
- **`IdentityReconstructionException`** carries a typed `reason()` (`SnapshotDegradationReason`) classifying which failure occurred, so a caller can bind a precise `SnapshotDegradationMarker` rather than a catch-all code. A `null` snapshot or a carrier mismatch defaults to `DECODE_FAILED`.
- The reconstruction marker attributes (`identity.reconstructed`, `.mode`, `.capturedAt`, `.authenticatedAt`) are purely descriptive of how the authentication state was produced; setting them grants nothing. `.authenticatedAt` is the one a chained re-capture reads back, so the original authentication instant survives each hop.

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

This is a **distinct privileged type**, deliberately not a third method on `IdentityReconstruction`: Mode 1 always mints an empty `authorization()` and Mode 3 inverts that guarantee, so it lives on its own interface wired only by its own opt-in Dagger module — never as a fallback binding. Enabling Mode 3 means explicitly installing that module, which is visible at code review.

Both entry points share `IdentityReconstruction`'s fail-closed trust boundary and additionally enforce a **per-target-kind allowlist**: the snapshot's durable target kind must be one of the configured allowed kinds, each required by startup validation to carry `CarriageRequirement#REQUIRED`, or reconstruction fails closed. A captured-authority target whose carriage is merely `OPTIONAL`/`FORBIDDEN` would let an attacker suppress the snapshot and dispatch un-authorized, or would be a standing bearer-credential hole.

`deferredExecutionWithCapturedAuthority` preserves the actor/subject split used by `IdentityReconstruction#deferredExecution` — the executing service is always the acting principal, the snapshot's subject-of-record is carried as the delegated subject, never impersonated. Deferred-execution infrastructure MUST use this method, never `resumeWithCapturedAuthority`.

**Event-silent.** Like `IdentityReconstruction`, this interface emits no `SecurityEventObserver` event — a plain interface method cannot invoke an injected emitter by construction. The sanctioned activation seam in `dev.vertique:vertique-security-runtime` is the only binding an application can *inject*; it invokes this SPI and then emits and awaits `CapturedAuthorityActivatedEvent` once reconstruction has actually succeeded. The event carries the reconstructed `SecurityIdentity` whole plus the claim set the activation put into effect, so the actor/subject split this SPI establishes — and what it authorized — survives all the way to the observer. The guarantee is scoped to injection: this interface is public, so application code that hand-wires its own implementation and calls it directly reconstructs without any event.

---

## Exceptions

| Exception | Root | Thrown when |
|---|---|---|
| `IdentityReconstructionException` | framework security exception | A snapshot fails integrity, freshness, carrier, or allowlist verification, or is `null` — carries a typed `reason()` |
| `ReconstructedContextIntrospectionUnsupportedException` | business-rule exception | `allowedActions`/`capabilities` is called with a context whose `reconstruction()` is present |
| `IdentityResolutionException` | framework security exception | A `SecurityIdentityResolver` chain fails to resolve an identity — carries an `IdentityResolutionError` (`AMBIGUOUS_CLIENT_ID`, `INVALID_DELEGATION`, `UNSUPPORTED_PRINCIPAL_CLASSIFICATION`, `UNDERIVABLE_PRINCIPAL_ID`) |

Value-type construction violations (bad action grammar, blank required components, oversized `InvocationOrigin` attributes, `LIVE_RESOLVED` passed to `ReconstructionMarker`) throw `IllegalArgumentException` from the owning compact constructor.

---

## Dependencies

- `dev.vertique:vertique-core` — `ContextValue` (for `SecurityContext`), `CorrelationContext`, `OrderedExtension`, `DurableTarget`/`DurableCarrierDescriptor`, exception roots
- `io.vertx:vertx-core` — `Future` (SPI method return types)
- `com.fasterxml.jackson.core:jackson-databind` — polymorphic `VerificationSource` serialization, plus the `jsr310` and `jdk8` datatype modules for `Instant` and `Optional` components on the event and snapshot records
- `jakarta.annotation:jakarta.annotation-api` — `@Nullable`
