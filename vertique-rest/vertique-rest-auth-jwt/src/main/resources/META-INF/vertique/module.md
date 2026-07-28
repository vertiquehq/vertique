<!--
SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
SPDX-License-Identifier: EUPL-1.2
-->

# REST Auth JWT Module

> **Status:** Implemented
> **Package:** `dev.vertique.rest.auth.jwt`
> **Artifact:** `rest-auth-jwt`
> **Depends on:** rest-security, vertx-auth-jwt

Zero-boilerplate JWT bearer token authentication for the REST framework. Applications include `JwtAuthModule` in their Dagger component (instead of wiring `AuthModule` and `SecurityModule` directly), provide a `JWTAuth` binding, and get a fully wired JWT authentication stack: an OpenAPI security scheme handler, claim-based authorization extraction for `@RolesAllowed`/`@Authorized`, and `SecurityContext` population.

---

## Key Classes

### JwtAuthModule

Dagger `@Module` that composes the JWT authentication stack. Declared as:

```java
@Module(includes = {AuthModule.class, SecurityModule.class})
public abstract class JwtAuthModule { ... }
```

It includes both `AuthModule` and `SecurityModule` from `rest-security`, so applications do not need to list those separately. The application must provide a `JWTAuth` binding (typically in `AppModule`).

**Provided bindings:**

| Binding | Kind | Description |
|---------|------|-------------|
| `Set<SecuritySchemeHandler>` | `@IntoSet` | `JwtBearerSecuritySchemeHandler` for the configured scheme name |
| `Set<AuthorizationProvider>` | `@IntoSet` | `JwtClaimAuthorizationProvider` extracting JWT claims |
| `Set<RouteAuthHandler>` | `@IntoSet` | Route-level auth handler for non-OpenAPI transports (e.g. WebSocket) for the configured scheme name |
| `Optional<JwtAuthConfig>` | `@BindsOptionalOf` | Optional app-supplied override for the entire `JwtAuthConfig`; when present, wins over the config-parsed default |
| `@JwtEffective JwtAuthConfig` | `@Provides @Singleton` | The resolved effective config: app override when present, otherwise parsed from the `"jwt"` section |

**Minimal application component:**

```java
@Singleton
@Component(modules = {
    VertxModule.class,
    RestModule.class,
    JwtAuthModule.class,   // includes AuthModule + SecurityModule automatically
    AppModule.class,
    ResourceModule.class
})
interface AppComponent {
    HttpVerticle httpVerticle();
}
```

**Providing `JWTAuth` in `AppModule`:**

```java
@Module
public class AppModule {

    @Provides @Singleton
    static JWTAuth jwtAuth(Vertx vertx) {
        return JwtAuthFactory.fromJwks(vertx, "classpath:jwks.json");
    }
}
```

**Overriding the scheme name and/or validation via `JwtAuthConfig`:**

```java
@Module
public class AppModule {

    @Provides
    static JwtAuthConfig jwtAuthConfig() {
        return new JwtAuthConfig(
            "myCustomScheme",  // must match @SecurityScheme name
            JwtValidationConfig.builder()
                .issuer("https://auth.example.com/")
                .audience(List.of("https://api.example.com"))
                .clockSkewSeconds(30)
                .build());
    }
}
```

When no `@Provides JwtAuthConfig` is present, `JwtAuthModule` parses the typed config from the `"jwt"` section of the application config instead.

---

### JwtAuthConfig

Typed configuration record for JWT bearer authentication, parsed from the `"jwt"` section of the application config via the injected `ConfigParser`.

```java
public record JwtAuthConfig(String schemeName, JwtValidationConfig validation) {
    public static JwtAuthConfig defaults() { ... }
}
```

| Component | Default | Description |
|-----------|---------|-------------|
| `schemeName` | `"bearerAuth"` | OpenAPI security scheme name the handler registers under |
| `validation` | `JwtValidationConfig` defaults | Issuer / audience / clock-skew validation constraints |

`JwtAuthConfig.fromJson` is the `@JsonCreator` factory (fills defaults for omitted properties). The compact constructor normalizes `null` components to defaults so a partial `jwt` object (e.g. `schemeName` only) deserializes safely.

---

### JwtEffective

Dagger `@Qualifier` marking the **effective** `JwtAuthConfig` resolved by `JwtAuthModule`.

```java
@Qualifier
@Retention(RUNTIME)
@Target({METHOD, PARAMETER, FIELD})
public @interface JwtEffective {}
```

