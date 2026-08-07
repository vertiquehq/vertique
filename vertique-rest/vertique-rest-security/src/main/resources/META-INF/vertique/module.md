<!--
SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
SPDX-License-Identifier: EUPL-1.2
-->

# REST Security Module

> **Status:** Implemented
> **Package:** `dev.vertique.rest.security`
> **Artifact:** `vertique-rest-security`
> **Depends on:** rest-core, security-core, security-runtime, context, logging

`vertique-rest-security` is the authentication, identity-resolution, and authorization layer for
Vertique's HTTP transport. It turns whatever an authentication handler proved about a request into
the framework's typed `dev.vertique.security.SecurityContext`, enforces the operation's declared
security policy before the resource method runs, and emits a canonical security event for every
credential acceptance, credential rejection, and authorization decision.

It does **not** verify credentials itself. Credential verification is contributed by an
authentication module — `dev.vertique:vertique-rest-auth-jwt` for bearer tokens, or an application's
own `SecuritySchemeHandler` / `RouteAuthHandler`. This module consumes the evidence those handlers
produce. It also does not manage users, sessions, or login flows.

---

## When To Use It

Install `vertique-rest-security` whenever an HTTP operation carries `@RolesAllowed`, `@DenyAll`,
`@PermitAll`, `@Authorized`, `@RequiresAction`, or a Swagger `@SecurityRequirement`. Without it the
route registrar has no `AuthEnforcementCapability` binding and rejects those annotations at startup,
so a secured API cannot start unauthenticated by accident.

Pair it with:

| Artifact | Why |
|---|---|
| `dev.vertique:vertique-rest-auth-jwt` | Ready-made bearer-token `SecuritySchemeHandler` and `RouteAuthHandler` |
| `dev.vertique:vertique-security-core` | The typed `SecurityContext` model, `AuthorizationClaims`, `@RequiresAction`, `AuthorizationPolicy` |
| `dev.vertique:vertique-security-runtime` | `SecurityEventEmitter` and the `SecurityEventObserver` multibinding |
| `dev.vertique:vertique-security-authz` | The action `Authorizer` engine that `@RequiresAction` requires |

---

## Core Concepts

### What runs, in what order

Every request passes three separately-owned stages. Only the last two belong to this module.

1. **Authentication** — an `AuthenticationHandler` installed from the operation's security scheme
   sets `ctx.user()` and appends `AuthenticationEvidence`. On failure it calls
   `CredentialRejectionReporter.report(...)` and then `ctx.fail(...)`, which short-circuits
   everything below.
