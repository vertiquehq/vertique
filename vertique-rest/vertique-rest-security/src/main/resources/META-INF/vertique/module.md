<!--
SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
SPDX-License-Identifier: EUPL-1.2
-->

# REST Security Module

> **Status:** Implemented
> **Package:** `dev.vertique.rest.security`
> **Artifact:** `rest-security`
> **Depends on:** rest-core, core

Authentication, identity resolution, and authorization for the REST framework. Bridges inbound
Vert.x authentication to the typed `SecurityContext` model, resolves `SecurityIdentity` from
accumulated `AuthenticationEvidence`, enforces authorization via `AuthorizationDecisionPoint`, and
emits security lifecycle events through `SecurityEventObserver`.

---

## Overview

`rest-security` provides:

- **`OriginCaptureMiddleware`** — ROOT-scoped pre-auth middleware that captures `RequestOrigin` from
  the inbound request
- **`IdentityResolutionMiddleware`** — per-route handler that runs the `SecurityIdentityResolver`
  chain, builds the `SecurityContext`, enriches MDC, and emits `CredentialAcceptedEvent`
- **`AuthorizationContributor`** — adds authorization handlers for `@RolesAllowed`/`@Authorized`
  operations via `SecurityPolicyEnforcer` and `AuthorizationDecisionPoint`
- **`CredentialRejectionReporter`** SPI — called by auth handlers on failure to emit
  `CredentialRejectedEvent`
- **`SecurityClaimMapper`** SPI — maps raw JWT/provider claims to typed `AuthorizationClaims`
- **`SecurityEventEmitter`** (from `vertique-security-runtime`, `dev.vertique.security.runtime.events`) — failure-isolated fan-out to `Set<SecurityEventObserver>`; the multibinding and singleton are owned by `SecurityEventsModule` and injected here
- **`DefaultChannelIdentityManager`** — in-memory `ChannelIdentityManager` for long-lived channels
- **`JaxRsSecurityContext`** — bridges framework `SecurityContext` to
  `jakarta.ws.rs.core.SecurityContext`
- **`DefaultSecurityPolicyValidator`** — startup annotation/OpenAPI consistency checker
- **`ActionGateAuthenticationContributor`** — installs an auth handler on action-only routes (`SecurityPolicy.None` + `@RequiresAction`) so the gate sees a real identity rather than anonymous
- **`AuthModule`** and **`SecurityModule`** — Dagger modules wiring all of the above

---

## Request Pipeline

The framework registers handlers for each JAX-RS operation in this order:

```
ROOT-scoped middleware (run at Router level for every request):
  1. RequestContextLifecycle    (order = Integer.MIN_VALUE — scope owner)
  2. CorrelationIngressMiddleware (order = ORDER + 10 from lifecycle)
  3. OriginCaptureMiddleware    (order = CorrelationIngressMiddleware.ORDER + 10 — captures RequestOrigin)
  4. RestRequestCompletionEmitter (order = RequestContextLifecycle.ORDER + 5, owned by rest-core; emits RestRequestCompletedEvent on response end)
  ... other ROOT middlewares ...

  → Auth handler(s) (e.g., JWTAuthHandler via SecuritySchemeHandler.configure(SecuritySchemeRegistry);
    ChainAuthHandler.any() for OR-scheme operations; sets ctx.user(), appends AuthenticationEvidence,
    calls CredentialRejectionReporter on failure)

Per-route handler chain:
  5. IdentityResolutionMiddleware  (priority 80 — resolves SecurityIdentity, binds SecurityContext,
                                    emits CredentialAcceptedEvent)
  6. AuthorizationHandler          (priority 100 — enforces @RolesAllowed/@Authorized + scope requirements
                                    folded from securityRequirementSets(), emits AuthorizationDecisionEvent)
  7. OperationIdCaptureContributor  (priority 350, owned by rest-core — stores operationId + route template for RestRequestCompletionEmitter)
  8. ResourceMethodInvoker         (always last — JAX-RS method invocation)
```

`IdentityResolutionMiddleware` is NOT a `Middleware` — it is added per-route via
`IdentityResolutionContributor` so it runs after the auth handler (which sets `ctx.user()` and
appends evidence) and before the authorization handler (which reads claims from the bound
`SecurityContext`).

---

## Security Model

### OR-of-AND-with-scopes

Operation security follows the OpenAPI semantics: the `securityRequirementSets()` list is an **OR** (any one set satisfying all its schemes authenticates the request); within a `SecurityRequirementSet` every listed `SecurityRequirement` must hold (**AND**); within a scheme every listed scope must hold (**AND**). An empty list means public (no authentication required).