The effective config is the app-bound `JwtAuthConfig` (via `@Provides JwtAuthConfig` in the app module) when present, otherwise the config-parsed default. The qualifier disambiguates the resolved binding from the optional app-supplied override that `JwtAuthModule` consumes as input.

---

### JwtAuthFactory

Standalone utility class for creating `JWTAuth` instances from common key sources. Not managed by Dagger — call its methods inside a `@Provides JWTAuth` binding in `AppModule`.

**Method reference:**

| Method | Returns | Description |
|--------|---------|-------------|
| `fromJwks(Vertx, String location)` | `JWTAuth` | Creates JWTAuth from a JWKS document. Auto-detects location by prefix. |
| `fromJwksAsync(Vertx, String location)` | `Future<JWTAuth>` | Async variant; HTTP fetch runs off-event-loop via `executeBlocking`. |
| `fromJwksRefreshing(Vertx, String location, Duration refreshInterval)` | `Future<JWTAuth>` | Self-refreshing variant; returns a `RefreshableJwtAuth` that periodically re-fetches JWKS. |
| `fromSymmetricKey(Vertx, String algorithm, String secret)` | `JWTAuth` | HMAC symmetric key (HS256, HS384, HS512). |
| `fromPublicKey(Vertx, String algorithm, String pem)` | `JWTAuth` | PEM-encoded asymmetric public key (RS256, ES256, PS256, etc.). |

**Location auto-detection for `fromJwks` / `fromJwksAsync`:**

| Prefix | Source |
|--------|--------|
| `classpath:` | Classpath resource (reads via `Thread.currentThread().getContextClassLoader()`) |
| `http://` or `https://` | HTTP fetch with 10-second timeout (use `https://` in production; `http://` is local-development only — an on-path attacker could substitute the signing keys) |
| _(anything else)_ | Filesystem path via `vertx.fileSystem().readFileBlocking()` |

**Usage in `AppModule`:**

```java
@Module
public class AppModule {

    // JWKS from classpath (e.g., for testing or embedded keys)
    @Provides @Singleton
    static JWTAuth jwtAuth(Vertx vertx) {
        return JwtAuthFactory.fromJwks(vertx, "classpath:jwks.json");
    }
}
```

```java
@Module
public class AppModule {

    // JWKS from a filesystem path read from config
    @Provides @Singleton
    static JWTAuth jwtAuth(Vertx vertx, @VertxConfig JsonObject config) {
        return JwtAuthFactory.fromJwks(vertx, config.getString("auth.jwksPath"));
    }
}
```

```java
@Module
public class AppModule {

    // Symmetric key (e.g., shared secret for internal services)
    @Provides @Singleton
    static JWTAuth jwtAuth(Vertx vertx, @VertxConfig JsonObject config) {
        return JwtAuthFactory.fromSymmetricKey(vertx, "HS256", config.getString("auth.secret"));
    }
}
```

**Remote JWKS (async, avoids blocking the event loop):**

For remote JWKS endpoints, use `fromJwksAsync` to avoid blocking the event loop during verticle startup. Under `VertiqueApplication`, `config()` is already the resolved tree; only the async JWKS fetch needs composing:

```java
@Override
public void start(Promise<Void> startPromise) {
    // config() is pre-resolved by the launcher — no ConfigBootstrap.load needed
    String jwksUri = config().getString("auth.jwksUri");
    JwtAuthFactory.fromJwksAsync(vertx, jwksUri)
        .compose(jwtAuth -> {
            AppComponent c = DaggerAppComponent.builder()
                .vertxModule(new VertxModule(vertx, config()))
                .appModule(new AppModule(jwtAuth))  // pass JWTAuth
                .build();
            c.jacksonConfigurer().configure();
            return c.verticleDeploymentManager().deployAll();
        })
        .onSuccess(v -> startPromise.complete())
        .onFailure(startPromise::fail);
}
```

> **Note:** `fromJwks()` (synchronous) is safe for `classpath:` and filesystem locations. Only use `fromJwksAsync()` or `fromJwksRefreshing()` when the location is an `http://` or `https://` URI.

**Self-refreshing JWKS (key rotation):**

For identity providers that rotate keys (Auth0, Entra ID, Keycloak), use `fromJwksRefreshing()` to periodically re-fetch the JWKS:

