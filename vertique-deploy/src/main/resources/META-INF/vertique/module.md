<!--
SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
SPDX-License-Identifier: EUPL-1.2
-->

# Deploy Module

> **Status:** Implemented
> **Package:** `dev.vertique.deploy`
> **Artifact:** `deploy`
> **Depends on:** core

Verticle lifecycle management with phase-ordered startup/shutdown, multi-instance support, and supervised restart. Verticles are grouped into `LifecyclePhase` buckets (`BOOTSTRAP` → `INFRA` → `SERVICES` → `EDGE`) for coarse ordering, with `priority` for fine-grained ordering within each phase. A separate `VerticleSupervisor` handles crash detection, exponential-backoff restart, and availability signalling.

---

## Key Classes

### `VerticleDeployment`

Immutable record describing a single verticle deployment.

```java
public record VerticleDeployment(
    String name,                           // human-readable name for logging and ID lookup
    Supplier<? extends Verticle> supplier, // called once per instance
    DeploymentOptions options,             // instances, threading model, config, etc.
    LifecyclePhase phase,                  // coarse startup/shutdown ordering
    int priority                           // fine-grained ordering within phase (lower = first)
) {}
```

The `phase` must be one of the four verticle-subset phases: `BOOTSTRAP`, `INFRA`, `SERVICES`, or
`EDGE`. A non-verticle phase (e.g. `CONFIGURE`, `VALIDATE`, `MIGRATE`, `AFTER_START`) is rejected
at construction with an `IllegalArgumentException`. See `LifecyclePhase.isVerticlePhase()`.

Factory methods for common cases:

```java
// Default options, priority 0
VerticleDeployment.of("my-verticle", MyVerticle::new, LifecyclePhase.EDGE);

// Default options, explicit priority
VerticleDeployment.of("my-verticle", MyVerticle::new, LifecyclePhase.EDGE, 10);
```

For multi-instance deployment, set `options.setInstances(n)` and use a `Provider<T>` as the supplier so each instance gets fresh dependencies from Dagger.

#### Invariants & Gotchas

- `phase` must satisfy `LifecyclePhase.isVerticlePhase()`. The compact constructor enforces this at construction time — before the object can be contributed to a multibinding — so misconfiguration surfaces at Dagger component construction (app startup), not at first deployment attempt.
- `null` options are normalized to `new DeploymentOptions()` by the compact constructor.

### `VerticleDeployer`

Low-level deploy/undeploy engine that tracks deployed verticles by name. Provided as a Dagger `@Singleton` via its `@Inject` constructor — no explicit `@Provides` is needed.

```java
@Inject
public VerticleDeployer(Vertx vertx) { ... }
```

**Deployment methods:**

| Method | Description |
|--------|-------------|
| `deploy(VerticleDeployment)` | Deploys a single verticle; fails if name is already tracked |
| `undeploy(String name)` | Undeploys a tracked verticle by name; tracking retained on failure |
| `undeployAll()` | Undeploys all tracked verticles in reverse startup order (EDGE → SERVICES → INFRA → BOOTSTRAP, high priority first); in-flight deployments are skipped |
| `deploymentId(String name)` | Returns the Vert.x deployment ID, or `null` if not deployed or deployment in flight |
| `evict(String name, String expectedId)` | CAS-removes a stale tracking entry when the verticle is known dead; used by `VerticleSupervisor` for stale-tracking recovery |

Within the same priority group, all verticles deploy in parallel with `Future.join` (wait-all) semantics.

### `VerticleDeploymentManager`

Phase orchestrator for multibound `VerticleDeployment` sets. Provided as a Dagger `@Singleton` via its `@Inject` constructor — no explicit `@Provides` is needed.

```java
@Inject
public VerticleDeploymentManager(VerticleDeployer deployer, Set<VerticleDeployment> deployments) { ... }
```

**Batch deployment methods:**

| Method | Description |
|--------|-------------|
| `deployAll()` | Deploys all multibinding-registered verticles, phase by phase, priority group by priority group; uses invocation-scoped rollback (only undeploys what the current call started) |
| `deployPhase(LifecyclePhase)` | Deploys only the verticles registered for the given phase; transactional per phase — rolls back that phase only on failure |

The `deployPhase()` API is useful when services must deploy between infrastructure and edge phases:

```java
// When mixing phase-based and manager-based deployment
manager.deployPhase(LifecyclePhase.INFRA)
    .compose(v -> serviceDeploymentManager.deployAll())
    .compose(v -> manager.deployPhase(LifecyclePhase.EDGE))
    .onSuccess(v -> startPromise.complete())
    .onFailure(startPromise::fail);
```

`deployAll()` handles the simple case where all verticles are registered via multibinding and no interleaved deployment logic is needed.

### `VerticleSupervisor`

Supervises deployed verticles by name with one-for-one restart and bounded exponential backoff. Provided as a Dagger `@Singleton` via its `@Inject` constructor.

```java
@Inject
public VerticleSupervisor(Vertx vertx, VerticleDeployer deployer) { ... }
```

`VerticleSupervisor` depends on `VerticleDeployer` (not `VerticleDeploymentManager`) because supervision operates on individual verticles, not on phase batches.

**Supervision lifecycle:**

```java
// After successful deployment — start supervising
supervisor.supervise(name, deploymentId, config, redeployAction);

// Before intentional undeploy — suppress restart
supervisor.unsupervise(name);
```

**Availability query:**

```java
supervisor.isAvailable(name);       // true if running, false while restarting or budget exhausted
supervisor.availability();          // snapshot map of name → availability for all supervised verticles
```

