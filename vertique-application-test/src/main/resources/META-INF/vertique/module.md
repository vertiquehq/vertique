<!--
SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
SPDX-License-Identifier: EUPL-1.2
-->

# Vertique Application Test

> **Status:** Stable
> **Package:** `dev.vertique.application.test`
> **Artifact:** `vertique-application-test`
> **Depends on:** `dev.vertique:vertique-application`, `dev.vertique:vertique-core`,
> `dev.vertique:vertique-deplo---

## When To Use It

Add this artifact at test scope whenever an integration test needs a fully started Vertique
application: the Vert.x instance, the Dagger component, the deployed verticles, and the resolved
configuration, all torn down when the class finishes. Use it instead of hand-writing the
asynchronous bootstrap and the awaited teardown, which is where these tests usually go wrong.

A unit test that exercises one collaborator does not need it. Reach for it when the thing under test
only exists once the application is up — a route, a service handler, a startup step's effect.

---

## Core Concepts

**One application per test class.** The extension boots in `beforeAll` and shuts down in `afterAll`,
so every test in the class shares one running application. That makes the tests fast and makes
cross-test state the author's responsibility: reset anything a test mutates.

**Configuration is supplied, not discovered.** `withConfig` hands the application the exact
`JsonObject` it will run on, so a test never depends on ambient files or environment.

**Teardown is awaited.** The extension blocks until the application has actually stopped, so a
following class does not race a half-released port or an undeployed verticle. `startTimeout` bounds
the boot side of that contract.

---

## Key Classes

### `VertiqueAppExtension`

The JUnit 5 extension. Registered as a `@RegisterExtension static final` field, configured through a
small fluent surface before the first test runs, and queried during tests for what the running
application resolved.

| Member | Purpose |
|---|---|
| `withConfig(JsonObject)` | The configuration the application boots on |
| `withVertx(Vertx)` | Runs against a caller-supplied Vert.x instead of one the extension owns |
| `startTimeout(Duration)` | Bounds the boot; the default suits an ordinary application |
| `vertx()` | The running Vert.x instance |
| `config()` | The resolved configuration |
| `httpPort()` | The port the application's HTTP server actually bound, for tests that use port `0` |

---

## Testing

`vertique-application-test` provides `VertiqueAppExtension`, a JUnit 5 extension that boots a
Vertique application once per test class and tears it down afterwards. Integration tests use it as a
`@RegisterExtension static final` field so every test in the class runs against a fully started app
without hand-writing the asynchronous bootstrap dance or the easy-to-forget awaited teardown.

**Add to `pom.xml` as a test-scoped dependency:**

```xml
<dependency>
    <groupId>dev.vertique</groupId>
    <artifactId>vertique-application-test</artifactId>
    <scope>test</scope>
</dependency>
```

**Basic usage:**

```java
@RegisterExtension
static final VertiqueAppExtension app = VertiqueAppExtension
        .forFactory(new AppComponentVertiqueComponentFactory())
        .withConfig(new JsonObject().put("http", new JsonObject().put("port", 0)));

@BeforeAll
static void setUp() {
    RestAssured.port = app.httpPort();  // ephemeral port assigned by the OS
}
```

### Builder methods

| Method | Default | Description |
|--------|---------|-------------|
| `forFactory(VertiqueComponentFactory)` | — | Required. The factory that builds the application component. |
| `withConfig(JsonObject)` | empty `JsonObject` | Root configuration passed to `VertiqueRuntime`. |
| `withVertx(Vertx)` | created and owned | Supplies a caller-owned `Vertx`; the extension does not close it on teardown. |
| `startTimeout(Duration)` | 30 seconds | Maximum wait for startup and teardown to settle. |

### Accessors (valid after `beforeAll`)

| Method | Returns |
|--------|---------|
| `vertx()` | The `Vertx` instance the application was started on. |
| `handle()` | The `VertiqueApplicationHandle` — exposes `component()` and `shutdown()`. |
| `component()` | The built Dagger component, cast to the caller's expected type (unchecked). |
| `config()` | The root `JsonObject` (valid at any time, even before `beforeAll`). |
| `httpPort()` | The bound HTTP port, read from the `"vertique"` shared-data map. Throws if no HTTP server started. |

### Invariants & Gotchas

- The extension implements `BeforeAllCallback` + `AfterAllCallback`. It must be a `static final`
  field to take effect at class level; an instance field runs per-method.
- In `afterAll` the extension awaits `VertiqueApplicationHandle.shutdown()` first, then closes the
  `Vertx` it owns. Teardown failures are surfaced (not swallowed), so a broken teardown fails the
  build.
- The start timeout covers both startup (in `beforeAll`) and each teardown future (in `afterAll`).
- Blocking the JUnit thread is safe here: that thread is not a Vert.x event-loop thread, so the
  awaited futures cannot deadlock.
- `httpPort()` reads from `vertx.sharedData().getLocalMap("vertique")` using the key `"http.port"`,
  published by `HttpVerticle` after a successful bind. It throws `IllegalStateException` when no
  entry is present — i.e., when the application started no HTTP server.

---
