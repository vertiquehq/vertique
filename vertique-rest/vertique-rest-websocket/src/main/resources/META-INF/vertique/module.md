<!--
SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
SPDX-License-Identifier: EUPL-1.2
-->

# REST WebSocket Module

> **Status:** Implemented
> **Package:** `dev.vertique.rest.websocket`
> **Artifact:** `rest-websocket`
> **Depends on:** rest-core, rest-security, core

Annotation-driven WebSocket endpoint module. Provides a dedicated `RouterMount`-based WebSocket runtime separate from JAX-RS. Endpoints use `@WebSocketEndpoint` with lifecycle hooks and typed Jackson message handling.

---

## Overview

`rest-websocket` provides:

- `@WebSocketEndpoint` — class-level annotation declaring a WebSocket path and optional auth scheme
- `@OnOpen`, `@OnMessage`, `@OnClose`, `@OnError` — lifecycle method annotations
- `WebSocketSession` — session interface for interacting with the connected client
- `WebSocketMount` — `RouterMount` implementation that registers all scanned endpoints; default priority -100 (mounts before the JAX-RS router)
- `WebSocketEndpointScanner` — startup annotation scanner with structural validation
- `WebSocketEndpointRegistrar` — route registration wired with security integration
- `WebSocketMessageCodec` — Jackson serialization/deserialization using the shared `ObjectMapper`
- `WebSocketPathMatcher` — path template to regex compiler for `{param}` extraction
- `WebSocketConfig` — configuration value object (basePath, maxFrameSize, maxMessageSize)
- `WebSocketModule` — Dagger module including `RestCoreModule`

Security uses the same annotations as JAX-RS (`@Authorized`, `@RolesAllowed`, `@PermitAll`, `@DenyAll`). Authorization is enforced at connection time via `SecurityPolicyEnforcer` + `RouteAuthHandler`.

---

## Key Classes

### @WebSocketEndpoint

Class-level annotation that marks a class as a WebSocket endpoint.

```java
public @interface WebSocketEndpoint {
    /** Path template, e.g. "/ws/chat/{roomId}" */
    String value();

    /** Optional security scheme name (matches an OpenAPI security scheme). */
    String authScheme() default "";
}
```

Use alongside JAX-RS security annotations (`@Authorized`, `@RolesAllowed`, etc.) on the class to declare connection-level authorization policy.

### @OnOpen, @OnMessage, @OnClose, @OnError

Lifecycle annotations for WebSocket handler methods.

| Annotation | Trigger | Allowed parameters |
|------------|---------|-------------------|
| `@OnOpen` | Connection established | `WebSocketSession`, `@PathParam String`, `@QueryParam String` |
| `@OnMessage` | Frame received | `WebSocketSession`, message DTO (deserialized by `WebSocketMessageCodec`) |
| `@OnClose` | Connection closed | `WebSocketSession` |
| `@OnError` | Error during session | `WebSocketSession`, `Throwable` |

All lifecycle methods may return either `void` or `Future<Void>`. An async `@OnOpen` failure
triggers the bootstrap-failure path (registrar tears down the partial scope, logs, closes the
socket with `1011`); an async `@OnClose` failure is logged at WARN and dispatched to `@OnError`
(when declared and not the failing method itself) before the session scope is closed.

### WebSocketSession

Session interface providing access to the connected client and associated context. Sketch
below; see `dev.vertique.rest.websocket.WebSocketSession`
for the canonical signatures and javadoc.

