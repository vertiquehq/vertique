<!--
SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
SPDX-License-Identifier: EUPL-1.2
-->

# REST Auth JWT Module

> **Status:** Implemented
> **Package:** `dev.vertique.rest.auth.jwt`
> **Artifact:** `vertique-rest-auth-jwt`
> **Depends on:** rest-security, security-core, security-runtime

`vertique-rest-auth-jwt` turns a Vert.x `JWTAuth` into a fully wired Vertique authentication stack.
Include `JwtAuthModule` in the Dagger component, bind a `JWTAuth`, and every JAX-RS operation and
WebSocket endpoint that declares a bearer security requirement is authenticated from the
`Authorization: Bearer` header, with the token's claims mapped into the framework's typed
`SecurityContext` for `@RolesAllowed` and `@Authorized` enforcement.

It does **not** issue, refresh, or revoke tokens, and it does not own user management. Vertique
verifies inbound credentials against keys the application supplies; the identity provider owns
everything else.

---

## When To Use It

Install this module when an external identity provider issues JWTs and the application needs to
verify them on inbound HTTP requests or WebSocket upgrades.

`JwtAuthModule` includes `AuthModule` and `SecurityModule` from
`dev.vertique:vertique-rest-security`, so list it **instead of** those two, not alongside them. Pair
it with `dev.vertique:vertique-rest-websocket` to authenticate WebSocket upgrades with the same
token, and with `dev.vertique:vertique-security-runtime` (`SecurityAuthzModule`) when operations
declare `@RequiresAction` gates.

---

## Core Concepts

### Two enforcement layers, and only one of them is automatic

Issuer, audience, and expiry are checked in two different places, and the difference matters:

| Check | Where | When it applies |
|---|---|---|
| Signature | Vert.x `JWTAuth` | Always |
| `exp` / `nbf` (with clock skew) | Vert.x `JWTAuth` | **Only** when the `JWTAuth` was built with a `JwtValidationConfig` |
| `iss` | Vert.x `JWTAuth`, then re-checked by `JwtBearerSecuritySchemeHandler` | The handler check always applies |
| `aud` | Vert.x `JWTAuth`, then re-checked by `JwtBearerSecuritySchemeHandler` | The handler check always applies |

The handler's post-authentication `iss`/`aud` re-check is defense-in-depth: it reads
`jwt.validation.issuer` and `jwt.validation.audience` from the effective config and rejects a
mismatch with 401, **regardless of how the application built its `JWTAuth`**.

**Clock skew has no such backstop.** `jwt.validation.clockSkewSeconds` is applied only when the
application passes a `JwtValidationConfig` to `JwtAuthFactory.fromJwks(...)` /
`fromJwksAsync(...)`. Setting it in configuration alone does nothing — the application, not this
module, constructs the `JWTAuth`, and nothing re-applies leeway after authentication. The same is
true of `fromJwksRefreshing`, `fromSymmetricKey`, and `fromPublicKey`, none of which accept a
`JwtValidationConfig`.

If you rely on clock-skew tolerance, build the `JWTAuth` with the config overload and give the
handler the same values:

```java
@Provides
@Singleton
static JwtValidationConfig jwtValidation() {
    return JwtValidationConfig.builder()
            .issuer("https://auth.example.com/")
            .audience(List.of("https://api.example.com"))
            .clockSkewSeconds(30)
            .build();
}

@Provides
@Singleton
static JwtAuthConfig jwtAuthConfig(JwtValidationConfig validation) {
    return new JwtAuthConfig("bearerAuth", validation);   // drives the handler's iss/aud check
}

@Provides
@Singleton
static JWTAuth jwtAuth(Vertx vertx, JwtValidationConfig validation) {
    return JwtAuthFactory.fromJwks(vertx, "https://auth.example.com/.well-known/jwks.json", validation);
}
```

### Claims become framework authorization claims, not Vert.x authorizations