```java
// rest-core, dev.vertique.rest.core.routing
public record SecurityRequirement(String schemeName, List<String> scopes) {}

public record SecurityRequirementSet(List<SecurityRequirement> schemes) {
    /** True when this set contains exactly one scheme (the only case V1 implements). */
    public boolean isSingleScheme() { return schemes.size() == 1; }
    /** True when at least one scheme in this set carries required scopes. */
    public boolean hasScopes() { return schemes.stream().anyMatch(r -> !r.scopes().isEmpty()); }
}
```

`RestOperationDescriptor.securityRequirementSets()` replaces the former `securityRequirements()`. The `SecuritySchemeAnnotationScanner` produces sets directly: a standalone `@SecurityRequirement(name)` → a single-scheme set; `@SecurityRequirement(combine={…})` → a multi-scheme AND-set; repeated / `@SecurityRequirements` / `@Operation(security=…)` → multiple sets (OR).

### Fail-closed matrix (V1)

The framework fails startup with `RestConfigurationException` on any configuration it cannot enforce safely. Supported configurations are: public (empty set list); single-scheme scopeless; multiple single-scheme scopeless OR alternatives. Everything else is rejected at startup — not silently degraded.

| Configuration | Outcome |
|---|---|
| Empty `securityRequirementSets()` | Public — no auth handler |
| Single-scheme set, no scopes | Auth handler installed; no scope check |
| Single-scheme set, with scopes | Auth handler + scope enforcement (see below) |
| Multiple single-scheme scopeless OR sets | `ChainAuthHandler.any()` — any one scheme sufficient |
| Multi-scheme AND-set (`combine()`) | `RestConfigurationException` at startup — deferred |
| Any OR alternative with scopes (multi-set with scopes) | `RestConfigurationException` at startup — deferred |
| Duplicate `schemeName()` across handlers | `RestConfigurationException` at startup |
| Both `@Authorized(scopes)` and `@SecurityRequirement` scopes on same operation | `RestConfigurationException` at startup — ambiguous |

### Scope enforcement unified with `@Authorized`

For the one scoped case V1 supports (single-scheme set with scopes), `EffectiveSecurityPolicy.fold` merges the set's scopes into the operation's `SecurityPolicy.Constrained(existingRoles, scopes, requireAllScopes=true)` at the route-registration site. Enforcement then flows through the existing `VertxProviderDecisionPoint` → `SecurityPolicyEnforcer` → `ctx.fail(403)` path — one mechanism, one 403, no parallel scope check. `@Authorized(scopes)` and `@SecurityRequirement` scopes are mutually exclusive per operation (both-scopes fails closed, see above). Roles from `@RolesAllowed` / `@Authorized(roles)` are preserved alongside the folded scopes.

---

## Key Classes

### SecurityIdentity and SecurityContext

The typed security model is defined in `dev.vertique.security`. See `dev.vertique:vertique-security-core` for the
full reference. At the REST layer, `IdentityResolutionMiddleware` builds an
`AuthenticatedSecurityContext` from:
- `SecurityIdentity` — resolved by the `SecurityIdentityResolver` chain
- `AuthenticationState` — built from accumulated `AuthenticationEvidence`
- `AuthorizationClaims` — built by `SecurityClaimMapper` from `ctx.user().principal()`
- `Optional<RequestOrigin>` — captured pre-auth by `OriginCaptureMiddleware`

### OriginCaptureMiddleware

ROOT-scoped `Middleware` (order = `CorrelationIngressMiddleware.ORDER + 10`) that captures
`RequestOrigin` before any authentication handler runs. Stashes the captured origin on the routing
context under `RequestOrigin.class.getName()`. Downstream `IdentityResolutionMiddleware` reads it
and incorporates it into the resolved `SecurityContext`.

`RequestOriginCapturer` applies the `RequestOriginConfig` trusted-proxy policy:
- XFF is always parsed for observability (AC-RO-3)
- `clientIp` uses the forwarded address only when the direct peer is in `trustedProxyCidrs`
- Forwarded scheme and host are trusted only when configured and the direct peer is trusted

### IdentityResolutionMiddleware

Per-route handler (priority 80, added by `IdentityResolutionContributor`) that:

1. Reads accumulated `AuthenticationEvidence` from `RestAuthenticationEvidence.get(ctx)`
2. Builds a `SecurityIdentityResolutionContext` (evidence + origin + correlation + empty transport
   attributes)
3. Runs the priority-ordered `SecurityIdentityResolver` chain — first non-empty result wins;
   exhausted chain falls back to `SecurityIdentity.anonymous()`
