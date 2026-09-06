<!--
SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
SPDX-License-Identifier: EUPL-1.2
-->

# REST Auth JWT Module

> **Status:** Beta
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

### Two enforcement layers, and why clock skew only reaches one

Signature, time claims, issuer, and audience are not all checked in the same place, and the
difference decides what the framework can still fix after the fact:

| Check | Where | When it applies |
|---|---|---|
| Signature | Vert.x `JWTAuth` | Always |
| `exp` / `nbf` / `iat` (with clock-skew leeway) | Vert.x `JWTAuth` | Always for a `JwtAuthFactory`-built provider — every overload applies a `JwtValidationConfig`, and the overloads that take none apply the defaults |
| `iss` | Vert.x `JWTAuth` when the provider was built with an issuer, then re-checked by `JwtBearerSecuritySchemeHandler` | The handler check always applies |
| `aud` | Vert.x `JWTAuth` when the provider was built with an audience, then re-checked by `JwtBearerSecuritySchemeHandler` | The handler check always applies |

The handler's post-authentication `iss`/`aud` re-check is defense-in-depth: it reads
`jwt.validation.issuer` and `jwt.validation.audience` from the effective config and rejects a
mismatch with 401, **regardless of how the application built its `JWTAuth`**.

**Clock skew can have no such backstop, so it must be right at construction.** Leeway is
*permissive*: once Vert.x has rejected a token as expired, not yet valid, or issued in the future,
no later handler can un-reject it. Every `JwtAuthFactory` method therefore applies a
`JwtValidationConfig` — the overloads that take none apply `JwtValidationConfig.builder().build()`,
so the documented 30-second default reaches Vert.x instead of Vert.x's own leeway of `0`. The
refreshing provider applies its config to the initial key set and to every refreshed one.

What the framework cannot do is *choose* the value for you: the application, not this module,
constructs the `JWTAuth`, so `jwt.validation.clockSkewSeconds` on its own changes nothing. Keep the
two in one place by injecting `@JwtEffective JwtAuthConfig` and passing its `validation()` to the
factory:

```java
@Provides
@Singleton
static JWTAuth jwtAuth(Vertx vertx, @JwtEffective JwtAuthConfig effective) {
    return JwtAuthFactory.fromJwks(
            vertx, "https://auth.example.com/.well-known/jwks.json", effective.validation());
}
```