```java
@Override
public void start(Promise<Void> startPromise) {
    String jwksUri = config().getString("auth.jwksUri");
    long minutes = config().getLong("auth.jwks.refreshMinutes", 60L);
    JwtAuthFactory.fromJwksRefreshing(vertx, jwksUri, Duration.ofMinutes(minutes))
        .compose(jwtAuth -> {
            AppComponent c = DaggerAppComponent.builder()
                .vertxModule(new VertxModule(vertx, config()))
                .appModule(new AppModule(jwtAuth))
                .build();
            c.jacksonConfigurer().configure();
            return c.verticleDeploymentManager().deployAll();
        })
        .onSuccess(v -> startPromise.complete())
        .onFailure(startPromise::fail);
}
```

The returned `JWTAuth` is a `RefreshableJwtAuth` that wraps a volatile delegate. On each refresh tick it fetches the JWKS, creates a new `JWTAuth`, and swaps the delegate atomically. Failed refreshes preserve the existing keys. Call `RefreshableJwtAuth.close()` on shutdown to cancel the timer (Vert.x also auto-cancels timers when the owning verticle is undeployed).

**Exceptions:**
- `IllegalArgumentException` — if location is null/blank, the document is not valid JSON, or the `keys` array is absent or empty
- `UncheckedIOException` — if reading or fetching the document fails

---

### RefreshableJwtAuth

`JWTAuth` implementation that wraps a volatile delegate and periodically re-fetches a JWKS document. Transparent to the rest of the framework — handlers and providers see a standard `JWTAuth`.

**Key features:**
- Atomic delegate swap via `volatile` — no locking, no request blocking
- `AtomicBoolean refreshInProgress` — overlapping refresh ticks are skipped
- `AtomicBoolean closed` — in-flight refreshes after `close()` do not swap
- Vert.x timer auto-cancels on verticle undeploy; explicit `close()` also available

Created via `RefreshableJwtAuth.create(vertx, location, interval)` or `JwtAuthFactory.fromJwksRefreshing(vertx, location, interval)`.

---

### JwtBearerSecuritySchemeHandler

`SecuritySchemeHandler` and `Handler<RoutingContext>` implementation that handles JWT bearer token authentication for a named OpenAPI security scheme. Rather than delegating to Vert.x's `JWTAuthHandler` directly (which casts to an internal interface), it parses the `Authorization` header, calls `JWTAuth#authenticate` directly, and on success appends `AuthenticationEvidence` with a `JwksVerificationSource`.

```java
public class JwtBearerSecuritySchemeHandler implements SecuritySchemeHandler, Handler<RoutingContext> {

    public JwtBearerSecuritySchemeHandler(
            String schemeName,
            JWTAuth jwtAuth,
            JwtValidationConfig validationConfig,
            CredentialRejectionReporter rejectionReporter) { ... }

    @Override public String schemeName() { return schemeName; }

    @Override
    public void configure(SecuritySchemeRegistry registry) {
        registry.authenticationHandler(new DelegatingJwtAuthHandler(JWTAuthHandler.create(jwtAuth)));
    }
}
```

The `schemeName` defaults to `"bearerAuth"` when `JwtAuthConfig.schemeName` is not overridden. It must match the `@SecurityScheme` name declared on the application's OpenAPI config class.

`SecuritySchemeRegistry` is the neutral registration surface; calling `registry.authenticationHandler(...)` registers the `AuthenticationHandler` for the scheme. The framework applies it as an OR-composed `ChainAuthHandler` on operations whose `@SecurityRequirement` references this scheme.

**Failure reason codes** (reported via `CredentialRejectionReporter`):

| Code | Condition |
|------|-----------|
| `BEARER_MISSING` | No `Authorization` header |
| `BEARER_MALFORMED` | Header present but not a valid Bearer token |
| `JWT_EXPIRED` | Token `exp` claim exceeded |
| `JWT_SIGNATURE_INVALID` | Signature verification failed |
| `JWT_AUDIENCE_INVALID` | `aud` claim mismatch |
| `JWT_ISSUER_INVALID` | `iss` claim mismatch |
| `JWT_ALG_UNSUPPORTED` | Algorithm not permitted |
| `JWT_INVALID` | Any other validation failure |

**OpenAPI security configuration (in the application):**

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

---

### JwtClaimAuthorizationProvider

Vert.x `AuthorizationProvider` that extracts roles and scopes from JWT claims and populates the user's Vert.x authorization cache (provider ID: `"jwt-claims"`). Contributed to the `Set<AuthorizationProvider>` multibinding by `JwtAuthModule`.