4. Builds `AuthorizationClaims` from `ctx.user().principal()` via `SecurityClaimMapper`
5. Constructs `AuthenticatedSecurityContext` and binds it via `SecurityRuntime.bindCurrent()`,
   registering the returned scope for LIFO cleanup with `RequestContextLifecycle`
6. When the injected `Optional<IdentitySnapshotCapture>` is present, captures an identity snapshot
   for durable carriage via `capture.captureFrom(securityContext)`, registering the returned bind
   scope for LIFO cleanup with the same `RequestContextLifecycle`
7. Enriches MDC (`userId`, `clientId`, `authMethod`) — absent components are never emitted as
   empty strings
8. Emits `CredentialAcceptedEvent` when evidence is non-empty (authenticated requests only)

#### Invariants & Gotchas

- Duplicate `(priority, id)` resolver pairs fail loudly at startup (`IllegalStateException`) —
  NFR-ID-003. This fires at Dagger construction time, not at first request.
- The `Optional<IdentitySnapshotCapture>` ingress capture hook is **inert unless identity-snapshot
  durable carriage is installed**: `SecurityModule` declares it `@BindsOptionalOf`, so the optional
  is empty (and the REST hot path pays nothing) until an application includes
  `IdentitySnapshotReconstructionModule` / `IdentitySnapshotCarriageModule` and configures
  `identity.snapshot.hmacKeys`. When present, `captureFrom(...)` gates the `identity.snapshot.captureEnabled`
  kill-switch itself. The convenience constructor that omits the capture argument (used by
  `WebSocketMount` and tests) passes `Optional.empty()`, so WebSocket/SSE ingress captures no
  snapshot (deferred — ADR-0164).
- `CredentialAcceptedEvent` is NOT emitted for anonymous requests (empty evidence). Anonymous
  requests produce a bound `SecurityContext` with `SecurityIdentity.anonymous()` but emit no event.
- `CredentialRejectedEvent` is emitted by the failing auth handler before `ctx.fail()` is called,
  NOT by this middleware. Once `ctx.fail()` is called, `IdentityResolutionMiddleware` is
  short-circuited.

### RestAuthenticationEvidence

Static utility class for auth handlers to append `AuthenticationEvidence` to a routing context.

```java
// In a Vert.x auth handler or SecuritySchemeHandler:
RestAuthenticationEvidence.append(ctx, new AuthenticationEvidence(
    method,
    Optional.of("credential-id-or-jti"),
    Instant.now(),                          // verifiedAt
    Optional.of(exp),                       // notAfter
    new JwksVerificationSource("https://idp.example.com/.well-known/jwks.json"),
    Map.of()
));
```

Evidence is stored as a list on `ctx.data()` under a well-known key. Multiple evidence entries are
supported for layered authentication (e.g., mTLS plus JWT).

### CredentialRejectionReporter

SPI that auth handlers call when credential verification fails. The default implementation
(`DefaultCredentialRejectionReporter`) assembles a `CredentialRejectedEvent` from the provided
parameters plus the pre-auth-bound `RequestOrigin` and ambient `CorrelationContext`, and emits it
immediately via the `SecurityEventsModule`-provided `SecurityEventEmitter` (`dev.vertique.security.runtime.events`).

```java
// In a custom SecuritySchemeHandler on failure:
rejectionReporter.report(
    ctx,
    DefaultAuthMethod.jwt(),
    Optional.of(jti),                     // stable, non-sensitive credential id
    Optional.of(new JwksVerificationSource(issuer)),
    "TOKEN_EXPIRED",                       // stable reason code
    Map.of("header_alg", headerAlg)       // safe, redacted context only
);
ctx.fail(401);
```

`safeAttributes` MUST NOT contain raw token material, raw API keys, raw passwords, or raw request
bodies.

### SecurityClaimMapper

Functional SPI for mapping raw JWT/provider claims to typed `AuthorizationClaims`. The default
implementation (`DefaultSecurityClaimMapper`) handles the most common JWT claim conventions:

| Claim | Produced authority kind |
|-------|------------------------|
| `roles` | `AuthorityKind.ROLE` |
| `scope` / `scp` | `AuthorityKind.SCOPE` |
| `permissions` | `AuthorityKind.PERMISSION` |

Both JSON-array (`["a", "b"]`) and space-delimited string (`"a b"`) formats are supported for all
claims.

Override by providing a custom binding in the application's Dagger module:

```java
@Provides
SecurityClaimMapper myClaimMapper() {
    return claims -> {
        // Example: Keycloak nested realm_access.roles
        List<String> roles = extractNestedRoles(claims, "realm_access", "roles");
        return AuthorizationClaims.of(AuthorityKind.ROLE, roles);
    };
}
```

