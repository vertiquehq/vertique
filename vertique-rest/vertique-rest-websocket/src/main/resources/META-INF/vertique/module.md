<!--
SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
SPDX-License-Identifier: EUPL-1.2
-->

# REST WebSocket Module

> **Status:** Beta
> **Package:** `dev.vertique.rest.websocket`
> **Artifact:** `vertique-rest-websocket`
> **Depends on:** rest-core, rest-security, core, context, security-core, security-runtime, logging

`vertique-rest-websocket` is the annotation-driven WebSocket transport for Vertique. You write a
plain class annotated with `@WebSocketEndpoint`, add lifecycle methods (`@OnOpen`, `@OnMessage`,
`@OnClose`, `@OnError`), and contribute it to a Dagger multibinding. The module mounts a dedicated
Vert.x sub-router ahead of the JAX-RS router, authenticates and authorizes the HTTP upgrade request
using the same security annotations JAX-RS resources use, and propagates the request's ambient
context — security context, correlation, MDC — into the connection for its whole lifetime.

It is **not** a JAX-RS transport: WebSocket endpoints are not `@Path` resources, they are not
described in the generated OpenAPI document, and there is no per-message authorization. Every
security decision is made once, at upgrade time, before the 101 response is written.

---

## When To Use It

Install this module when the application serves WebSocket connections and wants Vertique's
authentication, typed message handling, and context propagation on them rather than hand-rolling a
`ServerWebSocket` handler.

| You also want | Also install |
|---|---|
| Bearer-token authentication on the upgrade | `dev.vertique:vertique-rest-auth-jwt` (or another module contributing a `RouteAuthHandler`) |
| Role/scope enforcement and a resolved `SecurityContext` | `dev.vertique:vertique-rest-security` (`AuthModule` + `SecurityModule`) |
| `@RequiresAction` action gates on endpoints | `dev.vertique:vertique-security-runtime` (`SecurityAuthzModule`) |
| Bean Validation on typed messages | `dev.vertique:vertique-validation` (`ValidationModule`) |
| Canonicalization/sanitization of inbound values | `dev.vertique:vertique-sanitization` (`SanitizationModule`) |

Without a security module the endpoints still work, unauthenticated. That is a supported
configuration for local development and for genuinely public sockets.

---

## Core Concepts

### The upgrade is the security boundary

A WebSocket endpoint's route runs four handlers in a fixed order before the socket exists:

1. **Authentication** — installed only when the endpoint's policy is `@RolesAllowed`/`@Authorized`
   (or when a class-level `@RequiresAction` is present). It sets the Vert.x user and appends
   authentication evidence.
2. **Identity resolution** — resolves the framework `SecurityContext` from that evidence.
3. **Authorization** — evaluates the role/scope policy AND, when present, the `@RequiresAction`
   action gate. Both must pass.
4. **Upgrade** — only now is `HttpServerRequest.toWebSocket()` called.

A rejected caller therefore never reaches step 4 and never sees a 101 response. The client observes
an ordinary HTTP failure status on the handshake.

When `AuthModule` is installed, steps 2–3 are supplied by the security-owned
`dev.vertique.rest.security.IdentityPipelineFactory` — the single assembly point every transport
(REST, MCP, WebSocket) reads identity resolution and authorization from. The handler this module
installs is assembled for invocation origin `websocket` (`DispatchBoundary.WEBSOCKET`) with
identity-snapshot capture always **off**, regardless of whether the application binds a capture —
a channel upgrade establishes a long-lived identity, not the per-request lifecycle that capture is
defined for. `AuthorizationDecisionEvent`s and audit attributes recorded for a WebSocket upgrade
therefore carry origin kind `websocket`, distinguishing them from REST's `rest`.