`@RolesAllowed` and `@Authorized(scopes = ...)` are evaluated against the `AuthorizationClaims` on
the request's `SecurityContext`. Those are produced during identity resolution in
`dev.vertique:vertique-rest-security` by the `SecurityClaimMapper`, which reads the `roles`,
`scope`, `scp`, and `permissions` claims from the verified token principal.

This module also contributes a Vert.x `AuthorizationProvider` (`JwtClaimAuthorizationProvider`) that
populates the Vert.x `User`'s own authorization cache from the same claims. That cache is available
to code that asks Vert.x directly — but the framework's authorization decision does **not** read it.
Adding another `AuthorizationProvider` to the multibinding will not change an authorization outcome;
replace the `SecurityClaimMapper` instead.

### Every rejection is reported before the request fails

Whether the token is missing, malformed, expired, wrongly signed, addressed to the wrong audience,
or refused by a custom claims validator, the module reports the rejection through
`CredentialRejectionReporter` with a stable reason code before failing the routing context. That
emission is what makes authentication failures observable to security-event observers and audit.

---

## Getting Started

```java
@Singleton
@Component(modules = {VertxModule.class, ConfigParsingModule.class,
                      RestModule.class, JwtAuthModule.class,
                      AppModule.class, ResourceModule.class})
interface AppComponent {
    HttpVerticle httpVerticle();
}
```

```java
@Module
public class AppModule {

    @Provides
    @Singleton
    static JWTAuth jwtAuth(Vertx vertx) {
        return JwtAuthFactory.fromJwks(vertx, "classpath:jwks.json");
    }
}
```

Declare the scheme on the application's OpenAPI configuration class, using the same name as
`jwt.schemeName`:

```java
@OpenAPIDefinition(
    info = @Info(title = "My API", version = "1.0.0"),
    security = @SecurityRequirement(name = "bearerAuth")
)
@SecurityScheme(
    name = "bearerAuth",
    type = SecuritySchemeType.HTTP,
    scheme = "bearer",
    bearerFormat = "JWT"
)
public class OpenApiConfig {}
```

Then annotate resource methods as usual:

```java
@Path("/items")
public class ItemResource {

    @GET
    @Path("/{id}")
    @RolesAllowed("user")
    public Future<Item> getItem(@PathParam("id") String id) { ... }

    @POST
    @Authorized(scopes = "write:items")
    public Future<Response> createItem(CreateItemRequest request) { ... }
}
```

---

## Key Classes

### `JwtAuthModule`

```java
@Module(includes = {AuthModule.class, SecurityModule.class})
public abstract class JwtAuthModule { ... }
```

The application must supply exactly one thing: a `JWTAuth` binding. Everything else is provided.

| Binding | Kind | What it is |
|---|---|---|
| `Set<SecuritySchemeHandler>` | `@IntoSet` | A `JwtBearerSecuritySchemeHandler` registered under the effective scheme name, for OpenAPI-described operations |
| `Set<RouteAuthHandler>` | `@IntoSet` | A route-level handler under the same scheme name, for transports with no OpenAPI description (WebSocket upgrades, action-only routes) |
| `Set<AuthorizationProvider>` | `@IntoSet` | `JwtClaimAuthorizationProvider` |
| `Set<OperationHandlerContributor>` | `@IntoSet` | `JwtClaimsValidatorContributor` at priority 50 when a `JwtClaimsValidator` is bound; otherwise a no-op contributor |
| `JwtAuthConfig` | `@BindsOptionalOf` | The application's optional whole-config override |
| `JwtClaimsValidator` | `@BindsOptionalOf` | The application's optional custom claim check |
| `@JwtEffective JwtAuthConfig` | `@Provides @Singleton` | The resolved config: the application override when bound, else the parsed `jwt` section |

The scheme handler and the route auth handler are **separate instances** built from the same
effective config, so both paths produce identical evidence and identical rejection reason codes.

### `JwtAuthConfig`

```java
public record JwtAuthConfig(String schemeName, JwtValidationConfig validation) {
    public static JwtAuthConfig defaults();
}
```