### AuthorizationDecisionPoint

Async REST-layer SPI for authorization evaluation. Implementations receive a fully populated
`AuthorizationRequest` and return `Future<AuthorizationDecision>`. Per ADR-0114, implementations
must **not** emit `AuthorizationDecisionEvent` — the enforcement layer (`SecurityPolicyEnforcer`)
owns emission and emits exactly one event per attempt.

`SecurityPolicyEnforcer` selects the active decision point at construction time in priority order:
1. App-provided `AuthorizationDecisionPoint` override (`@BindsOptionalOf`)
2. App-provided sync `AuthorizationPolicy`, wrapped by `SyncPolicyDecisionPoint`
3. Default `VertxProviderDecisionPoint` (evaluates from `AuthorizationClaims`)

```java
// Async decision point for remote PDP:
// import dev.vertique.security.runtime.events.SecurityEventEmitter; — lives in vertique-security-runtime

@Provides
AuthorizationDecisionPoint remoteDecisionPoint(MyPdpClient client, SecurityEventEmitter emitter) {
    // NOTE: per ADR-0114 the enforcement layer (SecurityPolicyEnforcer) owns emission.
    // A custom AuthorizationDecisionPoint should return the decision without emitting;
    // SecurityPolicyEnforcer will emit exactly one AuthorizationDecisionEvent.
    return request -> client.evaluate(request.identity(), request.action(), request.resource())
            .map(allowed -> allowed
                    ? AuthorizationDecision.permit("REMOTE_PDP_ALLOWED")
                    : AuthorizationDecision.deny("REMOTE_PDP_DENIED"));
}
```

> **Migration note:** Applications using Vert.x `AuthorizationProvider` bindings must migrate
> authorization data to the `SecurityClaimMapper` → `AuthorizationClaims` path. Vert.x
> `AuthorizationProvider` chains are NOT consulted by the new `AuthorizationDecisionPoint`. A
> future adapter resolver is tracked in GitHub issue #73. See
> ADR-0064 for the rationale.

### SecurityEventEmitter

`SecurityEventEmitter` is defined and lives in `vertique-security-runtime` (`dev.vertique.security.runtime.events`).
`AuthModule` includes `SecurityEventsModule`, which declares the `Set<SecurityEventObserver>` multibinding
and makes the singleton emitter available for injection throughout the security stack.

### ActionGateAuthenticationContributor

`OperationHandlerContributor` (priority 40) that installs the selected `RouteAuthHandler` on a
JAX-RS route whose resolved policy is `SecurityPolicy.None` but which carries a `@RequiresAction`
gate. Without this contributor, an action-only route receives no OpenAPI security handler, so
`IdentityResolutionMiddleware` resolves an anonymous identity and the action gate silently evaluates
the wrong caller.

The handler is installed **only** when `SecurityPolicy.None` + `@RequiresAction` are both present.
Routes with `AuthenticatedOnly` or `Constrained` policies already get an auth handler from the
OpenAPI security scheme; installing a second one would double-authenticate. `PermitAll`/`DenyAll`
cannot combine with `@RequiresAction` (rejected at startup).

Priority 40 places it before:
- the JWT claims validator (priority 50, `rest-auth-jwt`)
- `IdentityResolutionContributor` (priority 80)
- `AuthorizationContributor` (priority 100)

#### Invariants & Gotchas

- Exactly one `RouteAuthHandler` must be registered. If none is registered, or more than one is
  registered (JAX-RS has no per-route scheme selector to disambiguate), startup fails with
  `IllegalStateException` — fail-closed by design.

### DefaultChannelIdentityManager

`@Singleton` `ChannelIdentityManager` implementation backed by a `ConcurrentHashMap`. Maintains
the registry of `(channelId → SecurityContext + ChannelBinding + optional expiry timer)`. For each
channel whose `AuthenticationState.earliestNotAfter()` is non-empty, schedules a raw Vert.x timer
that calls `closeChannel(channelId, "IDENTITY_EXPIRED")` on expiry.

**Channel lifecycle sequence:**