```java
public interface WebSocketSession {
    /** Unique session ID (UUID). */
    String id();

    /** Underlying Vert.x ServerWebSocket. */
    ServerWebSocket raw();

    /** Request path from the upgrade request. */
    String path();

    /** Path parameters extracted from the endpoint path template. */
    Map<String, String> pathParams();

    /** Query parameters from the upgrade request URL (Vert.x MultiMap). */
    MultiMap queryParams();

    /** HTTP headers from the upgrade request. */
    MultiMap headers();

    /**
     * Mutable session attributes for application use.
     * SecurityContext is NOT stored here; read it via SecurityRuntime.current() inside
     * any lifecycle callback — the substrate holder is bound for the session's lifetime.
     */
    Map<String, Object> attributes();

    /** Send an object as a JSON text frame (serialized by WebSocketMessageCodec). */
    Future<Void> send(Object message);

    /** Send a raw text frame. */
    Future<Void> sendText(String text);

    /** Send a raw binary frame. */
    Future<Void> sendBinary(Buffer data);

    /** Close with normal code (1000). */
    Future<Void> close();

    /** Close with the given status code and reason. */
    Future<Void> close(short statusCode, String reason);

    /** Whether the connection is still open. */
    boolean isOpen();
}
```

`WebSocketSession` no longer carries a `securityContext()` accessor. Read the security context
inside any lifecycle callback via `SecurityRuntime.current()` or
`ContextValues.current(SecurityContext.class)`. Both work because the substrate holder is bound
for the entire session lifetime.

The `ContextSnapshot` capture and `ContextHolder.Scope` install that drive that binding are
owned entirely by `WebSocketEndpointRegistrar` and stored on the registrar's package-private
`DefaultWebSocketSession`. They are deliberately not exposed on the public `WebSocketSession`
interface — endpoint code must not be able to close or null the live scope mid-connection.

### WebSocketConfig

Configuration value object read from the application config.

| Field | Type | Default | Description |
|-------|------|---------|-------------|
| `basePath` | `String` | `""` | Prefix mounted before all WebSocket paths |
| `maxFrameSizeBytes` | `int` | `65536` | Maximum frame size in bytes |
| `maxMessageSizeBytes` | `int` | `262144` | Maximum aggregated message size in bytes |

### WebSocketMount

`RouterMount` implementation. Scans endpoint classes via `WebSocketEndpointScanner`, registers routes via `WebSocketEndpointRegistrar`, and applies `WebSocketConfig`. Default priority is `-100`.

```java
public class WebSocketMount implements RouterMount {
    @Override
    public int priority() { return -100; }

    @Override
    public void mount(Router mainRouter, Router subRouter) { ... }
}
```

`WebSocketMount.Factory` is injected by Dagger. Include `WebSocketModule` in your `@Component` and contribute endpoint classes via the `@WebSocketEndpoints` multibinding.

### WebSocketEndpointScanner

Validates endpoint classes at startup:

- Exactly one `@OnMessage` method per endpoint
- `@OnOpen`, `@OnClose`, `@OnError` are optional (at most one each)
- Method parameter types are consistent with their annotation
- Path template syntax is valid
- `@RequiresAction` is supported **at endpoint/class level only** and enforced once at upgrade (ADR-0115). A `@RequiresAction` on any lifecycle method (`@OnOpen`, `@OnMessage`, `@OnClose`, `@OnError`) **fails startup** — per-method enforcement cannot be honored and is rejected rather than silently ignored (fail-closed).
- A class-level `@RequiresAction` must parse as a canonical `ActionRef` and be registered in the `ActionRegistry`; if present but the authorization engine is absent, startup fails (fail-closed).
- A class-level `@RequiresAction` combined with `@PermitAll` or `@DenyAll` fails startup — `@RequiresAction` composes only with `@RolesAllowed`/`@Authorized`.

Throws `RestConfigurationException` on violation (or `IllegalArgumentException` for endpoint-class-level structural violations).

When the authorization engine is absent (no `SecurityAuthzModule`), the scanner is constructed with no `ActionRegistry` — any endpoint declaring `@RequiresAction` fails startup.

The resolved action gate is stored in `WebSocketEndpointMeta.requiredAction()` (`Optional<ActionRef>`), which is `Optional.empty()` when the endpoint declares no action gate.

### WebSocketEndpointRegistrar

