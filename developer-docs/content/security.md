---
title: Security
description: Understand what the REST starter secures by default, add JWT bearer authentication, and enforce role- and scope-based authorization on your resources.
---

<!--
SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
SPDX-License-Identifier: EUPL-1.2
-->

# Security

By the end of this page you will understand what `vertique-starter-rest` secures by default and
why, add JWT bearer authentication with `vertique-rest-auth-jwt`, and enforce role- and
scope-based authorization on a resource method.

## What the REST starter gives you by default

`vertique-starter-rest`'s `RestApplicationModule` (see [Application model](application-model.md))
already includes `AuthModule` and `SecurityModule` from `vertique-rest-security`. Those two modules
wire the security *runtime* and its enforcement seams — identity resolution, the authorization
handler, and the JAX-RS `SecurityContext` bridge — not a concrete way to verify who a caller is. No
JWT (or any other) mechanism artifact is on the starter's dependency ledger, and it contributes no
`SecuritySchemeHandler` of its own.

That mechanism-neutral composition has one direct, observable consequence for the `HelloResource`
generated in [Quickstart](quickstart.md): its `hello` method carries no `@RolesAllowed`,
`@Authorized`, or OpenAPI security requirement, so the framework installs no auth handler on it —
the request is public. This is exactly what the generated project's own README says in its
Security section: the `/hello` endpoint ships without authentication, and anyone who can reach the
port can call it.