That is the same `JwtValidationConfig` the handler enforces `iss`/`aud` from, so the two layers
cannot drift apart. If they do drift — a factory call built with one clock skew while
`jwt.validation.clockSkewSeconds` says another — startup fails; see
[`JwtAuthModule`](#jwtauthmodule).

A fully programmatic setup works the same way, as long as one `JwtValidationConfig` instance feeds
both the `JwtAuthConfig` override and the factory call:

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
to code that asks Vert.x directly. Contributed `AuthorizationProvider`s reach the framework's
`AuthorizationClaims` only when the application includes the opt-in `VertxAuthorizationImportModule`
from `dev.vertique:vertique-rest-security`, which consults them at identity-resolution time — and
that import deliberately excludes this provider's `"jwt-claims"` bucket, because its
scope→permission projection is lossy (a `scope` claim would come back as a `PERMISSION` authority).
JWT claims already reach `AuthorizationClaims` with full kind fidelity through the
`SecurityClaimMapper`; to shape JWT-derived claims, replace or extend the `SecurityClaimMapper`.

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
    static JWTAuth jwtAuth(Vertx vertx, @JwtEffective JwtAuthConfig effective) {
        return JwtAuthFactory.fromJwks(vertx, "classpath:jwks.json", effective.validation());
    }
}
```

Passing `effective.validation()` is the recommended shape even before any `jwt` section exists — it
resolves to the defaults then, and it keeps the provider aligned with configuration the day one is
added. The two-argument `JwtAuthFactory.fromJwks(vertx, "classpath:jwks.json")` behaves identically
only while `jwt.validation` is entirely unset: it applies the default clock skew but leaves `iss`
and `aud` unconstrained at the Vert.x layer, and a configured `clockSkewSeconds` other than the
default would fail startup against it.

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
| `Set<RouteAuthHandler>` | `@IntoSet` | A route-level handler under the same scheme name, for transports with no OpenAPI description (WebSocket upgrades, action-only routes), including explicit optional-authentication support |
| `Set<AuthorizationProvider>` | `@IntoSet` | `JwtClaimAuthorizationProvider` — feeds the Vert.x cache; the opt-in `VertxAuthorizationImportModule` import always excludes it |
| `Set<OperationHandlerContributor>` | `@IntoSet` | `JwtClaimsValidatorContributor` at priority 50 when a `JwtClaimsValidator` is bound; otherwise a no-op contributor |
| `JwtAuthConfig` | `@BindsOptionalOf` | The application's optional whole-config override |
| `JwtClaimsValidator` | `@BindsOptionalOf` | The application's optional custom claim check |
| `@JwtEffective JwtAuthConfig` | `@Provides @Singleton` | The resolved config: the application override when bound, else the parsed `jwt` section |

The scheme handler and the route auth handler are **separate instances** built from the same
effective config, so both paths produce identical evidence and identical rejection reason codes.

The JWT route handler also advertises `createOptionalHandler()`. With no `Authorization` header it
continues exactly once without setting a Vert.x user or appending authentication evidence. Any
present header, including a blank, non-Bearer, malformed, expired, or invalid bearer value, delegates
to the same JWT verifier as `createHandler()` and therefore rejects rather than becoming anonymous.

Both of those bindings run the same startup check before handing out a handler. When the bound
`JWTAuth` was built by `JwtAuthFactory` (including a `RefreshableJwtAuth`) and the clock skew it
applied differs from the effective config's `clockSkewSeconds`, component construction fails with a
`ConfigurationException` naming both values. Clock skew is the only field compared, because it is
the only one the handler cannot re-enforce — a mismatched issuer or audience is already fail-closed,
since the handler independently rejects anything the configured values do not accept. Putting the
check on both bindings means an application that authenticates only over WebSocket (and so never
resolves the `SecuritySchemeHandler` multibinding) still gets it.

A `JWTAuth` the framework did **not** build — `JWTAuth.create(...)` called directly, or a custom
implementation — carries no record of what it applied. It is accepted silently: no check, no
warning, and no clock-skew enforcement from this module.

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
the input to `JwtAuthFactory`.

| Field | Type | Default | Notes |
|---|---|---|---|
| `issuer` | `String` | `null` | Unset means no `iss` check at either layer |
| `audience` | `List<String>` | `null` | Unset or empty means no `aud` check at either layer |
| `clockSkewSeconds` | `int` | `30` | Leeway applied to `exp`, `nbf`, and `iat` by every `JwtAuthFactory` construction path. Must be within `[0, MAX_CLOCK_SKEW_SECONDS]` |

```java
JwtValidationConfig config = JwtValidationConfig.builder()
        .issuer("https://auth.example.com/")
        .audience(List.of("https://api.example.com"))
        .clockSkewSeconds(30)
        .build();