Resolves security policy from class-level annotations, calls `SecurityPolicyEnforcer.createHandler()`, and registers the upgrade handler + lifecycle handler chain on the sub-router. Owns the upgrade ordering described below.

### Context Snapshot / Session Handoff

WebSocket sessions share the originating HTTP request's Vert.x duplicated context (Vert.x's
`ServerWebSocketHandshaker` passes `request.context()` into `ServerWebSocketImpl`). This means
the WebSocket session's holder writes land on the same context as the request lifecycle. The
handoff uses `RequestContextLifecycle` and `ContextSnapshot` to bridge this correctly.

**Upgrade ordering (`WebSocketEndpointRegistrar.toWebSocket(...)`):**

1. `ws.pause()` — gate frames before the rebind runs.
2. `ContextValues.snapshot()` — capture all currently-bound holder values (deep-copies `MDCContext`). Assign to `session.contextSnapshot(snapshot)`.
3. `lifecycle.afterClose(...)` — register the rebind task to run **after** the HTTP request's scopes have been closed.
4. `lifecycle.completeNow()` — synchronously runs all `onClose` registrations (LIFO) then `afterClose` tasks (FIFO). This explicit call is required because `Http1xServerResponse.completeHandshake()` writes the 101 response without firing the response end handler.

**Inside the `afterClose` task:**