```
WebSocket open:
  WebSocketChannelAdapter.onOpen()
    → ChannelIdentityManager.register(channelId, ctx, binding)
    → emits ChannelOpenedEvent
    → schedules expiry timer if notAfter is present

Identity refresh (e.g., token rotation):
  ChannelIdentityManager.refreshIdentity(channelId, newCtx)
    → binding.rebind(newCtx)   // on channel's event loop
    → emits ChannelIdentityRefreshedEvent
    → reschedules expiry timer

Connection close (peer or server-initiated):
  WebSocket @OnClose runs (SecurityContext still bound)
  WebSocketChannelAdapter.onClose()
    → ChannelIdentityManager.deregister(channelId, fallbackReasonCode)
    → cancels expiry timer
    → emits ChannelClosedEvent
    → binding.releaseResources()   // scope released last
```

### JaxRsSecurityContext

Bridges framework `SecurityContext` to `jakarta.ws.rs.core.SecurityContext`:

```java
public class JaxRsSecurityContext implements jakarta.ws.rs.core.SecurityContext {
    public JaxRsSecurityContext(SecurityContext frameworkContext, boolean secure) { ... }
}
```

| JAX-RS Method | Framework Implementation |
|---|---|
| `getUserPrincipal()` | `identity().actor()` as `Principal` |
| `isUserInRole(role)` | `authorization().valuesOf(ROLE).contains(role)` |
| `isSecure()` | Based on HTTPS request |
| `getAuthenticationScheme()` | JWT→`"BEARER"`, BASIC→`"BASIC_AUTH"`, API_KEY→`"API_KEY"`, MTLS→`"CLIENT_CERT"`, CUSTOM/UNKNOWN→`"CUSTOM"` |

### SecurityPolicyEnforcer

Creates `Handler<RoutingContext>` instances that enforce `SecurityPolicy` authorization rules. Used by
`AuthorizationContributor` to decouple policy resolution from handler construction, enabling reuse
across JAX-RS and non-JAX-RS routes.

**Key methods:**

| Method | Purpose |
|---|---|
| `createHandler(SecurityPolicy)` | Role/scope enforcement only; equivalent to calling the two-argument form with `Optional.empty()` |
| `createHandler(SecurityPolicy, Optional<ActionRef>)` | AND-composes the role/scope gate with the `@RequiresAction` action gate into one handler that emits **exactly one** `AuthorizationDecisionEvent` per attempt (ADR-0113 / ADR-0114) |
| `createHandler(SecurityPolicy.Constrained, String)` | Constrained-policy handler with a context label for error messages |

Returns `null` for `SecurityPolicy.None` and `SecurityPolicy.PermitAll` (with no action); those
variants install no authorization handler.

**Emission ownership (ADR-0114).** The enforcer — not the decision point — owns every
`AuthorizationDecisionEvent`. This includes fail-closed short-circuits that historically emitted
nothing: `DenyAll` (reason `DENY_ALL`); missing/anonymous `SecurityContext` on `AuthenticatedOnly` or
`Constrained` routes (reason `AUTHENTICATION_REQUIRED`); decision-point failure (reason
`INTERNAL_AUTHZ_ERROR`). `SecurityPolicyEnforcer` injects `Authorizer` optionally via
`@BindsOptionalOf`; the action gate is composed only when a `@RequiresAction` is present, which
requires the authorization engine to be installed (startup validation fails otherwise).

**Decision point chain (priority order at construction time):**
1. App-provided `AuthorizationDecisionPoint` override (`@BindsOptionalOf`)
2. App-provided `AuthorizationPolicy` (sync, core SPI), wrapped as `SyncPolicyDecisionPoint`
3. Default `VertxProviderDecisionPoint` (evaluates role/scope/permission requirements from `AuthorizationClaims`)

#### Invariants & Gotchas

- `SecurityPolicy.Constrained` with both empty roles and empty scopes throws `IllegalStateException`
  at handler-creation time (not at first request).
- The correlation context is captured **synchronously at handler entry** before any async hop so that
  a remote-PDP decision completing off the request context still carries the inbound correlation in
  the emitted event.

### RequestOriginConfig

Trusted-proxy configuration. Defaults to no trusted proxy (empty CIDR set). Override in the
application's Dagger module for production deployments:

```java
@Provides @Singleton
RequestOriginConfig originConfig() {
    return new RequestOriginConfig(
        Set.of("10.0.0.0/8", "172.16.0.0/12"),
        16,      // forwardedForCap
        true,    // trustForwardedScheme
        true     // trustForwardedHost
    );
}
```

### DefaultSecurityPolicyValidator

`SecurityPolicyValidator` implementation that validates annotation/security-model consistency at startup.
Checks violations per operation, each resulting in `RestConfigurationException`:

1. `ANNOTATION_WITHOUT_OPENAPI_SECURITY` — method has `@RolesAllowed`/`@Authorized`/`@DenyAll`
   but the operation's `securityRequirementSets()` is empty (no security requirement)