| Component | Default | Description |
|---|---|---|
| `schemeName` | `"bearerAuth"` | Must match the `@SecurityScheme` / `@SecurityRequirement` name |
| `validation` | An all-defaults `JwtValidationConfig` | Issuer / audience / clock-skew constraints |

The compact constructor normalizes `null` components to those defaults, so a partial `jwt` object —
`schemeName` only, say — deserializes safely. `fromJson` is the `@JsonCreator` and fills defaults for
omitted properties.

Bind your own `@Provides JwtAuthConfig` to override the parsed `jwt` section entirely. Injection
sites read the resolved value through the `@JwtEffective` qualifier; the unqualified binding is the
override channel that `JwtAuthModule` consumes as input.

### `JwtValidationConfig`

A Lombok builder type with fluent accessors, used both as the handler's enforcement policy and as
the input to `JwtAuthFactory`'s validating overloads.

| Field | Type | Default | Effect when unset |
|---|---|---|---|
| `issuer` | `String` | `null` | No `iss` check at either layer |
| `audience` | `List<String>` | `null` | No `aud` check at either layer |
| `clockSkewSeconds` | `int` | `30` | Only reaches Vert.x through `JwtAuthFactory`'s validating overloads |

```java
JwtValidationConfig config = JwtValidationConfig.builder()
        .issuer("https://auth.example.com/")
        .audience(List.of("https://api.example.com"))
        .clockSkewSeconds(30)
        .build();
```

`JwtAuthFactory` logs a startup warning when `issuer` or `audience` is unset in a validating
overload, because either omission leaves tokens open to substitution.

### `JwtEffective`

```java
@Qualifier
@Retention(RUNTIME)
@Target({METHOD, PARAMETER, FIELD})
public @interface JwtEffective {}
```

Marks the resolved `JwtAuthConfig`. Inject `@JwtEffective JwtAuthConfig` to read the values actually
in force; inject plain `JwtAuthConfig` only when providing an override.

### `JwtAuthFactory`

Standalone utility — not Dagger-managed. Call it inside a `@Provides JWTAuth` method.

| Method | Returns | Applies `JwtValidationConfig` to the `JWTAuth`? |
|---|---|---|
| `fromJwks(Vertx, String location)` | `JWTAuth` | No |
| `fromJwks(Vertx, String location, JwtValidationConfig)` | `JWTAuth` | Yes — issuer, audience, and leeway |
| `fromJwksAsync(Vertx, String location)` | `Future<JWTAuth>` | No |
| `fromJwksAsync(Vertx, String location, JwtValidationConfig)` | `Future<JWTAuth>` | Yes |
| `fromJwksRefreshing(Vertx, String location, Duration interval)` | `Future<JWTAuth>` | No — no overload accepts one |
| `fromSymmetricKey(Vertx, String algorithm, String secret)` | `JWTAuth` | No — HS256/HS384/HS512 |
| `fromPublicKey(Vertx, String algorithm, String pem)` | `JWTAuth` | No — RS\*, ES\*, PS\* |

Location handling for the JWKS methods:

| Prefix | Source |
|---|---|
| `classpath:` | Thread-context classloader resource |
| `http://` or `https://` | JDK `HttpClient`, HTTP/1.1, 10-second connect and request timeout; a non-200 response fails. Use `https://` in production — over `http://` an on-path attacker substitutes the signing keys, and the factory logs a warning saying so |
| _(anything else)_ | Filesystem, via `vertx.fileSystem().readFileBlocking()` |

`fromJwks` performs synchronous I/O. That is fine for `classpath:` and filesystem locations. For an
HTTP location on an event-loop thread, use `fromJwksAsync` — it dispatches the fetch through
`executeBlocking` — and compose the component build onto it:

```java
@Override
public void start(Promise<Void> startPromise) {
    String jwksUri = config().getJsonObject("auth").getString("jwksUri");
    JwtAuthFactory.fromJwksAsync(vertx, jwksUri)
        .compose(jwtAuth -> {
            AppComponent c = DaggerAppComponent.builder()
                    .vertxModule(new VertxModule(vertx, config()))
                    .appModule(new AppModule(jwtAuth))
                    .build();
            return c.verticleDeploymentManager().deployAll();
        })
        .onSuccess(v -> startPromise.complete())
        .onFailure(startPromise::fail);
}
```