**Claim extraction behavior:**

| Claim | Authorization type | Convention | Supported formats |
|-------|--------------------|-----------|-------------------|
| `roles` | `RoleBasedAuthorization` | Common IdP convention | JSON array or space-delimited string |
| `scope` | `PermissionBasedAuthorization` | RFC 8693 (OAuth 2.0) | JSON array or space-delimited string |
| `scp` | `PermissionBasedAuthorization` | Azure AD | JSON array or space-delimited string |
| `permissions` | `PermissionBasedAuthorization` | Auth0 | JSON array or space-delimited string |

All claims handle both `["value1", "value2"]` (JSON array) and `"value1 value2"` (space-delimited string) formats. Non-string array elements and blank values are silently skipped. Claim parsing is delegated to `JwtClaimExtractor` (in `rest-security`) for consistent behaviour with the identity resolution pipeline. All extracted authorizations are stored under provider ID `"jwt-claims"`.

The stored authorizations feed directly into `@RolesAllowed` and `@Authorized(scopes = ...)` enforcement by `AuthorizationContributor` (from `rest-security`).

---

## Extension Points

### RouteAuthHandler (multibinding contribution)

`JwtAuthModule` contributes a `RouteAuthHandler` (via `@IntoSet`) that creates a `JwtBearerSecuritySchemeHandler` for the configured scheme name. This feeds the `Set<RouteAuthHandler>` multibinding declared by `AuthModule`, allowing other modules to contribute additional auth handlers for non-JWT schemes without depending on `JwtAuthModule`.

### Scheme name + validation override (via JwtAuthConfig)

`JwtAuthModule` declares a `@BindsOptionalOf` for `JwtAuthConfig`. Applications provide their own `@Provides JwtAuthConfig` binding to override both the scheme name and/or the validation config in a single typed object. The `@JwtEffective`-qualified provider prefers the app binding when present and falls back to the config-parsed default.

```java
@Provides
static JwtAuthConfig jwtAuthConfig() {
    return new JwtAuthConfig(
        "myCustomScheme",
        JwtValidationConfig.builder()
            .issuer("https://auth.example.com/")
            .build());
}
```

The scheme name must match the name used in `@SecurityScheme` and `@SecurityRequirement` annotations.

---

## Application Setup

Full example with `JwtAuthModule`:

1. Include `JwtAuthModule.class` in the Dagger `@Component` (replaces `AuthModule` + `SecurityModule`)
2. Provide a `JWTAuth` binding in `AppModule`
3. Add `@SecurityScheme(name = "bearerAuth", ...)` and `@SecurityRequirement` to the OpenAPI config class
4. Annotate resource methods with `@RolesAllowed`, `@PermitAll`, `@DenyAll`, or `@Authorized`

**Example resource:**

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

## Configuration Reference

JWT configuration is parsed from the `jwt` section of the application config into a typed `JwtAuthConfig` record at the `JwtAuthModule` boundary via the injected `ConfigParser`. All fields are optional — when absent, defaults are applied.

| Key | Type | Default | Description |
|-----|------|---------|-------------|
| `jwt.schemeName` | String | `"bearerAuth"` | OpenAPI security scheme name the handler registers under |
| `jwt.validation.issuer` | String | — | Expected token issuer (`iss` claim) |
| `jwt.validation.audience` | List\<String\> | — | Expected audiences (`aud` claim) |
| `jwt.validation.clockSkewSeconds` | int | 30 | Clock skew tolerance for `exp`/`nbf` validation (seconds) |

Example:

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

An application-supplied `@Provides JwtAuthConfig` binding takes priority over this config section entirely.

---

## Dependencies

- `dev.vertique:rest-security`
- `io.vertx:vertx-auth-jwt`
- `com.google.dagger:dagger`
- `org.projectlombok:lombok` (provided scope)

---

## Related ADRs

- ADR-0104: Typed Config Architecture — establishes the boundary-parse model; `JwtAuthConfig` is the typed-config object that collapses the former two `@Named` seams into one override channel.
- ADR-0105: Auth Capability and JWT Config Seam — establishes `JwtAuthConfig` as the single typed-config seam for JWT authentication; records the removal of `@Named("jwt.schemeName")` and `@BindsOptionalOf JwtValidationConfig` and their replacement with `@BindsOptionalOf JwtAuthConfig` + `@JwtEffective`.