1. `ContextValues.bindSnapshot(snapshot)` — install the captured snapshot on the now-clean context.
2. `session.contextScope(sessionScope)` — store the returned scope for the session's lifetime.
3. Install frame and close handlers (the user's `@OnMessage`, `@OnClose`, `@OnError`).
4. Invoke the user's `@OnOpen` callback.
5. `ws.resume()` — unblock queued frames.

The connection close handler closes `session.contextScope()` exactly once after the user's
`@OnClose` future settles.

**Bootstrap failure policy.** If anything inside the `afterClose` task throws before `ws.resume()`,
the catch block tears down any partially-bound scope (clearing it from the session), logs the error,
and closes the WebSocket with close code `1011` (internal server error — `CLOSE_CODE_INTERNAL_ERROR`
constant) so the client receives a defined failure instead of a hung paused connection.
`RequestContextLifecycle.closeAll()` runs each `afterClose` task in its own `try/catch`, so a
failure in this task does not block other registered `afterClose` tasks.

**Why this is generic.** `ContextSnapshot` captures all holder entries, not just `SecurityContext`.
Future request-scoped types (locale, tenant, correlation) automatically propagate into WebSocket
sessions through the same snapshot/rebind path — no changes to `WebSocketEndpointRegistrar` or
`WebSocketSession` are required for each new type.

**Parameter injection.** `SecurityContext`-typed parameters inside lifecycle callbacks resolve via
`SecurityRuntime.current()`. Generic typed-value parameters resolve via `ContextValues.current(type)`.
The reflective `isAssignableFrom(SecurityContext.class)` arm that existed in earlier versions is gone.

### WebSocketMessageCodec

Serializes outbound objects and deserializes inbound frames using the shared `ObjectMapper` provided by `JacksonConfigurer`. Honors any registered `ObjectMapperCustomizer` instances.

```java
public class WebSocketMessageCodec {
    public <T> T decode(String json, Class<T> type) throws JsonProcessingException { ... }
    public String encode(Object value) throws JsonProcessingException { ... }
}
```

### WebSocketPathMatcher

Compiles path templates with `{param}` placeholders to named-group regex patterns. Used by `WebSocketEndpointRegistrar` to extract path parameters at upgrade time.

---

## Validation and Input Processing

### Bean Validation on @OnMessage

When `ValidationModule` is included in the Dagger component, typed messages deserialized by `@OnMessage` are automatically validated via `BeanValidator`. Validation runs after deserialization. On failure the `@OnError` handler is invoked with a `BeanValidationException`.

```java
public record CreateRoomMessage(
    @NotBlank @Size(max = 64) String name,
    @Min(1) @Max(100) int maxParticipants
) {}

@WebSocketEndpoint("/ws/rooms")
@Singleton
public class RoomEndpoint {

    @OnMessage
    Future<Void> onMessage(WebSocketSession session, CreateRoomMessage msg) {
        // msg has already passed bean validation
        return session.send(new RoomCreated(msg.name()));
    }

    @OnError
    void onError(WebSocketSession session, Throwable error) {
        if (error instanceof BeanValidationException ex) {
            session.send(new ErrorResponse(ex.violations()));
        }
    }
}
```

Apply `@ValidateWith` on the `@OnMessage` method to specify validation groups:

```java
@OnMessage
@ValidateWith(groups = {Default.class, StrictInput.class})
Future<Void> onMessage(WebSocketSession session, CreateRoomMessage msg) { ... }
```

`BeanValidator` is bound as `@BindsOptionalOf` — if `ValidationModule` is absent the validator is not present and validation is skipped.

### Canonicalization and Sanitization on @OnMessage

When `SanitizationModule` is included in the Dagger component, `InputObjectProcessor` is applied to typed messages before validation. Processing uses a two-phase deserialization approach:

1. Deserialize the JSON frame to an intermediate representation
2. Apply canonicalization and sanitization policies defined by `@Canonicalize`/`@Sanitize` on message fields
3. Materialize the final typed object

Declare processing policy on the `@OnMessage` method. Class-level fallback is **not** used — annotations must be on the method itself.

```java
@OnMessage
@Canonicalize(NfkcCanonicalize.class)
@Sanitize(StripControlCharsSanitize.class)
Future<Void> onMessage(WebSocketSession session, ChatMessage msg) { ... }
```

`InputObjectProcessor` is bound as `@BindsOptionalOf` — if `SanitizationModule` is absent it is not present and input processing is skipped.

### Canonicalization on @PathParam Values

`@PathParam` string values extracted from the WebSocket upgrade URL are also processed through `InputObjectProcessor` when `SanitizationModule` is present. Declare `@Canonicalize`/`@Sanitize` on the lifecycle method (e.g., `@OnOpen`) to control how parameter strings are normalized.

```java
@OnOpen
@Canonicalize(NfkcCanonicalize.class)
void onOpen(WebSocketSession session, @PathParam("roomId") String roomId) {
    // roomId has been NFKC-canonicalized before use
}
```

Policy resolution is method-only — there is no class-level fallback for lifecycle methods.

### Optional Module Dependencies

| Feature | Required module | Binding mechanism |
|---------|----------------|-------------------|
| Bean Validation | `ValidationModule` | `@BindsOptionalOf BeanValidator` |
| Input Processing | `SanitizationModule` | `@BindsOptionalOf InputObjectProcessor` |

---

## Extension Points

### @WebSocketEndpoints multibinding

Contribute endpoint classes to the runtime via Dagger multibinding. The qualifier `@WebSocketEndpoints` (`Set<Object>`) identifies all registered endpoint instances.

```java
@Module
public abstract class MyWebSocketModule {
    @Provides @IntoSet @WebSocketEndpoints
    static Object chatEndpoint(ChatEndpoint endpoint) {
        return endpoint;
    }
}
```

Alternatively, bind the endpoint class in `@Component` and let Dagger inject its dependencies normally.

---

## Dagger Module

### WebSocketModule

```java
@Module(includes = RestCoreModule.class)
public abstract class WebSocketModule {
    @Multibinds @WebSocketEndpoints
    abstract Set<Object> webSocketEndpoints();

    @Provides @Singleton
    static WebSocketMessageCodec webSocketMessageCodec(ObjectMapper objectMapper) { ... }

    @Provides @Singleton
    static WebSocketConfig webSocketConfig(JsonObject config) { ... }

    @Provides @IntoSet
    static RouterMount webSocketMount(WebSocketMount.Factory factory) { ... }
}
```

| Binding | Purpose |
|---------|---------|
| `@Multibinds @WebSocketEndpoints Set<Object>` | Empty default set; apps contribute endpoint instances via `@IntoSet` |
| `WebSocketMessageCodec` | Jackson codec using shared ObjectMapper |
| `WebSocketConfig` | Configuration value object |
| `Set<RouterMount>` | Contributes `WebSocketMount` at priority -100 |
| `@BindsOptionalOf Authorizer` | Present when `SecurityAuthzModule` is in the component; threaded into `SecurityPolicyEnforcer` so a class-level `@RequiresAction` is enforced at upgrade; coalesces with `AuthModule`'s declaration when both are present |
| `@BindsOptionalOf ActionRegistry` | Present when `SecurityAuthzModule` is in the component; used by `WebSocketEndpointScanner` to validate a class-level `@RequiresAction` at startup; absent means any `@RequiresAction` fails startup |

---

## Application Setup

1. Include `WebSocketModule.class` and (if auth is needed) `AuthModule.class` + `SecurityModule.class` in the Dagger `@Component`
2. Implement endpoint classes with `@WebSocketEndpoint` and lifecycle annotations
3. Contribute endpoints via `@Provides @IntoSet @WebSocketEndpoints`

**Example component:**

```java
@Component(modules = {VertxModule.class, RestModule.class, AuthModule.class,
                      SecurityModule.class, WebSocketModule.class,
                      AppModule.class, ResourceModule.class})
interface AppComponent {
    HttpVerticle httpVerticle();
}
```

**Example endpoint:**

```java
@WebSocketEndpoint("/ws/chat/{roomId}")
@Authorized(scopes = {"chat:connect"})
@Singleton
public class ChatEndpoint {

    private final ChatService chatService;

    @Inject
    public ChatEndpoint(ChatService chatService) {
        this.chatService = chatService;
    }

    @OnOpen
    void onOpen(WebSocketSession session, @PathParam("roomId") String roomId) {
        chatService.join(roomId, session.id());
    }

    @OnMessage
    Future<Void> onMessage(WebSocketSession session, ChatMessage message) {
        return session.send(new ChatAck("ok"));
    }

    @OnClose
    void onClose(WebSocketSession session) {
        chatService.leave(session.id());
    }

    @OnError
    void onError(WebSocketSession session, Throwable error) {
        log.error("WebSocket error for session {}", session.id(), error);
    }
}
```

**Endpoint contribution:**

```java
@Module
public abstract class AppModule {
    @Provides @IntoSet @WebSocketEndpoints
    static Object chatEndpoint(ChatEndpoint endpoint) {
        return endpoint;
    }
}
```

See `examples/vertique-example-websocket` for a working chat-room example demonstrating path-param routing, `@RolesAllowed` connection-level JWT auth, and room broadcast via `ChatRoomRegistry`.

---

## Channel Identity Management

When `AuthModule` is present in the Dagger component, `WebSocketMount.Factory` constructs a
`WebSocketChannelAdapter` inline and wires it into `WebSocketEndpointRegistrar`. The adapter bridges
the WebSocket lifecycle to `ChannelIdentityManager`:

### WebSocketChannelBinding

Package-private `ChannelBinding` implementation wrapping a single open `ServerWebSocket`. Holds two
`ContextHolder.Scope` references: the **combined snapshot scope** bound at upgrade time (the full
per-channel context — `SecurityContext`, `CorrelationContext`, MDC) and a **`SecurityContext`-only
override** layered on top by identity refresh. Identity refresh must not drop correlation/MDC, so the
snapshot scope stays open for the channel's whole lifetime.

**Key semantics:**

| Operation | Behavior |
|---|---|
| `rebind(newCtx)` | Hops to the channel's owning Vert.x context via `runOnContext`, closes the prior `SecurityContext`-only override (restoring the snapshot's `SecurityContext`), then layers a new override via `SecurityRuntime.bindCurrent(newCtx)`. The snapshot scope is **not** touched, so `CorrelationContext`/MDC survive the refresh. Returns a `Future<Void>` that completes after the rebind takes effect. |
| `close(reasonCode)` | Sends WebSocket close frame (code `1000`) on the channel's event loop. Does NOT release any scope. |
| `releaseResources()` | Idempotent release of the override then the snapshot scope, in LIFO order, using `AtomicReference.getAndSet`. Dispatched on the channel's event loop. |