**Failures:**

| Thrown | When |
|---|---|
| `IllegalArgumentException` | Any argument is null or blank; the document is not valid JSON; `keys` is not an array; `keys` is absent or empty |
| `UncheckedIOException` | The classpath resource is missing, or reading/fetching the document fails (including a non-200 HTTP status, or interruption) |

### `RefreshableJwtAuth`

A `JWTAuth` that periodically re-fetches its JWKS — for identity providers that rotate keys. Obtain
it from `JwtAuthFactory.fromJwksRefreshing(...)`, or directly:

```java
RefreshableJwtAuth.create(vertx, "https://auth.example.com/.well-known/jwks.json", Duration.ofMinutes(60))
        .compose(jwtAuth -> { ... });
```

- The delegate is a `volatile` field swapped atomically on each successful refresh. In-flight
  authentications complete against the key set that was current when they began.
- A failed refresh is logged and the existing keys are kept — key rotation outages do not take the
  service down.
- Overlapping refresh ticks are skipped while one is in flight.
- `close()` cancels the timer and is idempotent; a refresh still in flight afterwards completes but
  does not swap the delegate. Vert.x also cancels the timer when the owning verticle is undeployed.

**It applies no `JwtValidationConfig`.** A refreshing `JWTAuth` enforces signature only at the Vert.x
layer; `iss` and `aud` are still enforced by the handler, and `clockSkewSeconds` is not applied at
all.

### `JwtBearerSecuritySchemeHandler`

The credential verifier. It parses the `Authorization` header itself and calls
`JWTAuth.authenticate` directly rather than delegating to Vert.x's `JWTAuthHandler`.

```java
public class JwtBearerSecuritySchemeHandler implements SecuritySchemeHandler, Handler<RoutingContext> {

    public JwtBearerSecuritySchemeHandler(
            String schemeName,
            JWTAuth jwtAuth,
            JwtValidationConfig validationConfig,
            CredentialRejectionReporter rejectionReporter);

    @Override public String schemeName();
    @Override public void configure(SecuritySchemeRegistry registry);
    @Override public void handle(RoutingContext ctx);

    public Future<User> authenticate(RoutingContext ctx);
}
```

`handle` is the single-scheme path: it sets the user, appends evidence, and calls `next()`, or
reports the rejection and fails the context. `authenticate` is the composable path — it verifies and
returns the `User` **without mutating the routing context**, so the scheme can be one alternative in
a Vert.x `ChainAuthHandler.any()` OR chain when an operation declares two or more alternative bearer
requirements. Exactly one of the two paths appends the evidence per request.

On success the handler builds an `AuthenticationEvidence` carrying:

| Field | Value |
|---|---|
| method | `DefaultAuthMethod.jwt()` |
| `notAfter` | The `exp` claim, as an `Instant` |
| verification source | `JwksVerificationSource` with the configured issuer and a JWKS URI derived as `<issuer>/.well-known/jwks.json`; `kid` and `alg` are always empty because Vert.x does not expose the verified JWT header |
| safe attributes | `sub`, `client_id`, and `azp` when present — never raw token material |

`client_id` and `azp` are what let identity resolution classify a client-credentials token as a
service principal rather than a user.

#### Rejection reason codes

Every one of these fails the request with **401** and emits a credential-rejected event first.

| Code | Condition |
|---|---|
| `BEARER_MISSING` | No `Authorization` header |
| `BEARER_MALFORMED` | Header present but not `Bearer <token>`, an empty token, or a token the provider reports as structurally invalid |
| `JWT_EXPIRED` | The provider reports the token expired |
| `JWT_SIGNATURE_INVALID` | Signature verification failed |
| `JWT_AUDIENCE_INVALID` | The token's `aud` set does not overlap `jwt.validation.audience`, or the provider rejected the audience |
| `JWT_ISSUER_INVALID` | The token's `iss` is absent or differs from `jwt.validation.issuer`, or the provider rejected the issuer |
| `JWT_ALG_UNSUPPORTED` | The token's algorithm is not permitted |
| `JWT_INVALID` | Any other verification failure |
| `JWT_CLAIMS_INVALID` | A bound `JwtClaimsValidator` rejected the token (emitted by `JwtClaimsValidatorContributor`) |