```

`public static final int MAX_CLOCK_SKEW_SECONDS = 300` is the upper bound. A `clockSkewSeconds`
below `0` or above it throws `dev.vertique.core.exception.ConfigurationException` from the
constructor — which the builder and Jackson both route through, so an out-of-range
`jwt.validation.clockSkewSeconds` fails while the `jwt` section is parsed, at startup, rather than
widening the acceptance window silently.

The overloads that **take** a `JwtValidationConfig` log a startup warning when its `issuer` or
`audience` is unset, because either omission leaves tokens open to substitution. The overloads that
take none stay silent — that caller never asked for issuer/audience validation — while still
applying the default leeway.

### `JwtEffective`

```java
@Qualifier
@Retention(RUNTIME)
@Target({METHOD, PARAMETER, FIELD})
public @interface JwtEffective {}
```

Marks the resolved `JwtAuthConfig`. Inject `@JwtEffective JwtAuthConfig` to read the values actually
in force; inject plain `JwtAuthConfig` only when providing an override. Injecting it into the
application's own `@Provides JWTAuth` method is the supported way to build the provider from the
same config the handlers enforce — that binding depends only on the `jwt` section and the optional
override, so it introduces no cycle.

### `JwtAuthFactory`

Standalone utility — not Dagger-managed. Call it inside a `@Provides JWTAuth` method.

| Method | Returns | Validation applied to the `JWTAuth` |
|---|---|---|
| `fromJwks(Vertx, String location)` | `JWTAuth` | Defaults |
| `fromJwks(Vertx, String location, JwtValidationConfig)` | `JWTAuth` | Issuer, audience, and leeway |
| `fromJwksAsync(Vertx, String location)` | `Future<JWTAuth>` | Defaults |
| `fromJwksAsync(Vertx, String location, JwtValidationConfig)` | `Future<JWTAuth>` | Issuer, audience, and leeway |
| `fromJwksRefreshing(Vertx, String location, Duration interval)` | `Future<RefreshableJwtAuth>` | Defaults, on the initial key set and every refreshed one |
| `fromJwksRefreshing(Vertx, String location, Duration interval, JwtValidationConfig)` | `Future<RefreshableJwtAuth>` | Issuer, audience, and leeway, on the initial key set and every refreshed one |
| `fromSymmetricKey(Vertx, String algorithm, String secret)` | `JWTAuth` | Defaults — HS256/HS384/HS512 |
| `fromSymmetricKey(Vertx, String algorithm, String secret, JwtValidationConfig)` | `JWTAuth` | Issuer, audience, and leeway |
| `fromPublicKey(Vertx, String algorithm, String pem)` | `JWTAuth` | Defaults — RS\*, ES\*, PS\* |
| `fromPublicKey(Vertx, String algorithm, String pem, JwtValidationConfig)` | `JWTAuth` | Issuer, audience, and leeway |

"Defaults" means `JwtValidationConfig.builder().build()`: 30-second `exp`/`nbf`/`iat` leeway, no
issuer or audience constraint, and no missing-constraint warning. Every method above records the
`JwtValidationConfig` it applied so `JwtAuthModule` can compare it against configuration at startup;
the return type is still a plain `JWTAuth`, so no application `@Provides` signature changes.

Location handling for the JWKS methods:

| Prefix | Source |
|---|---|
| `classpath:` | Thread-context classloader resource |
| `http://` or `https://` | JDK `HttpClient`, HTTP/1.1, 10-second connect and request timeout; a non-200 response fails. Use `https://` in production — over `http://` an on-path attacker substitutes the signing keys, and the factory logs a warning saying so |
| _(anything else)_ | Filesystem, via `vertx.fileSystem().readFileBlocking()` |

`fromJwks` performs synchronous I/O. That is fine for `classpath:` and filesystem locations. On an
event-loop thread, use `fromJwksAsync` — it reads *every* location kind on a worker thread via
`executeBlocking`, since classpath and filesystem reads block too — and compose application startup
onto it, since the `JWTAuth` must exist before
the Dagger component that consumes it is built. `VertiqueApplicationBootstrap.start(...)` resolves
to a `Future<VertiqueApplicationHandle<AppComponent>>`; a custom host retains the handle and
delegates shutdown to it, per `dev.vertique:vertique-application`:

```java
public class MainVerticle extends AbstractVerticle {
    private VertiqueApplicationHandle<AppComponent> handle;

    @Override
    public void start(Promise<Void> startPromise) {
        String jwksUri = config().getJsonObject("auth").getString("jwksUri");
        JwtAuthFactory.fromJwksAsync(vertx, jwksUri)
            .compose(jwtAuth -> VertiqueApplicationBootstrap.start(
                    VertiqueRuntime.of(vertx, config()),
                    rt -> DaggerAppComponent.builder()
                            .vertxModule(new VertxModule(rt.vertx(), rt.config()))
                            .appModule(new AppModule(jwtAuth))
                            .build()))
            .onSuccess(h -> {
                handle = h;
                startPromise.complete();
            })
            .onFailure(startPromise::fail);
    }

    @Override
    public void stop(Promise<Void> stopPromise) {
        handle.shutdown().onComplete(v -> stopPromise.complete());
    }
}
```