2. `OPENAPI_SECURITY_WITHOUT_HANDLER` — operation has a security requirement but no
   matching `SecuritySchemeHandler` was registered for one of the required scheme names
3. `CONFLICTING_SEMANTICS` — method has `@PermitAll` but the operation has a security requirement
4. Multi-scheme AND-set (a `SecurityRequirementSet` with more than one `SecurityRequirement`) — not yet implemented; fails closed with a message naming the operation and the workaround
5. Scoped OR alternatives — any OR alternative (`SecurityRequirementSet`) that has scopes when there are multiple sets — fails closed (scope enforcement across OR branches needs matched-scheme tracking, deferred to a future release)
6. Both-scopes overlap — an operation declaring required scopes via both `@Authorized(scopes)` and a `@SecurityRequirement` scope — ambiguous; fails closed
7. Duplicate `schemeName()` — two `SecuritySchemeHandler`s registered with the same name (detected by `SecuritySchemeHandlerCollector`); fails closed

Violations cause `RestConfigurationException` at startup. No warn-only mode. The validator receives a `RestOperationDescriptor` (not `OpenAPIRoute`/`OpenAPIContract`) so it operates without any dependency on `vertx-openapi`.

---

## Extension Points

### SecurityIdentityResolver (multibinding)

Contribute custom identity resolvers to the chain. `SecurityIdentityResolver extends OrderedExtension`; resolvers are sorted by `OrderedExtension.comparator()` (phase → priority → orderKey), where `orderKey()` delegates to `id()`. Lower `priority()` runs first; `id()` is the stable tie-break for equal priorities. Two resolvers with the same `(priority, id)` fail at startup.

```java
@Provides @IntoSet
static SecurityIdentityResolver tenantResolver(TenantDirectory directory) {
    return new SecurityIdentityResolver() {
        @Override
        public int priority() { return 50; }  // run before framework default (100)

        @Override
        public Future<Optional<SecurityIdentity>> resolve(SecurityIdentityResolutionContext ctx) {
            // Check evidence for a tenant-specific API key claim
            return ctx.evidence().stream()
                    .filter(e -> e.method().normalizedKind() == AuthMethodKind.API_KEY)
                    .findFirst()
                    .map(e -> directory.lookup(e.credentialId().orElse(""))
                            .map(Optional::of))
                    .orElse(Future.succeededFuture(Optional.empty()));
        }
    };
}
```

### SecurityEventObserver (multibinding)

Observe security lifecycle events without coupling to REST internals:

```java
@Provides @IntoSet
static SecurityEventObserver siemForwarder(SiemClient siem) {
    return new SecurityEventObserver() {
        @Override
        public Future<Void> onCredentialAccepted(CredentialAcceptedEvent event) {
            return siem.send("auth.accepted", event.identity(), event.authentication());
        }

        @Override
        public Future<Void> onCredentialRejected(CredentialRejectedEvent event) {
            return siem.send("auth.rejected", event.attemptedMethod(), event.reasonCode());
        }
    };
}
```

### SecurityClaimMapper (optional binding)

Override claim-to-authorization mapping (see above).

### AuthorizationPolicy (optional core SPI)

Sync authorization for applications that can evaluate from claims without I/O:

```java
@Provides
AuthorizationPolicy myPolicy() {
    return (identity, claims, request) -> {
        if (claims.valuesOf(AuthorityKind.ROLE).contains("admin")) {
            return AuthorizationDecision.permit("ADMIN_ROLE");
        }
        return AuthorizationDecision.deny("INSUFFICIENT_ROLE");
    };
}
```

### AuthorizationDecisionPoint (optional async override)