Issuer comparison is exact string equality; a token with no `iss` is rejected when an issuer is
configured. Audience comparison is **any-match** — per RFC 7519 the `aud` claim may be a single
string or an array, and the token is accepted when its audience set overlaps the configured list. A
token with no `aud` is rejected when an audience list is configured.

### `JwtClaimAuthorizationProvider`

A Vert.x `AuthorizationProvider` with id `"jwt-claims"` that populates the Vert.x `User`'s
authorization cache from the token claims.

| Claim | Vert.x authorization | Convention |
|---|---|---|
| `roles` | `RoleBasedAuthorization` | Common IdP convention |
| `scope` | `PermissionBasedAuthorization` | OAuth 2.0 |
| `scp` | `PermissionBasedAuthorization` | Microsoft Entra ID |
| `permissions` | `PermissionBasedAuthorization` | Auth0 |

Each claim accepts either a JSON array (`["read", "write"]`) or a space-delimited string
(`"read write"`). Non-string array elements and blank values are skipped. A `null` user or a user
with no principal is a no-op.

Framework authorization does not consult this cache — see
[Claims become framework authorization claims](#claims-become-framework-authorization-claims-not-vertx-authorizations).
The cache is there for application code that queries Vert.x directly.

---

## Extension Points

### `JwtClaimsValidator` (optional binding)

Business-specific claim checks beyond signature, expiry, issuer, and audience.

```java
@FunctionalInterface
public interface JwtClaimsValidator {
    void validate(Map<String, Object> claims) throws SecurityException;
}
```

```java
@Provides
@Singleton
static JwtClaimsValidator tenantValidator() {
    return claims -> {
        if (!claims.containsKey("tenant_id")) {
            throw new SecurityException("Missing required 'tenant_id' claim");
        }
    };
}
```

Registered as a per-operation handler by `JwtClaimsValidatorContributor` at contributor priority
**50** — after authentication, before authorization (100). Throwing any exception emits a
`JWT_CLAIMS_INVALID` rejection and fails the request with 401; returning normally lets it through.

**Invariants and gotchas:**

- **Only one binding is supported.** Compose several checks inside one implementation; a second
  `@Provides JwtClaimsValidator` is a Dagger duplicate-binding error.
- **It runs only when a verified user is present.** With no `ctx.user()` — a `@PermitAll` operation,
  for instance — the handler passes the request through without calling the validator. It is a claim
  *check*, not an authentication requirement; it cannot make an anonymous route authenticated.
- **It is invoked per operation, not by the scheme handler.** The claims it receives are
  `ctx.user().principal()` as a raw map.
- **Implementations must be thread-safe.** The binding is a singleton invoked concurrently.

### `SecuritySchemeHandler` and `RouteAuthHandler` (multibindings)

Both multibindings are declared by `dev.vertique:vertique-rest-security`; this module contributes one
entry to each under the effective scheme name. Contribute your own entries for additional schemes —
an API key header, mutual TLS — without depending on this module. Scheme names must be unique across
all contributions.

### Scheme name and validation override

Bind a `JwtAuthConfig` to replace the parsed `jwt` section entirely:

```java
@Provides
@Singleton
static JwtAuthConfig jwtAuthConfig() {
    return new JwtAuthConfig(
            "myCustomScheme",
            JwtValidationConfig.builder()
                    .issuer("https://auth.example.com/")
                    .audience(List.of("https://api.example.com"))
                    .build());
}
```

The override wins over configuration wholesale — it is not merged field by field.

---

## Configuration

Parsed from the `jwt` section of the application configuration into `JwtAuthConfig` at the
`JwtAuthModule` boundary. Every key is optional.

| Key | Type | Default | Description |
|---|---|---|---|
| `jwt.schemeName` | String | `"bearerAuth"` | Security scheme name the handlers register under |
| `jwt.validation.issuer` | String | _(none)_ | Expected `iss`. Unset means no issuer check |
| `jwt.validation.audience` | List\<String\> | _(none)_ | Accepted `aud` values, any-match. Unset or empty means no audience check |
| `jwt.validation.clockSkewSeconds` | int | `30` | Leeway for `exp`/`nbf`. Reaches Vert.x **only** through `JwtAuthFactory`'s validating overloads |

```json
{
  "jwt": {
    "schemeName": "bearerAuth",
    "validation": {
      "issuer": "https://auth.example.com/",
      "audience": ["https://api.example.com"],
      "clockSkewSeconds": 30
    }
  }
}
```

An application-supplied `@Provides JwtAuthConfig` takes priority over this section entirely.

---

## Failures, Constraints, and Common Mistakes

### Startup failures

| Condition | Result |
|---|---|
| No `JWTAuth` binding in the component | Dagger compilation error — the module never provides one |
| Two `JwtClaimsValidator` bindings | Dagger duplicate-binding error |
| `jwt.schemeName` does not match the `@SecurityScheme` name | Any operation declaring that requirement has no collected handler and route registration fails, fail-closed, rather than mounting the operation unauthenticated |
| A JWKS location that cannot be read, or a document with no `keys` | `IllegalArgumentException` / `UncheckedIOException` from `JwtAuthFactory`, during component construction |

### Request-time outcomes

| Outcome | Status |
|---|---|
| Every rejection reason code in the table above | 401 |
| Authenticated but lacking a required role or scope | 403 (or 401 when authentication was required and absent) — decided by `dev.vertique:vertique-rest-security` |
| Authenticated and authorized | The operation runs |

### Common mistakes

- **Setting `jwt.validation.clockSkewSeconds` and expecting it to take effect.** It reaches Vert.x
  only when the application passes a `JwtValidationConfig` to a `JwtAuthFactory` validating overload.
  Configuration alone changes nothing about time-claim validation.
- **Using `fromJwksRefreshing` and assuming it validates like `fromJwks(..., config)`.** It does not
  accept a validation config. Issuer and audience are still enforced by the handler; clock skew is
  not enforced at all.
- **Listing `AuthModule` and `SecurityModule` alongside `JwtAuthModule`.** `JwtAuthModule` already
  includes both.
- **Fetching JWKS over `http://` in production.** An on-path attacker substitutes the signing keys
  and mints accepted tokens. The factory warns; treat the warning as an error.
- **Leaving `issuer` and `audience` unset in production.** Any validly signed token from any issuer
  reachable through the configured keys is accepted.
- **Contributing another `AuthorizationProvider` to change `@RolesAllowed` outcomes.** The framework
  decides from the `SecurityContext`'s claims; replace the `SecurityClaimMapper` instead.
- **Expecting `JwtClaimsValidator` to run on unauthenticated routes.** It is skipped when there is no
  verified user.
- **Calling `fromJwks` with an HTTP location from an event-loop thread.** It blocks. Use
  `fromJwksAsync`.

---

## Dependencies

| Dependency | Why |
|---|---|
| `dev.vertique:vertique-rest-security` | `AuthModule`/`SecurityModule`, `CredentialRejectionReporter`, evidence bridge, claim extraction |
| `dev.vertique:vertique-security-core` | `AuthenticationEvidence`, `JwksVerificationSource`, `DefaultAuthMethod` |
| `dev.vertique:vertique-security-runtime` | Security event emission behind the rejection reporter |
| `io.vertx:vertx-auth-jwt` | `JWTAuth`, `JWTAuthOptions`, `JWTOptions` |
| `com.fasterxml.jackson.core:jackson-databind` | Typed configuration binding |
| `com.google.dagger:dagger` | Module and multibinding declarations |
| `org.projectlombok:lombok` | Compile-time only |