Each scope is held in its own `AtomicReference` so concurrent close/rebind/expiry-timer races from
external threads are handled without data races.

### WebSocketChannelAdapter

Bridge between `WebSocketEndpointRegistrar` lifecycle hooks and `ChannelIdentityManager`. Not a
Dagger-managed bean — `WebSocketMount.Factory` builds one inline, and only when `AuthModule` is wired
alongside `WebSocketModule` (so both the optional `ChannelIdentityManager` and `SecurityRuntime` are
present).

**On WebSocket open (`WebSocketChannelAdapter.onOpen`):**
1. Creates a `WebSocketChannelBinding` wrapping the connection, handing it the initial
   `ContextHolder.Scope` from the upgrade handshake
2. Calls `ChannelIdentityManager.register(channelId, ctx, binding)` — manager takes over the
   channel lifecycle from this point

**On WebSocket close (`WebSocketChannelAdapter.onClose`):**
1. Called by `WebSocketEndpointRegistrar` **after** `@OnClose` has settled
2. Delegates to `ChannelIdentityManager.deregister(channelId, "CHANNEL_CLOSED_BY_PEER")`
3. The manager emits `ChannelClosedEvent`, cancels any expiry timer, removes the registry entry,
   and calls `binding.releaseResources()` — in that order

**Why `@OnClose` runs before deregister:** This ordering ensures the user's `@OnClose` method
observes an authenticated `SecurityContext` (the scope is still bound). The manager's cleanup, including
scope release, happens after all application close logic has settled. See
ADR-0064.