**Failures:**

| Thrown | When |
|---|---|
| `IllegalArgumentException` | `location`, `algorithm`, `secret`, or `pem` is null or blank; the document is not valid JSON; `keys` is not an array; `keys` is absent or empty |
| `NullPointerException` | `vertx` or the supplied `JwtValidationConfig` is null |
| `UncheckedIOException` | The classpath resource is missing, or reading/fetching the document fails (including a non-200 HTTP status, or interruption) |

The `fromJwksAsync` overloads still validate their arguments **synchronously** — a blank `location`
throws on the calling thread rather than producing a failed future. Only read failures surface as a
failed future.

### `RefreshableJwtAuth`

A `JWTAuth` that periodically re-fetches its JWKS — for identity providers that rotate keys. Obtain
it from `JwtAuthFactory.fromJwksRefreshing(...)`, or directly:

```java
public static Future<RefreshableJwtAuth> create(Vertx vertx, String jwksLocation, Duration refreshInterval);
public static Future<RefreshableJwtAuth> create(Vertx vertx, String jwksLocation, Duration refreshInterval,
                                                JwtValidationConfig config);
```

```java
RefreshableJwtAuth.create(vertx, "https://auth.example.com/.well-known/jwks.json",
                Duration.ofMinutes(60), effective.validation())
        .compose(jwtAuth -> { ... });
```

Both `create` overloads and both `fromJwksRefreshing` overloads resolve to
`Future<RefreshableJwtAuth>`, not `Future<JWTAuth>`, so `close()` stays reachable from whatever holds
the result.

- The delegate is a `volatile` field swapped atomically on each successful refresh. In-flight
  authentications complete against the key set that was current when they began.
- A failed refresh is logged and the existing keys are kept — key rotation outages do not take the
  service down.
- Overlapping refresh ticks are skipped while one is in flight.
- `close()` cancels the timer and is idempotent; a refresh still in flight afterwards completes but
  does not swap the delegate. Vert.x also cancels the timer when the owning verticle is undeployed —
  but only when `create` ran on that verticle's context. A `main()`-style bootstrap that creates the
  provider outside a verticle keeps the timer running until you call `close()` yourself.

**The `JwtValidationConfig` given at creation is applied to every delegate**, the initial one and
each refreshed one, and is never re-read. A key rotation therefore cannot silently relax the issuer,
audience, or `exp`/`nbf`/`iat` leeway that guarded the initial key set. The overload that takes no
config applies `JwtValidationConfig.builder().build()`, so the 30-second default leeway holds across
refreshes as well. `iss` and `aud` remain enforced a second time by the handler, as with any other
provider.

The missing issuer/audience warning is emitted at most once, on the initial load — refresh ticks
never repeat it.

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