Reaching for a restrictive annotation without also adding a mechanism module does not silently
fall back to that same public behavior. It fails application startup instead, because no
`SecuritySchemeHandler` exists to satisfy the declared requirement — see the "Common Mistakes"
section of [`vertique-starter-rest`'s module reference](../../vertique-starter/vertique-starter-rest/src/main/resources/META-INF/vertique/module.md)
for this fail-closed rule. An endpoint is either explicitly public, explicitly guarded by a working
mechanism, or the application refuses to start; it is never silently left open by a misconfiguration.

## Add an authentication mechanism

Add the mechanism module you want as an explicit dependency and name it in your own component.
`vertique-rest-auth-jwt` is the framework's JWT bearer implementation, and the public
[`vertique-example-hello`](../../examples/vertique-example-hello/pom.xml) example depends on it
directly — with no explicit version, the same way the generated project depends on
`vertique-starter-rest` without one, because both parent to a Vertique parent POM that manages the
version through the BOM:

```xml
<dependency>
  <groupId>dev.vertique</groupId>
  <artifactId>vertique-rest-auth-jwt</artifactId>
</dependency>
```

`JwtAuthModule` composes `AuthModule` and `SecurityModule` itself, so naming it in your component
reaches the same security runtime `RestApplicationModule` already wires, plus the JWT-specific
bindings on top. `vertique-example-hello`'s own
[`AppComponent`](../../examples/vertique-example-hello/src/main/java/dev/vertique/examples/hello/AppComponent.java)
names it alongside its JAX-RS, validation, and management modules (trimmed to the modules this page
covers):

```java
@VertiqueApp
@Singleton
@Component(
        modules = {
            VertxModule.class,
            RestModule.class,
            RestValidationModule.class,
            JwtAuthModule.class,
            ManagementModule.class,
            AppModule.class,
            ResourceModule.class,
            GeneratedJaxRsResourcesModule.class
        })
interface AppComponent extends VertiqueApplicationComponent {}
```

`JwtAuthModule` still needs a `JWTAuth` binding from your own application module.
`vertique-example-hello`'s
[`AppModule`](../../examples/vertique-example-hello/src/main/java/dev/vertique/examples/hello/AppModule.java)
builds one from a hardcoded symmetric HMAC key, for demonstration purposes only:

```java
@Provides
@Singleton
static JWTAuth jwtAuth(Vertx vertx) {
    return JwtAuthFactory.fromSymmetricKey(
        vertx, "HS256", "super-secret-key-for-example-app-minimum-256-bits-long!!");
}
```

A production application should not hardcode a symmetric key this way. Point `JwtAuthFactory` at
your identity provider's JWKS document instead: `fromJwks(vertx, location)` for a `classpath:` or
filesystem location, `fromJwksAsync(vertx, location)` to fetch an `http://`/`https://` location
without blocking the event loop during startup, or `fromJwksRefreshing(vertx, location, interval)`
when the provider rotates its signing keys, so token validation keeps working through a rotation
without restarting the application. See the
[`vertique-rest-auth-jwt` module reference](../../vertique-rest/vertique-rest-auth-jwt/src/main/resources/META-INF/vertique/module.md)
for the full method reference and the async startup sequence.

Declare the scheme your OpenAPI configuration exposes, matching
[`vertique-example-hello`'s `OpenApiConfig`](../../examples/vertique-example-hello/src/main/java/dev/vertique/examples/hello/resource/OpenApiConfig.java):

```java
@SecurityScheme(name = "bearerAuth", type = SecuritySchemeType.HTTP, scheme = "bearer", bearerFormat = "JWT")
public class OpenApiConfig {}
```

`bearerAuth` also matches `JwtAuthConfig`'s default scheme name. Constrain which tokens are
accepted with the `jwt` section of your application config:

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

Every field is optional and defaults when omitted — `clockSkewSeconds` defaults to `30` even when
the whole `validation` object is left out. See the module reference's configuration table for every
key, and its "Extension Points" section for overriding the whole `JwtAuthConfig` from a
`@Provides` binding instead of the config file.

## Enforce authorization on a resource method

Once a mechanism is wired, guard a resource method with `@RolesAllowed`, `@Authorized`, or
`@DenyAll` and read the authenticated caller from an injected `SecurityContext`.
`vertique-example-hello`'s
[`HelloResource`](../../examples/vertique-example-hello/src/main/java/dev/vertique/examples/hello/resource/HelloResource.java)
demonstrates the pattern this page reconciles against:

```java
@GET
@Path("/secured")
@RolesAllowed("user")
public Future<GreetingResponse> securedGreeting(SecurityContext sc) {
    String userId = sc.identity().actor().id();
    return Future.succeededFuture(new GreetingResponse("Hello, " + userId + "! (secured)"));
}

@GET
@Path("/scoped")
@RolesAllowed("user")
@Authorized(scopes = "write")
public Future<GreetingResponse> scopedGreeting(SecurityContext sc) {
    String userId = sc.identity().actor().id();
    return Future.succeededFuture(new GreetingResponse("Scoped access: Hello, " + userId + "!"));
}
```

`sc.identity().actor().id()` reads the authenticated caller's id from the typed `SecurityContext`;
`sc.authorization().valuesOf(AuthorityKind.SCOPE)` reads its granted scopes the same way.
Combining `@RolesAllowed` and `@Authorized` on one method, as `scopedGreeting` does above, requires
both to pass — a caller with the `user` role but not the `write` scope is rejected. The same
example's integration test proves the resulting behavior precisely: a request with no
`Authorization` header gets `401`; a valid token missing the required role or scope gets `403`; a
token carrying both gets `200`. A method with no security annotation and no OpenAPI security
requirement (like `hello` in the plain, unsecured `HelloResource`) stays public even once a
mechanism module is installed — adding a mechanism does not retroactively lock down every existing
route. `@DenyAll` always returns `403`, even for an otherwise-valid, fully-authorized token — see
[`vertique-rest-security`'s module reference](../../vertique-rest/vertique-rest-security/src/main/resources/META-INF/vertique/module.md)
for the complete authorization annotation table and the OR-of-AND-with-scopes model behind it.

## Customize claim mapping and authorization decisions

The default claim mapper reads `roles`, `scope`/`scp`, and `permissions` claims from the token,
each accepting either a JSON array or a space-delimited string. Override how claims map to roles
and scopes for your identity provider's own conventions by providing a custom
`SecurityClaimMapper` binding, and override how a role/scope requirement is evaluated — for example
against a remote policy decision point — by providing a custom `AuthorizationPolicy` or
`AuthorizationDecisionPoint` binding. Both extension points, their exact contracts, and worked
examples are covered in the
[`vertique-rest-security` module reference](../../vertique-rest/vertique-rest-security/src/main/resources/META-INF/vertique/module.md).

The typed `SecurityContext`, `SecurityIdentity`, and authorization model these seams operate on are
defined in
[`vertique-security-core`](../../vertique-security/vertique-security-core/src/main/resources/META-INF/vertique/module.md),
independent of REST — the same model is available to a headless services application (see
[Services](services.md)) that authenticates callers without a JAX-RS layer.

## Safe defaults and hardening pointers

`RequestOriginConfig`, which controls how a captured client IP, forwarded scheme, and forwarded
host are trusted, defaults to no trusted proxy at all: an empty CIDR set. Behind a load balancer or
reverse proxy, override this binding in your own Dagger module so the framework only trusts
forwarded headers from peers you actually control — see the module reference above for the exact
binding shape and every field it accepts.

Never log or return raw token material, raw API keys, or raw request bodies from a custom
`CredentialRejectionReporter` or claim mapper; the framework's own rejection reporting passes only
stable, redacted reason codes and safe attributes to its emitted events, and a custom
implementation should follow the same discipline.

## Learn more

- [`vertique-rest-security` module reference](../../vertique-rest/vertique-rest-security/src/main/resources/META-INF/vertique/module.md)
  — identity resolution, the authorization model, and every extension point on this page.
- [`vertique-rest-auth-jwt` module reference](../../vertique-rest/vertique-rest-auth-jwt/src/main/resources/META-INF/vertique/module.md)
  — `JwtAuthModule`, `JwtAuthFactory`, and the full `jwt` configuration reference.
- [`vertique-security-core` module reference](../../vertique-security/vertique-security-core/src/main/resources/META-INF/vertique/module.md)
  — the typed `SecurityContext`/`SecurityIdentity`/authorization model, independent of REST.
- [`vertique-starter-rest` module reference](../../vertique-starter/vertique-starter-rest/src/main/resources/META-INF/vertique/module.md)
  — the mechanism-neutral aggregate and its fail-closed startup behavior.
- [`vertique-example-hello`](../../examples/vertique-example-hello/src/main/java/dev/vertique/examples/hello/resource/HelloResource.java)
  — the full worked resource this page reconciles against, including its integration test proving
  the `401`/`403`/`200` outcomes above.
- [Quickstart](quickstart.md)
- [Application model](application-model.md)
- [REST APIs](rest-apis.md)
- [Services](services.md)
- [Documentation overview](index.md)