2. **Identity resolution** (priority 80) — runs the `SecurityIdentityResolver` chain, maps the
   Vert.x `User` principal to `AuthorizationClaims`, and — only when the opt-in
   `VertxAuthorizationImportModule` is included and the request is authenticated — imports the
   grants of every contributed Vert.x `AuthorizationProvider` into those claims (see
   [Vert.x authorization import](#vertx-authorization-import-opt-in)); then assembles the
   `SecurityContext`, binds it for the rest of the request, and emits `CredentialAcceptedEvent`.
3. **Authorization** (priority 100) — evaluates the effective `SecurityPolicy` and any
   `@RequiresAction` gate, and emits exactly one `AuthorizationDecisionEvent`.

The framework occupies these `OperationHandlerContributor` priorities. Choose a priority for your
own contributor relative to them:

| Priority | Contributor | Artifact |
|---|---|---|
| 40 | `ActionGateAuthenticationContributor` | `vertique-rest-security` |
| 50 | JWT claims validator | `vertique-rest-auth-jwt` |
| 80 | `IdentityResolutionContributor` | `vertique-rest-security` |
| 100 | `AuthorizationContributor` | `vertique-rest-security` |
| 350 | operation-id capture | `vertique-rest-core` |

`OriginCaptureMiddleware` is the module's one ROOT-scoped `Middleware`. It runs before any
authentication handler and stashes the resolved `RequestOrigin` so identity resolution and every
emitted event carry the caller's address even when authentication fails.

### The resolved `SecurityContext`

`dev.vertique.security.SecurityContext` has four pillars, assembled at identity resolution:

| Pillar | Source |
|---|---|
| `identity()` | the first `SecurityIdentityResolver` returning a non-empty result; `SecurityIdentity.anonymous()` when the chain is exhausted |
| `authentication()` | the accumulated `AuthenticationEvidence` list; `primaryMethod()` is the first entry's method, or `DefaultAuthMethod.none()` when there is no evidence |
| `authorization()` | `SecurityClaimMapper` applied to `ctx.user().principal()`, plus any grants imported through the opt-in [Vert.x authorization import](#vertx-authorization-import-opt-in); `AuthorizationClaims.empty()` when no Vert.x `User` is present |
| `origin()` | the `RequestOrigin` captured pre-authentication |

Inject it into any resource method (see [JAX-RS integration](#jax-rs-integration)), or read it
anywhere in the request through `SecurityRuntime.current()`. The binding unwinds with the request
lifecycle. The same context is captured onto outbound `vertique-services` dispatches automatically,
so a downstream service handler observes the caller's identity without threading it through the
contract.

### Authorization model: OR of AND, with scopes

An operation's security requirements follow OpenAPI semantics through
`RestOperationDescriptor.securityRequirementSets()`:

- the list of `SecurityRequirementSet` is an **OR** — satisfying any one set authenticates the
  request;
- every `SecurityRequirement` inside a set must hold — an **AND**;
- every scope listed on a scheme must hold — an **AND**;
- an empty list means the operation is public.

```java
// dev.vertique.rest.core.routing — from vertique-rest-core
public record SecurityRequirement(String schemeName, List<String> scopes) {}

public record SecurityRequirementSet(List<SecurityRequirement> schemes) {
    public boolean isSingleScheme();  // exactly one scheme in this set
    public boolean hasScopes();       // at least one scheme carries required scopes
}
```

Sets are produced from annotations: a standalone `@SecurityRequirement(name)` becomes a
single-scheme set; `@SecurityRequirement(combine = {…})` becomes a multi-scheme AND-set; repeated
`@SecurityRequirement`, `@SecurityRequirements`, or `@Operation(security = …)` become multiple sets
(the OR alternatives).

### Fail-closed startup matrix

Any security shape the framework cannot enforce exactly as declared fails startup with
`RestConfigurationException`. Nothing is silently downgraded or ignored.

| Declared shape | Outcome |
|---|---|
| Empty `securityRequirementSets()` | Public — no authentication handler installed |
| One single-scheme set, no scopes | Authentication handler installed; no scope check |
| One single-scheme set, with scopes | Authentication handler plus scope enforcement (folded, below) |
| Several single-scheme scopeless sets | `ChainAuthHandler.any()` — any one scheme is sufficient |
| Any multi-scheme AND-set (`combine()`) | `RestConfigurationException` at startup |
| Several sets where any set carries scopes | `RestConfigurationException` at startup |
| Scopes declared via both `@Authorized(scopes)` and `@SecurityRequirement` | `RestConfigurationException` at startup — ambiguous |
| Two `SecuritySchemeHandler`s with the same `schemeName()` | `RestConfigurationException` at startup |

The gate is `EffectiveSecurityPolicy.enforceSupportedShape` in `vertique-rest-core`. Reading an
operation's effective policy runs it, so the check is always on — it does not depend on this
module's `SecurityPolicyValidator` being wired.

### Scope enforcement is unified with `@Authorized`

For the one scoped shape the framework enforces — a single single-scheme set with scopes —
`EffectiveSecurityPolicy.fold` merges the scheme's scopes into the operation's policy at route
registration:

| Base policy | Effective policy after fold |
|---|---|
| `None` or `AuthenticatedOnly` | `Constrained([], scopes, requireAllScopes = true)` |
| `Constrained(roles, [], …)` | `Constrained(roles, scopes, requireAllScopes = true)` |
| `PermitAll` or `DenyAll` | unchanged |

Roles from `@RolesAllowed` / `@Authorized(roles)` survive the fold. Enforcement then flows through
the same `AuthorizationDecisionPoint` → `SecurityPolicyEnforcer` → 403 path as `@Authorized(scopes)`
— one mechanism, one status, one event. `fold` never replaces or loosens scopes a base policy
already declares.

### Security events

Every decision this module makes produces exactly one canonical event, fanned out to every
`SecurityEventObserver` through the `SecurityEventEmitter` from
`dev.vertique:vertique-security-runtime`. Observer failure is isolated and never changes the
security outcome.

| Event | Emitted by | When |
|---|---|---|
| `CredentialRejectedEvent` | the failing authentication handler, via `CredentialRejectionReporter` | credential verification failed |
| `CredentialAcceptedEvent` | identity resolution | evidence is non-empty **and** a `CorrelationContext` is bound |
| `AuthorizationDecisionEvent` | `SecurityPolicyEnforcer` | once per authorization attempt, permit or deny |
| `ChannelOpenedEvent`, `ChannelIdentityRefreshedEvent`, `ChannelClosedEvent` | `ChannelIdentityManager` | long-lived channel lifecycle |

An `AuthorizationDecisionPoint` is a **pure evaluator**: it returns a decision and must not emit. The
enforcement layer owns emission, so a decision point that also emits produces duplicate events.

---

## Getting Started

```java
@Singleton
@Component(modules = {VertxModule.class, RestModule.class,
                      AuthModule.class, SecurityModule.class,
                      AppModule.class, ResourceModule.class})
interface AppComponent {
    HttpVerticle httpVerticle();
}
```

`SecurityModule` supplies the `SecurityRuntime` binding and is required for any security feature.
`AuthModule` adds identity resolution, authorization, the startup validator, and the default
claim mapper, rejection reporter, and channel identity manager.

---

## Key Classes

### `RestAuthenticationEvidence`

Static bridge an authentication handler uses to record what it proved. Evidence accumulates as a
list on the routing context, so layered authentication (mTLS plus JWT) contributes several entries.

```java
RestAuthenticationEvidence.append(ctx, new AuthenticationEvidence(
        DefaultAuthMethod.jwt(),
        Optional.of(jti),        // credentialId — stable and non-sensitive
        Instant.now(),           // verifiedAt
        Optional.of(expiresAt),  // notAfter
        new JwksVerificationSource(
                Optional.of("https://idp.example.com/"),
                Optional.of("https://idp.example.com/.well-known/jwks.json"),
                Optional.of(kid),
                Optional.of("RS256")),
        Map.of()));              // safeAttributes
```

`RestAuthenticationEvidence.get(ctx)` returns the accumulated list; identity resolution reads it.
The first entry's method becomes `authentication().primaryMethod()`, so append the strongest or
primary method first.

### `CredentialRejectionReporter`

SPI an authentication handler calls when verification fails, before `ctx.fail(...)`.

```java
void report(RoutingContext ctx,
            AuthMethod attemptedMethod,
            Optional<String> credentialId,
            Optional<VerificationSource> verificationSource,
            String reasonCode,
            Map<String, Object> safeAttributes);
```

```java
rejectionReporter.report(
        ctx,
        DefaultAuthMethod.jwt(),
        Optional.of(jti),
        Optional.of(new JwksVerificationSource(
                Optional.of(issuer), Optional.of(jwksUri), Optional.empty(), Optional.empty())),
        "TOKEN_EXPIRED",
        Map.of("header_alg", headerAlg));
ctx.fail(401);
```

`safeAttributes` **must not** contain raw token material, raw API keys, raw passwords, raw HMAC
signatures, or raw request bodies. It throws `IllegalStateException` when no `CorrelationContext` is
bound; in normal request flow the correlation ingress middleware binds one before any authentication
handler runs.

### `SecurityClaimMapper`

Functional SPI mapping raw provider claims to typed `AuthorizationClaims`:

```java
AuthorizationClaims map(Map<String, Object> claims);
```

The default implementation reads three conventions. Each claim may be a JSON array of strings or a
single space-delimited string; non-string elements and blank values are skipped, and a missing claim
contributes nothing.

| Claim | Authority kind |
|---|---|
| `roles` | `AuthorityKind.ROLE` |
| `scope` and `scp` (merged) | `AuthorityKind.SCOPE` |
| `permissions` | `AuthorityKind.PERMISSION` |

Override it for a provider with a different shape:

```java
@Provides
SecurityClaimMapper keycloakClaimMapper() {
    return claims -> {
        // Keycloak nests roles under realm_access.roles.
        Set<AuthorityClaim> authorities = extractNestedRoles(claims, "realm_access", "roles").stream()
                .map(role -> new AuthorityClaim(AuthorityKind.ROLE, role, "", "", "", Map.of()))
                .collect(Collectors.toSet());
        return new AuthorizationClaims(authorities, Map.of());
    };
}
```

`AuthorityClaim` is `(kind, value, issuer, audience, source, attributes)`; the built-in mapper
leaves `issuer`, `audience`, and `source` empty. Query the result with
`claims.valuesOf(AuthorityKind.ROLE)`.

### `AuthorizationDecisionPoint`

Async authorization SPI. Implementations receive a fully-populated `AuthorizationRequest` and return
`Future<AuthorizationDecision>`.

```java
@FunctionalInterface
public interface AuthorizationDecisionPoint {
    Future<AuthorizationDecision> decide(AuthorizationRequest request);
}
```

```java
@Provides
AuthorizationDecisionPoint remoteDecisionPoint(MyPdpClient client) {
    // Return the decision only — SecurityPolicyEnforcer emits the one event.
    return request -> client
            .evaluate(request.securityContext(), request.action(), request.resource())
            .map(allowed -> allowed
                    ? AuthorizationDecision.permit("REMOTE_PDP_ALLOWED")
                    : AuthorizationDecision.deny("REMOTE_PDP_DENIED"));
}
```

Contributed Vert.x `AuthorizationProvider` bindings are consulted at identity-resolution time —
and only when the opt-in `VertxAuthorizationImportModule` is included (see
[Vert.x authorization import](#vertx-authorization-import-opt-in)); their grants are merged into the
resolved `AuthorizationClaims` before authorization runs. Decisions themselves are always evaluated
from `AuthorizationClaims` — no decision point talks to a provider.

### `SecurityPolicyEnforcer`

Builds the `Handler<RoutingContext>` that enforces a `SecurityPolicy`, optionally AND-composed with
a `@RequiresAction` gate. `AuthorizationContributor` uses it for JAX-RS routes; reuse it directly
when registering non-JAX-RS routes (for example a WebSocket upgrade) that need the same enforcement.

| Method | Purpose |
|---|---|
| `createHandler(SecurityPolicy)` | Role/scope enforcement only |
| `createHandler(SecurityPolicy, Optional<ActionRef>)` | AND-composes the role/scope gate with the action gate into one handler emitting one event |
| `createHandler(SecurityPolicy.Constrained, String)` | Constrained enforcement with a context label used in error messages |

It returns `null` — install no handler — for `None` and `PermitAll` with no action. With an action
present, even an action-only `None` route gets a handler.

The decision point is selected once, at construction, in this order:

1. an application-provided `AuthorizationDecisionPoint`;
2. an application-provided sync `AuthorizationPolicy`, wrapped as `SyncPolicyDecisionPoint`;
3. the built-in decision point, which evaluates roles, scopes, and permissions from
   `AuthorizationClaims`.

### `JaxRsSecurityContext`

Bridges the framework context to `jakarta.ws.rs.core.SecurityContext` so standard JAX-RS code works
unchanged.

| JAX-RS method | Behavior |
|---|---|
| `getUserPrincipal()` | the actor id as a `Principal`; **`null`** when the actor is anonymous or no framework context is bound |
| `isUserInRole(role)` | matches a `ROLE` authority claim by value; **always `false`** for an anonymous actor, regardless of claims |
| `isSecure()` | `origin().scheme()` equals `"https"`; falls back to the raw request's TLS state when no origin was captured |
| `getAuthenticationScheme()` | see the mapping below |

`getAuthenticationScheme()` maps `authentication().primaryMethod().normalizedKind()`:

| Kind | Returned string |
|---|---|
| `JWT` | `"BEARER"` |
| `BASIC` | `"BASIC"` (`SecurityContext.BASIC_AUTH`) |
| `API_KEY` | `"API_KEY"` |
| `MTLS` | `"CLIENT_CERT"` (`SecurityContext.CLIENT_CERT_AUTH`) |
| `HMAC` | `"HMAC"` |
| `CUSTOM`, `UNKNOWN` | the auth method's `id()`, upper-cased |
| `NONE` | `null` |

`getAuthenticationScheme()` also returns `null` when no framework context is bound.

### `ChannelIdentityManager`

Registry of identities bound to long-lived channels (WebSocket, SSE), keyed by channel id. The
default implementation is in-memory and, for every channel whose
`AuthenticationState.earliestNotAfter()` is present, schedules a timer that closes the channel with
reason code `IDENTITY_EXPIRED` at that instant.

| Method | Effect |
|---|---|
| `register(channelId, ctx, binding)` | records the identity, emits `ChannelOpenedEvent`, arms the expiry timer |
| `refreshIdentity(channelId, newCtx)` | rebinds on the channel's event loop, emits `ChannelIdentityRefreshedEvent`, re-arms the timer |
| `closeChannel(channelId, reasonCode)` | server-initiated close with an explicit reason |
| `deregister(channelId, fallbackReasonCode)` | peer-initiated close: cancels the timer, emits `ChannelClosedEvent`, releases the binding **last** |
| `current(channelId)` | the currently bound context, if any |

The binding is released after the close event, so an `@OnClose` callback still observes the bound
`SecurityContext`.

---

## Extension Points

### `SecurityIdentityResolver` (multibinding)

Contribute an identity resolver. `SecurityIdentityResolver extends OrderedExtension`, so the chain
is sorted by phase, then ascending `priority()`, then `orderKey()` — which delegates to `id()`. The
first resolver returning a non-empty `Optional` wins; an exhausted chain yields
`SecurityIdentity.anonymous()`. The framework default runs at priority 100, so an application
resolver should use a priority below 100 to run first.

```java
@Provides @IntoSet
static SecurityIdentityResolver tenantResolver(TenantDirectory directory) {
    return new SecurityIdentityResolver() {
        @Override
        public int priority() {
            return 50;
        }

        @Override
        public Future<Optional<SecurityIdentity>> resolve(SecurityIdentityResolutionContext ctx) {
            return ctx.evidence().stream()
                    .filter(e -> e.method().normalizedKind() == AuthMethodKind.API_KEY)
                    .findFirst()
                    .map(e -> directory.lookup(e.credentialId().orElse("")).map(Optional::of))
                    .orElse(Future.succeededFuture(Optional.empty()));
        }
    };
}
```

### `SecuritySchemeHandler` (multibinding)

Contribute the authentication handler for a named OpenAPI security scheme.

```java
@Provides @IntoSet
SecuritySchemeHandler jwtScheme(JWTAuth jwtAuth) {
    return new SecuritySchemeHandler() {
        @Override
        public String schemeName() {
            return "bearerAuth";
        }

        @Override
        public void configure(SecuritySchemeRegistry registry) {
            registry.authenticationHandler(JWTAuthHandler.create(jwtAuth));
        }
    };
}
```

The registry takes an `AuthenticationHandler`, not a bare `Handler<RoutingContext>`, so several
single-scheme scopeless alternatives can be composed into a Vert.x `ChainAuthHandler.any()`. Two
handlers reporting the same `schemeName()` fail startup.

### `RouteAuthHandler` (multibinding)

Route-level authentication for transports with no OpenAPI security scheme — WebSocket endpoints and
action-only `@RequiresAction` routes.

```java
@Provides @IntoSet
RouteAuthHandler bearerRouteAuth(JWTAuth jwtAuth) {
    return new RouteAuthHandler() {
        @Override
        public String schemeName() {
            return "bearerAuth";
        }

        @Override
        public Handler<RoutingContext> createHandler() {
            return JWTAuthHandler.create(jwtAuth);
        }
    };
}
```

### `SecurityEventObserver` (multibinding)

Observe security lifecycle events without coupling to REST internals. Declared by
`SecurityEventsModule` in `dev.vertique:vertique-security-runtime`, which `AuthModule` includes.

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

### `AuthorizationPolicy` (optional binding)

Synchronous authorization for applications that can decide from claims without I/O. Declared in
`dev.vertique:vertique-security-core`; `AuthorizationDecision decide(AuthorizationRequest request)`
takes the request alone — identity and claims are reached through
`request.securityContext()`.

```java
@Provides
AuthorizationPolicy myPolicy() {
    return request -> request.securityContext()
                    .authorization()
                    .valuesOf(AuthorityKind.ROLE)
                    .contains("admin")
            ? AuthorizationDecision.permit("ADMIN_ROLE")
            : AuthorizationDecision.deny("INSUFFICIENT_ROLE");
}
```

### Vert.x authorization import (opt-in)

Feed the grants of contributed Vert.x `AuthorizationProvider`s into the request's
`AuthorizationClaims`. The `AuthorizationProvider` multibinding declared by `AuthModule` is inert on
its own; including `VertxAuthorizationImportModule` in the component installs the importer that
identity resolution runs on every authenticated request, between claim mapping and `SecurityContext`
binding. Anonymous requests, and applications without the module, skip the step entirely.

```java
@Singleton
@Component(modules = {VertxModule.class, RestModule.class,
                      AuthModule.class, SecurityModule.class,
                      VertxAuthorizationImportModule.class,
                      AppModule.class, ResourceModule.class})
interface AppComponent { ... }
```

```java
@Provides @IntoSet
static AuthorizationProvider entitlementProvider(EntitlementDirectory directory) {
    // any io.vertx.ext.auth.authorization.AuthorizationProvider with a stable, unique id
    return directory.asAuthorizationProvider();
}
```

Execution contract:

- **Sequential and deterministic.** Providers run one at a time, in ascending `getId()` order, so
  the observable claim set never depends on registration order. Every provider id must be non-blank
  and unique across the set; a violation fails Dagger graph construction, not the first request.
- **Request-local user.** Providers never see the caller's Vert.x `User`. They share one
  request-local `User` built per import from deep copies of the caller's principal and attributes.
- **All-or-nothing.** Claims are merged only after every provider has succeeded. Any provider
  failure — a failed future, a synchronous throw, or a `null` future — fails the request with 503;
  no partially imported claim is ever observable.
- **No importer-level timeout.** Providers must not block the event loop and own their own
  timeouts; a provider whose future never completes stalls that request's authorization.

Mapping is fail-closed. Only these grants become claims:

| Vert.x authorization | Imported as |
|---|---|
| `RoleBasedAuthorization` with no resource | `AuthorityKind.ROLE` |
| `PermissionBasedAuthorization` with no resource | `AuthorityKind.PERMISSION` |

Imported claims carry `source = "vertx-provider:<providerId>"` and empty issuer, audience, and
attributes. Everything else — wildcard permissions, `And`/`Or`/`Not` composites, resource-scoped
grants, and blank values — is dropped and logged once per `(provider, authorization type)` pair at
WARN; the authorization value is never logged. The Vert.x `jwt-claims` provider bucket is always
excluded: its scope→permission projection is lossy, and the JWT principal already reaches
`AuthorizationClaims` with full kind fidelity through `SecurityClaimMapper`.

### Replaceable bindings

| Binding | Default | Override with |
|---|---|---|
| `SecurityClaimMapper` | three-convention JWT mapper | `@Provides SecurityClaimMapper` |
| `AuthorizationDecisionPoint` | claims-based evaluation | `@Provides AuthorizationDecisionPoint` |
| `CredentialRejectionReporter` | assembles and emits `CredentialRejectedEvent` | `@Provides CredentialRejectionReporter` |
| `ChannelIdentityManager` | in-memory registry with expiry timers | `@Binds ChannelIdentityManager` |
| `RequestOriginConfig` | trust no proxy | `@Provides @Singleton RequestOriginConfig` |

---

## JAX-RS Integration

```java
@Path("/orders")
public class OrderResource {

    @GET
    @Path("/{id}")
    @RolesAllowed("user")
    public Future<Order> getOrder(
            @PathParam("id") String id, dev.vertique.security.SecurityContext sc) {
        // sc.identity().actor().id()                      — authenticated user id
        // sc.authorization().valuesOf(AuthorityKind.SCOPE) — granted scopes
        return orderService.findById(id);
    }

    @POST
    @Authorized(scopes = "orders:write")
    public Future<Response> createOrder(CreateOrderRequest req) { ... }
}
```

Either context type may be injected as a resource-method parameter:

```java
public Future<Response> handle(dev.vertique.security.SecurityContext sc) { ... }
public Future<Response> handle(jakarta.ws.rs.core.SecurityContext sc) { ... }
```

### Annotation semantics

| Annotation | Effect |
|---|---|
| `@DenyAll` | Always 403; wins over every other annotation |
| `@PermitAll` | No authorization handler installed |
| `@RolesAllowed("a", "b")` | OR — any one listed role is sufficient |
| `@Authorized(scopes = "write")` | The listed scope is required |
| `@Authorized(scopes = {"a", "b"})` | AND — **all** listed scopes required (`matchAll` defaults to `true`) |
| `@Authorized(scopes = {"a", "b"}, matchAll = false)` | OR — any one listed scope is sufficient |
| `@Authorized(scopes = {})` | Authentication only — any authenticated caller |
| `@RolesAllowed` with `@Authorized` | AND — the role check and the scope check must both pass |
| `@RequiresAction("…")` | AND — the action gate must pass in addition to the role/scope gate |

`@Authorized` on a class applies to every method; a method-level `@Authorized` overrides it.

---

## Configuration

`RequestOriginConfig` is a Dagger binding, not a config-file section. The default trusts no proxy,
so `clientIp` always equals `remoteIp` and forwarded scheme and host headers are ignored.

```java
@Provides @Singleton
RequestOriginConfig originConfig() {
    return new RequestOriginConfig(
            Set.of("10.0.0.0/8", "172.16.0.0/12"),  // trustedProxyCidrs
            16,                                      // forwardedForCap
            true,                                    // trustForwardedScheme
            true);                                   // trustForwardedHost
}
```

| Component | Default | Meaning |
|---|---|---|
| `trustedProxyCidrs` | empty | CIDR ranges whose members may set forwarded headers; empty trusts nothing |
| `forwardedForCap` | `16` | Maximum `X-Forwarded-For` entries; a longer chain is dropped entirely and flagged rejected. Must be `>= 0`; `0` disables the chain |
| `trustForwardedScheme` | `false` | Honour `X-Forwarded-Proto` — only when the direct peer is trusted |
| `trustForwardedHost` | `false` | Honour `X-Forwarded-Host` — only when the direct peer is trusted |

Resolution rules:

- `X-Forwarded-For` is always parsed and exposed for observability, trusted peer or not.
- `clientIp` uses the forwarded chain **only** when the direct peer matches a trusted CIDR;
  otherwise it is the direct peer address.
- Forwarded scheme and host are honoured only when both the corresponding flag is set and the direct
  peer is trusted.

A non-empty `trustedProxyCidrs` is what makes forwarded headers trustworthy. Setting
`trustForwardedScheme` or `trustForwardedHost` without it changes nothing.

---

## Failures, Constraints, and Common Mistakes

### Startup failures

| Failure | Cause |
|---|---|
| `RestConfigurationException` | Any shape in the [fail-closed matrix](#fail-closed-startup-matrix): a multi-scheme AND-set, scopes on an OR alternative, scopes from both `@Authorized` and `@SecurityRequirement`, or duplicate `schemeName()` |
| `RestConfigurationException` | `@RolesAllowed` / `@Authorized` / `@DenyAll` on an operation with no declared security requirement — the handler would never run |
| `RestConfigurationException` | A declared security requirement with no matching `SecuritySchemeHandler` registered |
| `RestConfigurationException` | `@PermitAll` on an operation that also declares a security requirement — conflicting intent |
| `IllegalStateException` | Two `SecurityIdentityResolver`s share the same `(priority, id)` pair. Raised while the Dagger graph is constructed, not on first request |
| `IllegalStateException` | An action-only `@RequiresAction` route with no `RouteAuthHandler` registered, or with more than one — JAX-RS has no per-route scheme selector, so the framework fails rather than guess |
| `IllegalStateException` | A `SecurityPolicy.Constrained` with both empty roles and empty scopes. Raised at handler creation, not on first request |

There is no warn-only mode. Every validation failure stops startup.

### Request-time outcomes

| Status | Reason code | Situation |
|---|---|---|
| 401 | — | Credential verification failed; the authentication handler reported and failed the request |
| 401 | `AUTHENTICATION_REQUIRED` | No `SecurityContext` bound, or an anonymous actor on an `AuthenticatedOnly` or `Constrained` route |
| 403 | `DENY_ALL` | `@DenyAll` |
| 403 | the decision's own code | The decision point denied |
| 403 | `INTERNAL_AUTHZ_ERROR` | The decision point or `Authorizer` threw, returned a `null` future, or resolved to a `null` decision — fail-closed |
| 503 | — | A provider failed during the opt-in [Vert.x authorization import](#vertx-authorization-import-opt-in) — fail-closed: the `SecurityContext` is never bound and no partially imported claim is observable |
| — | `PERMITTED` | Both gates passed |

A failed (rather than denied) decision future propagates its cause through the error pipeline after
the deny event is emitted. Every path that reaches authorization emits exactly one
`AuthorizationDecisionEvent`; the credential-failure 401 and the import-failure 503 short-circuit
the request before authorization runs, so no decision event is emitted for them.

With a `@RequiresAction` gate, the role/scope gate is evaluated first and the action gate only if it
permits. The single emitted decision carries the **first failing predicate** as its top-level reason
code, plus `rolesSatisfied`, `actionSatisfied`, and `actionEvaluated` in `safeAttributes`.

### Common mistakes

- **Emitting from a custom `AuthorizationDecisionPoint`.** The enforcement layer already emits one
  event per attempt; a decision point that emits produces duplicates. Return the decision only.
- **Expecting `CredentialAcceptedEvent` for anonymous traffic.** It is emitted only when evidence is
  non-empty. An anonymous request still gets a bound `SecurityContext`, just no event. The event is
  also skipped, with a warning, when no `CorrelationContext` is bound.
- **Expecting identity resolution to report rejections.** `CredentialRejectedEvent` comes from the
  failing authentication handler before `ctx.fail(...)`; identity resolution never runs on that path.
- **Contributing a Vert.x `AuthorizationProvider` without including `VertxAuthorizationImportModule`.**
  The multibinding set is inert on its own — no provider is ever consulted until the opt-in import
  module is wired into the component.
- **Expecting resource-scoped, wildcard, or composite Vert.x authorizations to import.** The import
  maps only resource-free role and permission grants; everything else is dropped fail-closed with a
  throttled WARN.
- **Trusting forwarded headers without `trustedProxyCidrs`.** `trustForwardedScheme` and
  `trustForwardedHost` do nothing while the trusted-proxy set is empty.
- **Reading roles straight off `AuthorizationClaims` for a JAX-RS check.** `isUserInRole` deliberately
  returns `false` for an anonymous actor even if claims are present; hand-rolled checks lose that
  guard.
- **Including `AuthModule` without `SecurityModule`.** `SecurityModule` owns the `SecurityRuntime`
  binding that every enforcement path reads.

---

## Dependencies

| Artifact | Why |
|---|---|
| `dev.vertique:vertique-rest-core` | `Middleware`, `OperationHandlerContributor`, `SecurityPolicy`, `EffectiveSecurityPolicy`, `SecuritySchemeHandler`, `RouteAuthHandler`, `SecurityRuntime`, `RequestContextLifecycle` |
| `dev.vertique:vertique-security-core` | The typed identity model, `AuthorizationClaims`, `AuthorizationPolicy`, `Authorizer`, `ChannelIdentityManager`, `SecurityIdentityResolver` |
| `dev.vertique:vertique-security-runtime` | `SecurityEventEmitter`, `SecurityEventsModule`, `IdentitySnapshotCapture` |
| `dev.vertique:vertique-context` | `ContextHolder` scopes and the service-dispatch context encoder/decoder seam |
| `dev.vertique:vertique-logging` | MDC key binding for `userId`, `clientId`, and `authMethod` |
| `io.vertx:vertx-auth-common` | `AuthenticationHandler`, `User`, `AuthorizationProvider` |
| `jakarta.ws.rs:jakarta.ws.rs-api` | The `jakarta.ws.rs.core.SecurityContext` bridge and the Jakarta security annotations |
| `com.google.dagger:dagger` | Module wiring and multibindings |
| `org.projectlombok:lombok` | Compile-time only |