See example under [AuthorizationDecisionPoint](#authorizationdecisionpoint) above.

### CredentialRejectionReporter (replaceable binding)

Override rejection reporting (e.g., to add custom structured logging):

```java
// import dev.vertique.security.runtime.events.SecurityEventEmitter; — lives in vertique-security-runtime

@Provides
CredentialRejectionReporter myReporter(SecurityEventEmitter emitter) {
    return (ctx, method, credentialId, source, reasonCode, attrs) -> {
        myLogger.warn("auth.failed method={} reason={}", method.id(), reasonCode);
        // delegate to default emitter pattern
        ...
    };
}
```

### ChannelIdentityManager (replaceable binding)

Override channel identity management (e.g., for distributed channel tracking):

```java
@Binds @Singleton
abstract ChannelIdentityManager channelIdentityManager(RedisChannelIdentityManager impl);
```

### SecuritySchemeHandler (multibinding)

Contribute authentication handlers for named security schemes via the transport-neutral `SecuritySchemeRegistry`:

```java
@Provides @IntoSet
SecuritySchemeHandler jwtScheme(JWTAuth jwtAuth, CredentialRejectionReporter reporter) {
    return new SecuritySchemeHandler() {
        public String schemeName() { return "bearerAuth"; }
        public void configure(SecuritySchemeRegistry registry) {
            registry.authenticationHandler(JWTAuthHandler.create(jwtAuth));
        }
    };
}
```

The `SecuritySchemeRegistry` accepts an `AuthenticationHandler` (not a bare `Handler<RoutingContext>`) so the framework can compose multiple single-scheme scopeless OR alternatives into a Vert.x `ChainAuthHandler.any()`. The OR-of-AND-with-scopes security model governs which configurations are supported at startup (see `## Security Model` above).

---

## Dagger Modules

### AuthModule

```java
@Module(includes = SecurityEventsModule.class)  // owns Set<SecurityEventObserver> multibinding
public abstract class AuthModule {
    @Multibinds abstract Set<AuthorizationProvider> authorizationProviders();
    @Multibinds abstract Set<RouteAuthHandler> routeAuthHandlers();
    @Multibinds abstract Set<SecurityIdentityResolver> securityIdentityResolvers();
    // Set<SecurityEventObserver> declared in SecurityEventsModule (vertique-security-runtime)

    @BindsOptionalOf abstract SecurityClaimMapper optionalSecurityClaimMapper();
    @BindsOptionalOf abstract AuthorizationPolicy optionalAuthorizationPolicy();
    @BindsOptionalOf abstract AuthorizationDecisionPoint optionalAuthorizationDecisionPoint();
    @BindsOptionalOf abstract Authorizer optionalAuthorizer();

    // Default resolver + contributors (AuthorizationContributor, IdentityResolutionContributor,
    // ActionGateAuthenticationContributor) + validator + dispatch encoders + OriginCaptureMiddleware
    // + CredentialRejectionReporter + ChannelIdentityManager bindings all provided statically
}
```

| Binding | Purpose |
|---|---|
| `Set<SecurityIdentityResolver>` | Identity resolver chain (default: `DefaultSecurityIdentityResolver` at priority 100) |
| `Set<SecurityEventObserver>` | Declared by `SecurityEventsModule` (vertique-security-runtime); empty by default. `rest-security` contributes observers via `@IntoSet`. |
| `Set<AuthorizationProvider>` | Vert.x authorization sources (empty by default; not used for authorization decisions — retained for compatibility) |
| `Set<RouteAuthHandler>` | Route-level auth handlers for non-OpenAPI transports |
| `Optional<SecurityClaimMapper>` | Custom claim-to-claims mapping |
| `Optional<AuthorizationPolicy>` | Sync authorization policy |
| `Optional<AuthorizationDecisionPoint>` | Async authorization decision point override |
| `Optional<Authorizer>` | Core action authorizer; present when the authorization engine (`SecurityAuthzModule`) is installed; absent otherwise |
| `Set<OperationHandlerContributor>` | Registers `AuthorizationContributor` (priority 100), `IdentityResolutionContributor` (priority 80), and `ActionGateAuthenticationContributor` (priority 40) |
| `SecurityPolicyValidator` | Startup annotation/OpenAPI consistency validation |
| `ChannelIdentityManager` | Bound to `DefaultChannelIdentityManager` |
| `CredentialRejectionReporter` | Bound to `DefaultCredentialRejectionReporter` |
| `Set<Middleware>` | Registers `OriginCaptureMiddleware` (ROOT) |
| `AuthEnforcementCapability` (via `@BindsOptionalOf`) | Typed marker (`dev.vertique.rest.core.security`) signalling the auth enforcement runtime is installed; supplied only by `AuthModule` as `AuthEnforcementCapability.INSTANCE`. The route registrar reads the `Optional` to validate that restrictive security annotations have runtime support. |

### SecurityModule

| Binding | Purpose |
|---|---|
| `SecurityRuntime` | Bound to `HolderBackedSecurityRuntime` (reads/writes via `ContextValues`) |
| `JaxRsSecurityContextFactory` | Factory for `JaxRsSecurityContext` bridge |
| `@BindsOptionalOf IdentitySnapshotCapture` | Declares the optional ingress capture seam `IdentityResolutionMiddleware` injects; empty unless identity-snapshot durable carriage is installed (ADR-0164) |

Including `SecurityModule` in the app's `@Component` is required for security features.

---

## Application Setup

```java
@Singleton
@Component(modules = {VertxModule.class, RestModule.class, AuthModule.class,
                      SecurityModule.class,
                      AppModule.class, ResourceModule.class})
interface AppComponent {
    HttpVerticle httpVerticle();
}
```

**Example secured resource:**

```java
@Path("/orders")
public class OrderResource {

    @GET @Path("/{id}")
    @RolesAllowed("user")
    public Future<Order> getOrder(
            @PathParam("id") String id,
            dev.vertique.security.SecurityContext sc) {
        // sc.identity().actor().id() — authenticated user id
        // sc.authorization().valuesOf(AuthorityKind.SCOPE) — scopes
        return orderService.findById(id);
    }

    @POST
    @Authorized(scopes = "orders:write")
    public Future<Response> createOrder(CreateOrderRequest req) { ... }
}
```

**Injecting SecurityContext in lifecycle callbacks:**

```java
// Rich framework context
public Future<Response> handle(dev.vertique.security.SecurityContext sc) { ... }

// Standard JAX-RS context
public Future<Response> handle(jakarta.ws.rs.core.SecurityContext sc) { ... }
```

**Authorization annotation semantics:**

| Annotation | Effect |
|---|---|
| `@DenyAll` | Returns 403 always (highest priority) |
| `@PermitAll` | No authorization handler |
| `@RolesAllowed("a", "b")` | OR: any listed role is sufficient |
| `@Authorized(scopes = "write")` | Single scope required |
| `@Authorized(scopes = {"a", "b"}, matchAll = true)` | AND: all scopes required |
| `@Authorized(scopes = {"a", "b"}, matchAll = false)` | OR: any scope sufficient |
| `@RolesAllowed` + `@Authorized` | AND: must pass both role AND scope checks |

---

## Dependencies

- `dev.vertique:rest-core`
- `dev.vertique:core`
- `io.vertx:vertx-core`
- `io.vertx:vertx-web`
- `io.vertx:vertx-auth-common`
- `com.google.dagger:dagger`
- `jakarta.ws.rs:jakarta.ws.rs-api`
- `org.slf4j:slf4j-api`
- `org.projectlombok:lombok` (provided scope)

---

## Related ADRs

- ADR-0062: Canonical Security Facts and Observer Boundary — establishes that security modules
  produce canonical facts/events for independent observers; observer failure must not affect the
  security result.
- ADR-0063: Stateless External-IdP Authentication and Evidence Model — establishes that Vertique verifies inbound credentials and records evidence but does not own user management, login flows, or session establishment.
- ADR-0064: Typed Security Identity Model — establishes the four-pillar `SecurityContext`, `SecurityIdentity`/`AuthenticationState`/`AuthorizationClaims`/`RequestOrigin` structure, the `SecurityEventObserver` SPI, the SYSTEM identity boundary rule, the authorization source-of-truth decision (`AuthorizationClaims` over Vert.x `AuthorizationProvider`), trusted-proxy model, channel lifecycle close ordering, and resolver determinism.
- ADR-0084: Framework Extension-Ordering Contract — establishes `OrderedExtension` and `ExtensionPhase` as the canonical ordering contract for framework extensions.
- ADR-0085: OrderedExtension Rolled Out Across Sorted Behavioral SPIs — `SecurityIdentityResolver` now follows the framework OrderedExtension ordering contract; `orderKey()` delegates to `id()` so the documented `(priority, id)` tie-break is preserved.
- ADR-0113: Federated Action and Policy Authorship for Framework Authorization — establishes federated authorship of actions and policies; governs the AND-composition of the role/scope gate with the action gate in `SecurityPolicyEnforcer.createHandler(SecurityPolicy, Optional<ActionRef>)`.
- ADR-0114: Enforcement-Layer Emission Ownership for Authorization Decisions — the enforcement layer (`SecurityPolicyEnforcer`) — not the decision point — owns emission of exactly one `AuthorizationDecisionEvent` per authorization attempt; supersedes the former "MUST emit" contract on `AuthorizationDecisionPoint`.
- ADR-0124: Security Model as OR-of-AND-with-Scopes, Fail-Closed on the Unsupported Subset — establishes `SecurityRequirementSet` / `securityRequirementSets()` as the final SPI shape, the fail-closed matrix for unsupported configurations, and scope enforcement unified with the `@Authorized` path via `EffectiveSecurityPolicy.fold`.
- ADR-0164: Identity-Snapshot Capture Site — SecurityContext Assembly (Ingress), Opt-In — governs the opt-in `Optional<IdentitySnapshotCapture>` capture hook `IdentityResolutionMiddleware` invokes at REST ingress and the `@BindsOptionalOf` declaration in `SecurityModule`.