### Identity Refresh

To refresh a WebSocket channel's identity (e.g., after token rotation):

```java
@Inject
ChannelIdentityManager channelIdentityManager;

public Future<Void> refreshSession(String sessionId, SecurityContext newCtx) {
    return channelIdentityManager.refreshIdentity(sessionId, newCtx);
    // Returns a Future<Void> — compose on it before depending on the new identity
}
```

### Optional Wiring

`WebSocketModule` declares `@BindsOptionalOf ChannelIdentityManager` (the manager is bound only by
`AuthModule`). When `AuthModule` is absent (no-auth deployments), the optional manager is empty,
`WebSocketMount.Factory` builds no `WebSocketChannelAdapter`, and the registrar skips channel
management registration. This is a supported configuration for auth-absent components and unit tests.

---

## Dependencies

- `dev.vertique:rest-core`
- `dev.vertique:rest-security`
- `dev.vertique:core`
- `io.vertx:vertx-core`
- `io.vertx:vertx-web`
- `com.fasterxml.jackson.core:jackson-databind`
- `com.google.dagger:dagger`
- `org.slf4j:slf4j-api`
- `org.projectlombok:lombok` (provided scope)

---

## Related ADRs

- ADR-0064: Typed Security Identity Model — establishes channel lifecycle close ordering (deregister post-`@OnClose`), `ChannelIdentityManager` as single cleanup owner, and `WebSocketChannelBinding` rebind/release semantics.
- ADR-0113: Federated Action and Policy Authorship for Framework Authorization — establishes `@RequiresAction` as the mechanism for declaring WebSocket endpoint action gates, with federated authorship in each endpoint module.
- ADR-0115: WebSocket Action-Authorization Granularity (Class-Level, Upgrade-Time Only) — establishes that WebSocket action authorization is class-level and enforced once at upgrade; per-lifecycle-method `@RequiresAction` fails startup (fail-closed).

