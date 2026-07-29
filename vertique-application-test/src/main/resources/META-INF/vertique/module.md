<!--
SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
SPDX-License-Identifier: EUPL-1.2
-->

# Vertique Application Test

> **Status:** Alpha
> **Package:** `dev.vertique.application.test`
> **Artifact:** `vertique-application-test`
> **Depends on:** application, core, deploy

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