**Fail-closed registration when the pipeline is absent.** Without `AuthModule` (no
`IdentityPipelineFactory` bound), registering an endpoint whose security policy is restrictive
(`@DenyAll`, `@RolesAllowed`, `@Authorized`) or that declares a class-level `@RequiresAction` fails
startup with one aggregated `IllegalStateException` naming every offending endpoint class and its
policy — see [Startup failures](#startup-failures). Unannotated endpoints are unaffected and keep
serving unauthenticated.

### There is no per-message authorization

Authorization runs once, at upgrade. `@RequiresAction` is accepted at **class level only**; placing
it on `@OnOpen`, `@OnMessage`, `@OnClose`, or `@OnError` fails startup rather than being silently
ignored. If a connection's privileges must change mid-stream, refresh the channel identity (see
[Identity refresh](#identity-refresh)) — do not expect the framework to re-check a gate per frame.

### Frames are gated until `@OnOpen` completes

The socket is paused the moment the handshake succeeds and is resumed only after your `@OnOpen`
method has completed successfully. A message sent by the peer immediately after connecting is
queued, not dropped, and is delivered after `@OnOpen`. If `@OnOpen` throws or returns a failed
future, the socket is **never** resumed and is closed with code `1011`.

### Context is carried across the handshake

Everything bound to the request's ambient context at upgrade time — the `SecurityContext`,
`CorrelationContext`, MDC keys, and any other typed context value — is captured and re-bound onto
the connection for its lifetime. Read it inside any lifecycle callback:

```java
@OnMessage
void onMessage(WebSocketSession session, SecurityContext ctx, ChatMessage msg) {
    ctx.identity().subject().ifPresent(p -> log.info("message from {}", p));
}
```

`WebSocketSession` deliberately has no `securityContext()` accessor and no way to close the live
scope. Read the context one of three ways:

| Access | Notes |
|---|---|
| A `dev.vertique.security.SecurityContext` lifecycle-method parameter | Simplest; `null` when no security module is installed |
| An injected `dev.vertique.rest.core.security.SecurityRuntime`, then `current()` | `SecurityRuntime` is an interface bound only when `SecurityModule` is installed |
| `dev.vertique.context.ContextValues.current(SecurityContext.class)` | Static; returns `Optional<SecurityContext>` |

All three re-read the ambient value on every invocation, so they observe an identity refresh.

---

## Getting Started

```java
@WebSocketEndpoint("/ws/chat/{roomId}")
@RolesAllowed("chat:connect")
@Singleton
public class ChatEndpoint {

    private final ChatRoomRegistry registry;

    @Inject
    ChatEndpoint(ChatRoomRegistry registry) {
        this.registry = registry;
    }

    @OnOpen
    void onOpen(WebSocketSession session, @PathParam("roomId") String roomId) {
        registry.join(roomId, session);
    }

    @OnMessage
    Future<Void> onMessage(WebSocketSession session, ChatMessage msg) {
        String roomId = session.pathParams().get("roomId");
        return registry.broadcast(roomId, msg);
    }

    @OnClose
    void onClose(WebSocketSession session) {
        registry.leave(session.id());
    }

    @OnError
    void onError(WebSocketSession session, Throwable error) {
        log.error("WebSocket error on session {}", session.id(), error);
    }
}
```

Contribute the endpoint instance:

```java
@Module
public abstract class ChatModule {

    @Provides
    @IntoSet
    @WebSocketEndpoints
    static Object chatEndpoint(ChatEndpoint endpoint) {
        return endpoint;
    }
}
```

Wire the component:

```java
@Singleton
@Component(modules = {VertxModule.class, ConfigParsingModule.class,
                      RestModule.class, JwtAuthModule.class,
                      WebSocketModule.class, ChatModule.class, AppModule.class})
interface AppComponent {
    HttpVerticle httpVerticle();
}
```

`WebSocketModule` lives in `dev.vertique.rest.websocket.dagger` — not the module's base package —
and injects a `ConfigParser`, so `ConfigParsingModule` (from `dev.vertique:vertique-config-core`)
must be in the graph. `JwtAuthModule` supplies the authentication handler and transitively includes
`AuthModule` + `SecurityModule`; drop it for an unauthenticated socket.

When no endpoint is contributed the module registers no mount at all, so including it in a component
that has no WebSocket endpoints costs nothing.

---

## Key Classes

### `@WebSocketEndpoint`

Class-level annotation marking a WebSocket endpoint.

```java
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface WebSocketEndpoint {
    String value();
    String authScheme() default "";
}
```

| Attribute | Default | Meaning |
|---|---|---|
| `value` | _(required)_ | Path template. `{name}` placeholders each match exactly one path segment. |
| `authScheme` | `""` | Selects which registered `RouteAuthHandler` authenticates the upgrade, by `schemeName()`. Empty means auto-select: exactly one handler must be registered, otherwise registration fails. |

The path template is relative to `websocket.basePath`. Combine the annotation with the JAX-RS
security annotations (`@RolesAllowed`, `@PermitAll`, `@DenyAll`, `@Authorized`) and, optionally,
`@RequiresAction` — all at class level.

### Lifecycle annotations

`@OnOpen`, `@OnMessage`, `@OnClose`, and `@OnError` are method-level markers with no attributes. At
most one method per annotation per endpoint; a second one fails startup. All four are optional.

| Annotation | Fires when |
|---|---|
| `@OnOpen` | The handshake succeeded and the session context is bound. Runs before any frame is delivered. |
| `@OnMessage` | A complete text or binary message arrives. |
| `@OnClose` | The connection closed, from either side. |
| `@OnError` | A socket-level error occurs, an inbound message fails to deserialize or validate, or another lifecycle method throws or returns a failed future. |

**Return type** must be `void` or `Future<Void>`; anything else fails startup. Only `@OnOpen` and
`@OnClose` have their futures awaited — `@OnOpen`'s gates `resume()`, `@OnClose`'s gates resource
release. A failed future from `@OnMessage` or `@OnError` is logged and dispatched to `@OnError`.

**Parameters** are resolved by type and annotation, in this order:

| Parameter shape | Bound to |
|---|---|
| `WebSocketSession` (or a supertype) | The current session |
| `Throwable` (or a subtype) | The error — only on `@OnError`; otherwise left `null` |
| `dev.vertique.security.SecurityContext` | `SecurityRuntime.current()` at invocation time; `null` when no security module is installed |
| `@PathParam("name") T` | The extracted path segment, converted to `T` |
| any other parameter | The deserialized message — only on `@OnMessage` |

There is no query-parameter, header, or body-annotation injection. Read the upgrade request's query
string and headers from `session.queryParams()` and `session.headers()`.

`@PathParam` targets support `String`, `int`/`Integer`, and `long`/`Long`. Any other declared type
throws `IllegalArgumentException` when the callback is invoked. A `@PathParam` name that is not a
placeholder in the endpoint's path template fails startup.

### Message typing

The `@OnMessage` message parameter is the first parameter that is not a `WebSocketSession`, a
`Throwable`, a `SecurityContext`, or `@PathParam`-annotated. Its declared type selects the wire
handling:

| Declared type | Handling |
|---|---|
| `String` | Text frames delivered verbatim, no JSON decoding |
| `io.vertx.core.buffer.Buffer` | The endpoint listens for **binary** messages instead of text |
| anything else | Text frames decoded from JSON into that type |

An endpoint listens for either text or binary messages, never both — the `Buffer` parameter is what
switches it. With no message parameter (or no `@OnMessage` at all) the endpoint receives nothing.

JSON encoding and decoding — inbound typed messages and `session.send(Object)` — use the same shared
Jackson mapper as the REST pipeline, so any `ObjectMapperCustomizer` the application registers
applies to WebSocket payloads too.

A message that fails to deserialize or fails Bean Validation is **dropped**: `@OnError` is invoked
with the exception when declared, and `@OnMessage` is not called. The connection stays open.

### `WebSocketSession`

The handle passed to lifecycle methods. Instances are scoped to one connection and are not
thread-safe — touch them only on the connection's event loop.

```java
public interface WebSocketSession {
    String id();                                   // random UUID, stable for the connection
    ServerWebSocket raw();                         // underlying Vert.x socket
    String path();                                 // request path from the upgrade
    Map<String, String> pathParams();              // immutable
    MultiMap queryParams();                        // from the upgrade request URI
    MultiMap headers();                            // from the upgrade request
    Map<String, Object> attributes();              // mutable, application-owned

    Future<Void> send(Object message);             // serialized to a JSON text frame
    Future<Void> sendText(String text);
    Future<Void> sendBinary(Buffer data);

    Future<Void> close();                          // close code 1000
    Future<Void> close(short statusCode, String reason);
    boolean isOpen();
}
```

`id()` is also the channel id used by `ChannelIdentityManager`, so it is what you pass to
`refreshIdentity`.

### `WebSocketConfig`

Read from the `websocket` section of the application config.

| Key | Type | Default | Description |
|---|---|---|---|
| `websocket.basePath` | `String` | `"/*"` | Path at which the WebSocket sub-router is mounted. Must start with `/` and end with `/*`. |

```json
{
  "websocket": {
    "basePath": "/ws/*"
  }
}
```

This module sets **no** frame-size or message-size limits of its own. Those are Vert.x server
options — configure them on the HTTP server, not here.

### `WebSocketMount.Factory`

Injectable factory for building additional mounts programmatically, e.g. to mount a second endpoint
group at a different path or ahead of another `RouterMount`.

```java
public WebSocketMount create(String mountPath, Set<Object> endpoints);
public WebSocketMount create(String mountPath, Set<Object> endpoints, int priority);
```

The default mount created from `@WebSocketEndpoints` uses priority `-100`, which places it ahead of
the JAX-RS mount so WebSocket paths are matched before catch-all REST routes. Contribute a
hand-built mount to the `Set<RouterMount>` multibinding:

```java
@Provides
@IntoSet
static RouterMount adminSocketMount(WebSocketMount.Factory factory, AdminEndpoint endpoint) {
    return factory.create("/admin/ws/*", Set.of(endpoint), -200);
}
```

### Upgrade failure types

A **client** connecting to a Vertique WebSocket endpoint sees the server's refusal through one of
several raw Vert.x/Netty exception shapes depending on timing. Normalize them instead of
pattern-matching third-party types:

```java
public abstract sealed class WebSocketUpgradeException extends TechnicalException
        permits WebSocketUpgradeRejected, WebSocketUpgradeTransportFailure {}

public final class WebSocketUpgradeRejected extends WebSocketUpgradeException {
    public int status();          // e.g. 401, 403, 404, 503
}

public final class WebSocketUpgradeTransportFailure extends WebSocketUpgradeException {}

public final class WebSocketUpgradeExceptions {
    public static WebSocketUpgradeException translate(Throwable err);
}
```

`translate` returns a `WebSocketUpgradeException` unchanged, preserves the structured status of a
Vert.x `UpgradeRejectedException`, otherwise scans the message for a standalone 1xx–5xx token and
returns `WebSocketUpgradeRejected` when one is found. With no recoverable status it returns
`WebSocketUpgradeTransportFailure` — typically "the server closed the socket mid-handshake", which is
observable but says nothing about *why*. Retry before concluding anything from it.

```java
client.connect(options).onFailure(err -> {
    WebSocketUpgradeException upgrade = WebSocketUpgradeExceptions.translate(err);
    if (upgrade instanceof WebSocketUpgradeRejected rejected && rejected.status() == 401) {
        refreshTokenAndReconnect();
    }
});
```

These types live in `dev.vertique.rest.websocket.transport` and describe the **client** side of a
handshake. Nothing on the server path throws them.

---

## Extension Points

### `@WebSocketEndpoints` (multibinding)

The only registration surface. Contribute each endpoint instance as `Object` into the
`@WebSocketEndpoints`-qualified set; Dagger injects the endpoint's own dependencies normally.

```java
@Provides
@IntoSet
@WebSocketEndpoints
static Object chatEndpoint(ChatEndpoint endpoint) {
    return endpoint;
}
```

The qualifier is `dev.vertique.rest.websocket.dagger.WebSocketEndpoints`. Endpoints are scanned and
validated once, at router construction — a structural violation fails startup, not the first
connection.

### `RouteAuthHandler` (consumed, not declared here)

The upgrade's authentication handler comes from the `Set<RouteAuthHandler>` multibinding declared by
`dev.vertique:vertique-rest-security`. `dev.vertique:vertique-rest-auth-jwt` contributes the bearer
handler; contribute your own to authenticate upgrades with a different scheme, then name it with
`@WebSocketEndpoint(authScheme = "...")` when more than one is registered.

---

## JAX-RS Integration

WebSocket endpoints are not JAX-RS resources, but they reuse two annotation families so an
application declares security the same way on both transports:

| Annotation | Placement | Effect at upgrade |
|---|---|---|
| `@PermitAll` | class | No authentication handler, no authorization gate |
| `@DenyAll` | class | Every upgrade is refused with 403 |
| `@RolesAllowed({...})` | class | Authenticate, then require any one of the listed roles |
| `@Authorized(scopes = {...})` | class | Authenticate, then enforce the declared scopes |
| `@RequiresAction("...")` | class | Authenticate, then evaluate the action gate — AND-composed with the above |
| `@PathParam("name")` | lifecycle method parameter | Binds a path-template placeholder |
| `@ValidateWith(groups = {...})` | `@OnMessage` method | Selects Bean Validation groups |

Method-level security annotations on lifecycle methods are not consulted; the endpoint class is the
only policy site.

---

## Validation and Input Processing

Both features are optional bindings. When the providing module is absent the step is skipped
silently — nothing fails.

| Feature | Install | Applies to |
|---|---|---|
| Bean Validation | `ValidationModule` | The deserialized `@OnMessage` payload |
| Canonicalization / sanitization | `SanitizationModule` | The `@OnMessage` payload and `@PathParam` string values |

Canonicalizers and sanitizers see the value's provenance in their `InputValueContext`:
`@OnMessage` values — both a raw `String` payload and the decoded intermediate of a typed
message — report `InputLocation.PAYLOAD`, and `@PathParam` values report `InputLocation.PATH`.
Message values reported `BODY` before `PAYLOAD` existed: a custom processor that branches on
`InputLocation.BODY` must also handle `PAYLOAD` to keep covering messages.

Bean Validation runs after deserialization (and after sanitization when both are installed). A
violation raises `BeanValidationException`, which is routed to `@OnError`; the message is discarded.

```java
public record CreateRoomMessage(
    @NotBlank @Size(max = 64) String name,
    @Min(1) @Max(100) int maxParticipants
) {}

@OnMessage
@ValidateWith(groups = {Default.class, StrictInput.class})
@Canonicalize(NfkcCanonicalize.class)
@Sanitize(StripControlCharsSanitize.class)
Future<Void> onMessage(WebSocketSession session, CreateRoomMessage msg) {
    return session.send(new RoomCreated(msg.name()));
}

@OnError
void onError(WebSocketSession session, Throwable error) {
    if (error instanceof BeanValidationException ex) {
        session.send(new ErrorResponse(ex.violations()));
    }
}
```

**Policy resolution is method-only.** `@Canonicalize`/`@Sanitize` on the endpoint *class* are
ignored; put them on the lifecycle method whose input they govern. `@PathParam` values are processed
with the policies declared on the method that receives them, so `@OnOpen` and `@OnMessage` can
normalize the same path parameter differently.

**Composed policy annotations are honored.** A custom annotation meta-annotated with
`@Canonicalize`/`@Sanitize` — the usual way to name a reusable chain — declares that chain on a
lifecycle method exactly as the bare annotation does, matching REST. The startup gate resolves
composed annotations the same way, so what fails the build and what runs on the message path always
agree.

```java
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Sanitize(StripAllHtmlSanitizer.class)
public @interface SafeText {}

@OnMessage
@SafeText                      // identical to @Sanitize(StripAllHtmlSanitizer.class)
void onMessage(WebSocketSession session, String text) { }
```

**Renamed message fields are covered.** A field-level policy is declared on a Java property, while an
incoming message is keyed by whatever Jackson publishes — `@JsonProperty("user_name")`, a naming
strategy, or a `@JsonAlias`. Messages are bound through Vert.x's shared `DatabindCodec.mapper()`, and
that mapper's own property introspection is what maps each wire key back onto the Java property whose
policies apply (`JacksonFieldNameResolver`, from `dev.vertique:vertique-json`, implementing the
`dev.vertique.core.sanitization.InputFieldNameResolver` contract). A `@Sanitize` on a
renamed field therefore runs exactly as it would on an unrenamed one, with no extra declaration.

```java
public record ProfileMessage(
    @JsonProperty("display_name") @Sanitize(StripAllHtmlSanitizer.class) String displayName
) {}
// {"display_name": "<b>ada</b>"} -> displayName == "ada"
```

Each declared message type's projection is composed at endpoint registration, not on the message path.
**The engine decides which types get composed**, not the registrar: registration hands each declared
message type to `InputObjectProcessor.precomputeFieldNameResolution`, which prepares the resolver for
every owner type its own descent may consult for that message. The postcondition is that no statically
knowable owner is left to introspect on the event loop — an array message type reduces to its
component and a scalar such as the default `String` simply has nothing beneath it, both by the engine's
own classification. The `vertique-input-processing` reference documents the owner set and its bounds in
full.

Two startup failures follow (see [Startup failures](#startup-failures)), both of which previously
surfaced per message: a type in that set whose names cannot be projected, and a reachable type whose
policy annotations conflict — preparing the owner set resolves that type's policy metadata.

**Five shapes a declared policy still does not reach.** A `Map`-typed field, an `Object`-typed field,
a concrete `@JsonTypeInfo` subtype's own fields, `@JsonUnwrapped` members, and a key matched only by
`ACCEPT_CASE_INSENSITIVE_PROPERTIES` all leave the field with its inherited method- and type-level
chains and nothing else. Nothing fails and nothing is logged, so a stranded policy on one of these is
invisible until the message that mattered gets through. The `vertique-input-processing` reference
documents each shape, what still applies, and how to stay inside the covered set.

---

## Identity Refresh

When `AuthModule` is installed, each connection is registered as a channel with
`dev.vertique.security.channel.ChannelIdentityManager` (from `dev.vertique:vertique-security-core`).
Use it to swap a live connection's identity after, for example, a token rotation:

```java
@Singleton
public class SessionRefresher {

    private final ChannelIdentityManager channels;

    @Inject
    SessionRefresher(ChannelIdentityManager channels) {
        this.channels = channels;
    }

    public Future<Void> refresh(WebSocketSession session, SecurityContext newContext) {
        return channels.refreshIdentity(session.id(), newContext);
    }
}
```

Compose on the returned future — it completes only once the new identity is in effect. The refresh
replaces the `SecurityContext` only; correlation and MDC bound at upgrade survive it.

The Vert.x authorization import (opt-in via `VertxAuthorizationImportModule` from
`dev.vertique:vertique-rest-security`) runs during identity resolution at upgrade time only. A
refresh rebinds the supplied `SecurityContext` as-is — it does not re-invoke identity resolvers or
contributed `AuthorizationProvider`s. Resolve any refreshed authorities into the new context before
calling `refreshIdentity`.

**`@OnClose` runs before the channel is deregistered**, so your close handler still observes the
authenticated `SecurityContext` rather than an anonymous one. Cleanup — the channel-closed event,
expiry-timer cancellation, and scope release — happens after your close logic has settled.

Without `AuthModule` there is no `ChannelIdentityManager` binding and no channel registration; the
connection's context scope is released directly on close.

---

## Module Dagger Bindings

`WebSocketModule` (`dev.vertique.rest.websocket.dagger`) includes `RestCoreModule` and
`SecurityEventsModule`. It declares exactly two `@Multibinds` and six `@BindsOptionalOf`
declarations; identity resolution, authorization, claim mapping, and Vert.x authorization import are
no longer separately declared here — they live entirely behind the single
`IdentityPipelineFactory` optional binding, owned by `dev.vertique:vertique-rest-security`'s
`AuthModule` (issue #256; a second module declaring those collaborators would re-create the duplicate
assembly that change closed).

| Binding | Purpose |
|---|---|
| `@Multibinds @WebSocketEndpoints Set<Object>` | Empty default; applications contribute endpoints |
| `@Multibinds Set<RouteAuthHandler>` | Empty default so the graph resolves with no auth module present; `AuthModule` (or `dev.vertique:vertique-rest-auth-jwt`) contributes into the same set |
| `WebSocketConfig` (`@Singleton`) | Parsed from the `websocket` config section |
| `@ElementsIntoSet Set<RouterMount>` | Contributes one `WebSocketMount` at priority `-100`, or nothing when no endpoint is contributed |

Optional bindings (`@BindsOptionalOf`), each absent unless the named module is in the component. All
coalesce with the same declaration in `AuthModule` when both are present.

| Optional binding | Supplied by | Absent means |
|---|---|---|
| `IdentityPipelineFactory` (`dev.vertique.rest.security.IdentityPipelineFactory`) | `AuthModule` | No authentication, identity resolution, or authorization on any endpoint; a restrictive/`@RequiresAction` endpoint fails registration closed instead of registering unauthenticated (see [The upgrade is the security boundary](#the-upgrade-is-the-security-boundary)) |
| `ChannelIdentityManager` | `AuthModule` | No channel registration; no identity refresh |
| `Authorizer` | `SecurityAuthzModule` | Any endpoint declaring `@RequiresAction` fails startup |
| `ActionRegistry` | `SecurityAuthzModule` | Same |
| `BeanValidator` | `ValidationModule` | Messages are not validated |
| `InputObjectProcessor` (`dev.vertique.input.processing.InputObjectProcessor`) | `SanitizationModule` | Messages and path parameters are not sanitized — and any endpoint that *declares* a policy fails startup rather than accepting messages unprocessed |

---

## Failures, Constraints, and Common Mistakes

### Startup failures

All of these are raised while the router is built, so a misconfigured endpoint never serves traffic.

| Condition | Thrown |
|---|---|
| Endpoint instance not annotated `@WebSocketEndpoint` | `IllegalArgumentException` |
| Two methods carry the same lifecycle annotation | `IllegalArgumentException` |
| A lifecycle method returns something other than `void` or `Future` | `IllegalArgumentException` |
| `@PathParam("x")` names no placeholder in the path template | `IllegalArgumentException` |
| Conflicting security annotations on the class | `IllegalArgumentException` |
| `@RolesAllowed` with an empty value list | `IllegalArgumentException` |
| `@RequiresAction` on a lifecycle method | `IllegalArgumentException` |
| `@RequiresAction` that is not a canonical action reference | `IllegalArgumentException` |
| `@RequiresAction` combined with `@PermitAll` or `@DenyAll` | `IllegalArgumentException` |
| `@RequiresAction` with no `ActionRegistry` installed | `IllegalArgumentException` |
| `@RequiresAction` naming an action absent from the `ActionRegistry` | `IllegalArgumentException` |
| `@RequiresAction` with no authorization enforcement pipeline installed | `IllegalStateException` |
| `@RequiresAction` with an `ActionRegistry` but no `Authorizer` | `IllegalStateException` |
| One or more endpoints declare a restrictive policy (`@DenyAll`, `@RolesAllowed`, `@Authorized`) or class-level `@RequiresAction`, but no `IdentityPipelineFactory` is bound (no `AuthModule`) — checked for every contributed endpoint before any authentication handler is installed; the one exception aggregates every violating endpoint class and its policy | `IllegalStateException` |
| Endpoint needs authentication but no `RouteAuthHandler` is registered | `IllegalStateException` |
| `authScheme` names no registered `RouteAuthHandler` | `IllegalStateException` |
| Several `RouteAuthHandler`s registered and no `authScheme` given | `IllegalStateException` |
| A wire-name projection in a message type's owner set cannot be composed — two properties claiming one wire name, or two claiming one `@JsonAlias` (checked only when an `InputObjectProcessor` is bound) | `ConfigurationException` |
| A type in a message type's owner set declares conflicting policy annotations (checked only when an `InputObjectProcessor` is bound) | `IllegalStateException` |
| A lifecycle method declares a canonicalizer or sanitizer chain — directly or through a composed annotation — or the message type declares field-level policies, while no `InputObjectProcessor` is bound | `ConfigurationException` |

Every `@RequiresAction` failure mode above is deliberately fail-closed: an action gate that cannot
be enforced refuses to boot rather than serving traffic with the gate silently missing. The
restrictive-policy-without-pipeline row is the same discipline applied to role/scope policies: a
`@DenyAll` endpoint with no `AuthModule` installed would otherwise register open, and a graph that
binds a `RouteAuthHandler` without the pipeline would authenticate but never authorize.

### Connection-time outcomes

| Outcome | What the client sees |
|---|---|
| Authentication or authorization denied | The security layer's HTTP status (401 or 403) on the handshake; no 101 |
| `toWebSocket()` failed | HTTP 400 |
| Session bootstrap threw, channel registration failed, or `@OnOpen` failed | Handshake succeeds, then an immediate close with code `1011` and no frames |
| Peer closed while channel registration was still in flight | `@OnOpen` is skipped entirely and the connection is torn down |
| Normal `session.close()` | Close code `1000` |

### Common mistakes

- **Expecting per-message authorization.** There is none. A `@RequiresAction` on a lifecycle method
  fails startup for exactly this reason — it would suggest a guarantee the transport cannot make.
- **Storing the `SecurityContext` in `session.attributes()` at `@OnOpen`.** It goes stale across an
  identity refresh. Read it per invocation instead.
- **Declaring `@Canonicalize`/`@Sanitize` on the endpoint class.** Class-level policy is not
  consulted for lifecycle methods.
- **Assuming a failed `@OnMessage` closes the connection.** It does not; the failure is routed to
  `@OnError` and the socket stays open. Close it yourself if that is the intent.
- **Sending from `@OnOpen` and expecting inbound frames first.** Outbound writes work immediately;
  inbound delivery starts only after `@OnOpen`'s future completes.
- **Using a `@PathParam` type other than `String`, `int`/`Integer`, or `long`/`Long`.** This passes
  startup validation and fails per-invocation.
- **Adding a Vert.x `AuthorizationProvider` without installing `VertxAuthorizationImportModule`.**
  A contributed provider is inert on its own — role and scope decisions are evaluated from the
  framework's `SecurityContext` claims. When the application includes the opt-in
  `VertxAuthorizationImportModule` (from `dev.vertique:vertique-rest-security`), the security-owned
  `IdentityPipelineFactory` threads the importer into the handler this module installs, so
  contributed providers change authorization outcomes at upgrade time. `WebSocketModule` itself no
  longer declares the provider/importer bindings — they live entirely behind `AuthModule`'s
  `IdentityPipelineFactory`.

---

## Dependencies

| Dependency | Why |
|---|---|
| `dev.vertique:vertique-rest-core` | `RouterMount`, request-lifecycle handle, `SecurityRuntime`, `RouteAuthHandler` |
| `dev.vertique:vertique-input-processing` | the neutral `InputObjectProcessor` / `EffectiveInputPolicies` contracts message and path-parameter processing are typed against |
| `dev.vertique:vertique-json` | `JacksonFieldNameResolver` — the wire-name projection that lets a declared policy reach a renamed message field |
| `dev.vertique:vertique-rest-security` | Policy enforcement, identity resolution, claim mapping |
| `dev.vertique:vertique-core` | Context holder, config parsing, Bean Validation and sanitization contracts, including the `InputFieldNameResolver` projection contract message processing is typed against |
| `dev.vertique:vertique-context` | `ContextSnapshot`/`ContextValues` used to carry request context across the handshake |
| `dev.vertique:vertique-security-core` | `SecurityContext`, `ChannelIdentityManager`, action-gate types |
| `dev.vertique:vertique-security-runtime` | Security event emission for the connection lifecycle |
| `dev.vertique:vertique-logging` | Logging conventions |
| `io.vertx:vertx-web` | Router, routing context, `ServerWebSocket` |
| `io.vertx:vertx-auth-common` | Vert.x authorization provider types on the security seam |
| `jakarta.ws.rs:jakarta.ws.rs-api` | `@PathParam` and the JAX-RS security annotations |
| `com.fasterxml.jackson.core:jackson-databind` | Typed message serialization |
| `com.google.dagger:dagger` | Module and multibinding declarations |
| `org.projectlombok:lombok` | Compile-time only |