The cache is there for application code that queries the Vert.x authorization API directly. The
framework's opt-in import path — `VertxAuthorizationImportModule` in
`dev.vertique:vertique-rest-security` — excludes this provider's `"jwt-claims"` bucket by design:
collapsing OAuth scopes into `PermissionBasedAuthorization` loses the scope/permission distinction,
which the `SecurityClaimMapper` already preserves when mapping the same claims into
`AuthorizationClaims`. See
[Claims become framework authorization claims](#claims-become-framework-authorization-claims-not-vertx-authorizations).

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

The `detail` is dropped only where the rejection's 401 *overrides* the status the validator's own
exception would have produced — a plain `SecurityException` hitting the 500 catch-all, say, or a bean
validation failure mapping to 400. An exception that already maps to 401 —
`dev.vertique.core.exception.UnauthorizedException`, or a JAX-RS `NotAuthorizedException` — keeps its
message, and that message reaches the client as the `detail`; treat every validator exception message
as publishable and never name tenants, revocation state, or other internals in one. An application
that needs a specific detail on every rejection registers its own `ExceptionMapper` for the exception
type the validator throws; an application-contributed mapper outranks the framework's rejection status
and owns the whole response body.

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
| `jwt.validation.clockSkewSeconds` | int | `30` | Leeway for `exp`/`nbf`/`iat`, applied at `JWTAuth` construction. Must be `0`–`300`. Must equal what the application passed to `JwtAuthFactory`, or startup fails |

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

An application-supplied `@Provides JwtAuthConfig` takes priority over this section entirely — which
also makes it, not the `jwt` section, the value the startup clock-skew check compares against.

---

## Failures, Constraints, and Common Mistakes

### Startup failures

| Condition | Result |
|---|---|
| No `JWTAuth` binding in the component | Dagger compilation error — the module never provides one |
| Two `JwtClaimsValidator` bindings | Dagger duplicate-binding error |
| `jwt.schemeName` does not match the `@SecurityScheme` name | Any operation declaring that requirement has no collected handler and route registration fails, fail-closed, rather than mounting the operation unauthenticated |
| A JWKS location that cannot be read, or a document with no `keys` | `IllegalArgumentException` / `UncheckedIOException` from `JwtAuthFactory`, during component construction |
| `jwt.validation.clockSkewSeconds` outside `0`–`300` | `ConfigurationException` while the `jwt` section is parsed |
| A `JwtAuthFactory`-built `JWTAuth` whose applied clock skew differs from `jwt.validation.clockSkewSeconds` | `ConfigurationException` naming both values, during component construction |

### Request-time outcomes

| Outcome | Status |
|---|---|
| Every rejection reason code in the table above | 401 |
| Authenticated but lacking a required role or scope | 403 (or 401 when authentication was required and absent) — decided by `dev.vertique:vertique-rest-security` |
| Authenticated and authorized | The operation runs |

### Common mistakes

- **Letting `jwt.validation.clockSkewSeconds` and the value handed to `JwtAuthFactory` drift apart.**
  Startup fails with a `ConfigurationException` naming both, because clock skew cannot be enforced
  after construction. Inject `@JwtEffective JwtAuthConfig` into the `@Provides JWTAuth` method and
  pass its `validation()` so there is only one value to get right.
- **Hand-rolling the `JWTAuth` and expecting `jwt.validation.clockSkewSeconds` to apply.** A provider
  built outside `JwtAuthFactory` — `JWTAuth.create(...)` called directly, or a custom implementation
  — records nothing for the framework to compare, so it gets no clock-skew enforcement, no startup
  check, and no warning. Whatever `JWTOptions` leeway you set is what runs. Build through
  `JwtAuthFactory`, or keep the configured value aligned with the leeway you set by hand.
- **Listing `AuthModule` and `SecurityModule` alongside `JwtAuthModule`.** `JwtAuthModule` already
  includes both.
- **Fetching JWKS over `http://` in production.** An on-path attacker substitutes the signing keys
  and mints accepted tokens. The factory warns; treat the warning as an error.
- **Leaving `issuer` and `audience` unset in production.** Any validly signed token from any issuer
  reachable through the configured keys is accepted.
- **Contributing another `AuthorizationProvider` and expecting it to change `@RolesAllowed` outcomes
  by itself.** The multibinding is inert unless the application also includes the opt-in
  `VertxAuthorizationImportModule` from `dev.vertique:vertique-rest-security`; with it included,
  contributed providers are imported into `AuthorizationClaims` — except
  `JwtClaimAuthorizationProvider`, which is never imported regardless. To shape JWT-derived claims,
  replace the `SecurityClaimMapper`.
- **Expecting `JwtClaimsValidator` to run on unauthenticated routes.** It is skipped when there is no
  verified user.
- **Calling `fromJwks` with an HTTP location from an event-loop thread.** It blocks. Use
  `fromJwksAsync`.
- **Widening `fromJwksRefreshing`'s result to `JWTAuth` and dropping the reference.** It resolves to
  `Future<RefreshableJwtAuth>` precisely so `close()` can cancel the refresh timer at shutdown; keep
  the concrete type somewhere reachable.

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