**Error reporting (called by the verticle or its failure handler):**

```java
supervisor.reportFatalError(name, error);          // schedules restart; de-duplicated per name
supervisor.reportRedeployFailure(name, cause);     // clears in-progress flag, schedules retry
```

**Restart behavior:**

- Concurrent `reportFatalError` calls for the same name are de-duplicated via an internal `restartInProgress` flag
- Each restart calls `deployer.undeploy(name)` before the redeploy action to avoid orphaned event bus consumers
- If undeploy fails because the verticle is already dead (stale deployer tracking), the supervisor automatically calls `deployer.evict(name, expectedId)` via CAS so the name can be reused
- The verticle is marked unavailable while restarting, and back to available on successful redeploy
- If re-registration after a restart is detected (same name), mutable state is reset but the restart timestamp history is preserved so the sliding window budget remains accurate

**Re-registration on restart:** `supervise()` accepts re-registration of an already-supervised name. Mutable state (`available`, `restartInProgress`, `consecutiveRestarts`) is reset; restart timestamps are preserved.

### `SupervisionConfig`

Top-level record controlling restart budget and backoff behavior.

```java
public record SupervisionConfig(int maxRestarts, long withinMs, long initialBackoffMs, long maxBackoffMs) {}
```

| Field | Default | Description |
|-------|---------|-------------|
| `maxRestarts` | `5` | Maximum restarts allowed within the sliding time window |
| `withinMs` | `60000` | Sliding window duration in milliseconds |
| `initialBackoffMs` | `1000` | Initial delay before the first restart attempt |
| `maxBackoffMs` | `30000` | Maximum delay cap for exponential backoff |

```java
SupervisionConfig.DEFAULT  // maxRestarts=5, withinMs=60_000, initialBackoffMs=1_000, maxBackoffMs=30_000
```

### `DeployerModule`

Dagger `@Module` that declares the empty-by-default multibinding sets for verticle deployments and
non-verticle lifecycle steps. Include this in any component that uses `VerticleDeploymentManager`.

```java
@Singleton
@Component(modules = {
    VertxModule.class,
    DeployerModule.class,  // declares VerticleDeployment, ApplicationStartupStep, ApplicationShutdownStep multibindings
    AppModule.class        // contributes entries via @Provides @IntoSet
})
interface AppComponent {
    VerticleDeploymentManager verticleDeploymentManager();
    VerticleDeployer verticleDeployer();  // also available for direct use (e.g., ServiceDeploymentManager)
}
```

`VerticleDeployer`, `VerticleDeploymentManager`, and `VerticleSupervisor` are all `@Inject`-constructored — no explicit `@Provides` is needed in `DeployerModule`.

---

## Extension Points

### `Set<VerticleDeployment>` — Verticle Registration

Contribute verticle deployments to the multibinding. `VerticleDeploymentManager` picks them up at startup.

**Ordering note:** `VerticleDeployment` is deliberately **not** an `OrderedExtension`. It is a value record with its own `LifecyclePhase phase()` component (must be a verticle-subset phase; validated at construction) and is ordered via `groupingBy(priority, TreeMap)` where same-priority verticles within a phase deploy **in parallel** — it has no total order by design. Imposing `OrderedExtension` would change those semantics and collide on `phase()`.

```java
// In an app module
@Provides @IntoSet
static VerticleDeployment managementVerticle(Provider<ManagementVerticle> provider) {
    return VerticleDeployment.of("management", provider::get, LifecyclePhase.INFRA);
}

// Multi-instance with explicit priority
@Provides @IntoSet
static VerticleDeployment workerVerticle(Provider<WorkerVerticle> provider) {
    DeploymentOptions opts = new DeploymentOptions()
            .setInstances(4)
            .setThreadingModel(ThreadingModel.WORKER);
    return new VerticleDeployment("worker", provider::get, opts, LifecyclePhase.SERVICES, 10);
}
```

**Note:** `VerticleDeploymentManager.deployPhase(SERVICES)` only deploys verticles registered via this multibinding at the SERVICES phase. Verticles deployed directly via `VerticleDeployer.deploy()` (e.g., by `ServiceDeploymentManager`) are NOT included.

### `Set<ApplicationStartupStep>` — Non-Verticle Startup Steps

Contribute non-verticle startup work (e.g. configuring Jackson, running Flyway) to the multibinding declared by `DeployerModule`. Steps are ordered by `LifecycleOrdered.comparator()` (phase → priority → orderKey). The Phase 2 lifecycle runner (`vertique-application`) will consume this set.

```java
@Provides @IntoSet
static ApplicationStartupStep jacksonConfigureStep(JacksonConfigurer configurer) {
    return new ApplicationStartupStep() {
        @Override public LifecyclePhase phase() { return LifecyclePhase.CONFIGURE; }
        @Override public Future<Void> start() {
            configurer.configure();
            return Future.succeededFuture();
        }
    };
}
```

### `Set<ApplicationShutdownStep>` — Non-Verticle Shutdown Steps

Mirror of the startup-step multibinding for teardown work. Same ordering contract via `LifecycleOrdered.comparator()`. Consumed by the Phase 2 lifecycle runner.

---

## Dependencies

- `dev.vertique:vertique-core` — `LifecyclePhase`, `LifecycleOrdered`, `ApplicationStartupStep`, `ApplicationShutdownStep`
- `io.vertx:vertx-core`
- `com.google.dagger:dagger`
- `jakarta.inject:jakarta.inject-api`
- `jakarta.annotation:jakarta.annotation-api`
- `org.slf4j:slf4j-api`
- `org.projectlombok:lombok` (provided)
