<!--
SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
SPDX-License-Identifier: EUPL-1.2
-->

# Job Delayed Module

> **Status:** Implemented
> **Package:** `dev.vertique.job.delayed`
> **Artifact:** `vertique-job-delayed`
> **Depends on:** job-core, job-postgresql, services, deploy

Persistent delayed job queue with configurable retry and dead-letter behavior. Application code enqueues `DelayedJob` descriptors via `DelayedJobService`; the `DelayedJobPoller` verticle polls the database on a timer, claims executions with `FOR UPDATE SKIP LOCKED`, and dispatches each via fire-and-report over the event bus (the reply-address delivery mode documented in `vertique-services`'s module reference). Handler methods are annotated with `@DelayedJobHandlerMethod` and discovered at startup by scanning service implementations. Future-scheduled jobs use `ENQUEUED` state with a future `scheduled_at` value — no separate SCHEDULED state or transition timer is needed because the claim query filters by `scheduled_at <= NOW()`.

Multiple named queues are supported, each with independent concurrency, backoff configuration, and poller instance count.

---

## Package Layout

| Package | Contents |
|---------|----------|
| `dev.vertique.job.delayed` | `DelayedJob`, `DelayedJobConfig`, `DelayedJobService`, `DelayedJobPoller`, `@DelayedJobHandlerMethod`, `DelayedJobHandlerRegistrar`, `BackoffStrategyType`, `DelayedJobExceptionMapper`, `@DelayedJobContract`, `DelayedJobClient<P>`, `DelayedJobExecutor<P, C>`, `DelayedJobOptions`, `DelayedJobClientFactory`, `DelayedJobClientProxy`, `DelayedJobContractContributor`, `DelayedJobTargetResolver`, `DefaultDelayedJobTargetResolver`, `ResolvedDelayedJobTarget` |
| `dev.vertique.job.delayed.exception` | `DelayedJobConfigurationException`, `DelayedJobRegistrationException`, `DelayedJobTechnicalException`, `DelayedJobPersistenceException` |
| `dev.vertique.job.delayed.dagger` | `DelayedJobModule`, `@DelayedJobs` |

---

## Key Classes

### `DelayedJob`

Lombok `@Builder` value object describing a job to enqueue. Only `handler` is required.

| Field | Default | Description |
|-------|---------|-------------|
| `handler` | required | Handler name; maps to a registered `@DelayedJobHandlerMethod` |
| `payload` | `null` | Any Jackson-serializable object; stored as JSONB in DB |
| `runAt` | `null` | Future execution time; `null` or past = immediately claimable |
| `queue` | `"default"` | Logical queue name |
| `priority` | `0` | Higher values claimed first |
| `maxAttempts` | `3` | Maximum attempts before dead-letter; must be in `[1, 1000]` |
| `jobId` | auto-generated | Logical job ID; stable across retries; auto-generated if `null` |
| `metadata` | empty `DurableMetadata` | `DurableMetadata` document carrying durable propagation context; persisted as a `{"context": {namespace: {...}}}` carrier in `job_executions.metadata` JSONB (see ADR 0065) |

The `metadata` field carries durable propagation context as a `DurableMetadata` namespaced document. Application code does not normally populate this field directly — the framework captures and encodes context into the `DurableMetadata` carrier via `DelayedJobService` at enqueue time. The field is available for advanced use cases where a pre-built `DurableMetadata` document must be supplied by the caller.

```java
DelayedJob job = DelayedJob.builder()
    .handler("send-welcome-email")
    .payload(new WelcomeEmailPayload(userId, email))
    .runAt(Instant.now().plusSeconds(60))
    .maxAttempts(5)
    .build();
delayedJobService.enqueue(job);
```

### `DelayedJobService`

Singleton service for enqueueing delayed jobs. Validates the handler name against a pattern (`[a-zA-Z0-9._-]{1,128}`) and verifies it against the registered handler map. `maxAttempts` must be in `[1, 1000]`.

Supports two enqueue modes:

**Standalone (default pool):**

```java
Future<UUID> executionId = delayedJobService.enqueue(job);
```

**Transactional (outbox pattern):**

```java
pool.withTransaction(conn ->
    orderRepository.save(order, conn)
        .compose(v -> delayedJobService.enqueue(welcomeEmailJob, conn))
);
```

The transactional overload calls `PgJobRepository.save(execution, client)` so the INSERT participates in the caller's transaction. The handler address stored on the execution is resolved at enqueue time from `DelayedJobHandlerRegistrar.handlerAddresses()`, ensuring the persisted address matches the actual event bus address.

**Durable context propagation (producer side):** `DelayedJobService.toExecution(job)` calls `DurableContextPropagator.mergeCaptured(job.metadata(), "DELAYED_JOB")` before persisting the execution row. This encodes any currently bound durable-encoder-registered context values into namespaced entries of the `DurableMetadata` document and writes the result — serialised as a `{"context": {namespace: {...}}}` carrier — into `job_executions.metadata`. The caller-supplied `DurableMetadata` (from `job.metadata()`) is the base; framework-captured namespace entries are merged on top under standard collision rules (a collision fails if a context of that encoder's namespace is already present; caller namespace entries pass through unchanged if no such context is currently bound).

**Pre-merged enqueue (`DelayedJobService.enqueuePremerged`):** A `public` overload (two signatures: standalone and transactional) for callers that have already captured and encoded a `DurableMetadata` document — specifically workflow timer create, workflow timer recovery, and the generated `{Contract}_DelayedJobProxy`. Supplying `DelayedJobOptions.premergedMetadata` (a `DurableMetadata`) routes through this path and persists the document as-is with no re-capture. This is not a public-API bypass: re-capture would double-encode context that was already merged at the timer-create site. The method is `public` so generated proxies (which land in the contract's own package, not `dev.vertique.job.delayed`) can call it; the invariant that only framework-assembled `DurableMetadata` documents must be supplied is recorded in its javadoc. See ADR-0071.

**Exception mapping:** All four `enqueue*` methods wrap an escaping `DataAccessException` into `DelayedJobPersistenceException` (extends `DelayedJobTechnicalException` → core `TechnicalException`) via `DelayedJobExceptionMapper`. The mapper applies a `retryable` signal: transient failures (optimistic/pessimistic locking, deadlocks) set `retryable=true`; generic data-access failures set `retryable=false`. This ensures callers of the enqueue API never see a raw `DataAccessException`.

### `@DelayedJobHandlerMethod`

Method-level annotation that marks a service implementation method as the handler for a named delayed job type. Place on the implementation method, not the contract interface.

```java
@DelayedJobHandlerMethod("send-welcome-email")
public Future<Void> sendWelcomeEmail(WelcomeEmailPayload payload, JobContext ctx) {
    ctx.logger().info("Sending welcome email");
    ctx.progress().setTotal(1);
    return emailService.send(payload.email())
        .onSuccess(v -> ctx.progress().incrementSucceeded());
}
```

| Attribute | Description |
|-----------|-------------|
| `value()` | Handler name; must be unique across all registered handlers |

### `DelayedJobHandlerRegistrar`

Scans all registered service implementations via `ServiceContractRegistry` for `@DelayedJobHandlerMethod` annotations at startup. Validates:

- Annotation is on the implementation method, not the contract interface
- Handler name is not blank
- No duplicate handler names
- Annotated method corresponds to a registered service operation

Throws `DelayedJobRegistrationException` (extends `DelayedJobConfigurationException` → core `ConfigurationException`, with all violations collected before throwing) if any check fails.

`handlerAddresses()` returns an immutable `Map<String, String>` from handler name to event bus address, used by `DelayedJobService` for address resolution at enqueue time.

### `DelayedJobPoller`

Vert.x `AbstractVerticle` that polls a single named queue. Multiple instances per queue are safe — `FOR UPDATE SKIP LOCKED` prevents duplicate claims. Each instance runs one poll timer.

**Poll timer:** A recurring one-shot timer (`vertx.setTimer`) that wakes up every `sleepDelayMs` milliseconds, claims up to `maxConcurrentJobs - inFlight` executions, dispatches each, and reschedules.

**Concurrency control:** An `AtomicInteger` semaphore tracks in-flight dispatches. When the in-flight count reaches `maxConcurrentJobs`, the poll cycle skips claiming and reschedules immediately.

**Dispatch sequence for each claimed execution:**
1. Call `DurableContextPropagator.decodeToDispatchContext(execution.metadata(), "DELAYED_JOB")` — the poller runs on the verticle's **non-duplicated** deployment context, so no holder write happens at this site; the decoded namespaced context values ride in the outgoing envelope's `callerOverrides`
2. Build `DispatchEnvelope` with payload, MDC context, `DefaultJobContext`, `JobDispatchContext`, a `DeferredExecutionOrigin` (`kind = "delayed-job"`, `reference` = the execution's handler address — the stable dispatch address, deliberately not the often auto-generated job id — falling back to the kind string when blank) proving the dispatch is deferred execution, and the decoded caller overrides
3. Register a one-time reply consumer on `job.completions.<executionId>`
4. Fire `JobInterceptor.onDispatch()` in OrderedExtension order (phase → priority → orderKey)
5. Send to the handler's event bus address via `EventBusClient.send()`
6. On the consumer's duplicated context, `InboundDispatchScope.install` binds the decoded values (including `DurablePropagationMetadata`) before invoking the handler
7. On reply: fire `JobInterceptor.onComplete()`, delegate to `JobCompletionHandler` for DB update (success/retry/dead-letter)

**Consumer timeout:** When `executionTimeoutMs > 0`, a local Vert.x timer marks the execution `ABANDONED` and releases the concurrency slot if no reply arrives. The coordinator can then re-enqueue the execution.

**Cooperative cancellation:** A consumer on `job.cancel.<executionId>` sets `DefaultJobContext.setCancelled(true)`. Handlers should poll `ctx.isCancelled()` and exit gracefully.

**Progress flush:** When `progressFlushIntervalMs > 0` and a repository is available, a periodic timer writes changed `ProgressSnapshot` values to the repository.

**Graceful shutdown:** On `stop()`, the poll timer is cancelled and all active reply consumers are unregistered. In-flight executions complete independently.

### `DelayedJobConfig`

Per-queue configuration, deserialized from `delayedJob.queues.<name>` in the application config.

| Field | Default | Description |
|-------|---------|-------------|
| `sleepDelayMs` | `5000` | Milliseconds between poll cycles |
| `maxConcurrentJobs` | `5` | Maximum in-flight dispatches per poller instance |
| `backoffStrategy` | `"LINEAR"` | `FIXED`, `LINEAR`, or `EXPONENTIAL` |
| `backoffBaseDelayMs` | `30000` | Base delay in ms for backoff computation |
| `backoffMaxDelayMs` | `3600000` | Maximum delay cap in ms |
| `instances` | `1` | Number of poller verticle instances to deploy for this queue |

Multiple instances multiply throughput: total concurrency for a queue = `instances × maxConcurrentJobs`.

### `BackoffStrategyType`

Maps config string values to `BackoffStrategy` instances via `fromConfig(String)` (case-insensitive).

| Constant | Formula |
|----------|---------|
| `FIXED` | Constant `baseDelay` ms |
| `LINEAR` | `min(baseDelay × (attempt + 1), maxDelay)` |
| `EXPONENTIAL` | `baseDelay × 2^attempt`, capped at `maxDelay` |

---

## Typed Job Contracts

The typed contract pattern separates the enqueue API (client) from the execution logic (server) using a pair of generic interfaces linked by a shared contract annotation. This provides compile-time type safety between the enqueue site and the handler implementation.

### Pattern Overview

```
@DelayedJobContract        ← annotation on the client interface, carries name/maxAttempts/queue/priority
DelayedJobClient<P>        ← client-side interface (enqueue only); proxy generated by DelayedJobClientFactory
DelayedJobExecutor<P, C>   ← server-side interface (execute only); implement with business logic
```

The framework resolves `C` and `P` from the executor's generic type arguments at startup, reads the `@DelayedJobContract` annotation from `C`, and registers the executor as a service contract entry with event bus address `jobs/delayed/{name}/execute`.

### `@DelayedJobContract`

Type-level annotation on a `DelayedJobClient<P>` extension interface. Provides the contract name and default enqueue parameters.

| Attribute | Default | Description |
|-----------|---------|-------------|
| `name` | required | Handler name; must match `[a-zA-Z0-9._-]{1,128}`; used as address key |
| `maxAttempts` | `3` | Max attempts `[1, 1000]`; overridable via config |
| `queue` | `"default"` | Logical queue name; overridable via config |
| `priority` | `0` | Job priority; higher values claimed first; overridable via config |

### `DelayedJobClient<P>`

Client-side interface. Never implement directly — obtain a proxy via `DelayedJobClientFactory`. Extend and annotate with `@DelayedJobContract` to create a named contract interface.

```java
@DelayedJobContract(name = "deliver-webhook", maxAttempts = 2)
public interface DeliverWebhookJob extends DelayedJobClient<WebhookPayload> {}
```

**Enqueue methods:**

| Method | Description |
|--------|-------------|
| `enqueue(P payload)` | Immediate execution with contract defaults |
| `enqueue(P payload, Instant runAt)` | Scheduled for a specific time |
| `enqueue(P payload, Duration delay)` | Delayed by a duration from now |
| `enqueue(P payload, SqlClient tx)` | Transactional enqueue with contract defaults |
| `enqueue(P payload, DelayedJobOptions options)` | Per-enqueue overrides |
| `enqueue(P payload, DelayedJobOptions options, SqlClient tx)` | Transactional with per-enqueue overrides |

### `DelayedJobExecutor<P, C extends DelayedJobClient<P>>`

Server-side interface. Implement with business logic. The type parameter `C` links to the client contract interface for compile-time safety.

```java
@Singleton
class DeliverWebhookJobImpl
        implements DelayedJobExecutor<WebhookPayload, DeliverWebhookJob> {

    @Inject
    DeliverWebhookJobImpl(WebhookClient webhookClient) { ... }

    @Override
    public Future<Void> execute(WebhookPayload payload, JobContext ctx) {
        ctx.logger().info("Delivering webhook for payment {}", payload.paymentId());
        return webhookClient.notify(payload.paymentId());
    }
}
```

The `execute(P payload, JobContext ctx)` method receives the deserialized payload and a `JobContext` for progress tracking, structured logging, and cooperative cancellation. A failed future triggers retry according to `maxAttempts`.

### `DelayedJobOptions`

Lombok `@Builder` value object for per-enqueue overrides. All fields are nullable — `null` means "use contract default".

| Field | Type | Description |
|-------|------|-------------|
| `runAt` | `Instant` | When the job becomes eligible; `null` = immediate |
| `queue` | `String` | Queue override |
| `priority` | `Integer` | Priority override |
| `maxAttempts` | `Integer` | Max attempts override |
| `jobId` | `String` | Stable job ID for idempotency; auto-generated if `null` |
| `premergedMetadata` | `DurableMetadata` | **Internal/advanced.** Pre-captured and pre-encoded `DurableMetadata` document; when non-null, routes through `DelayedJobService.enqueuePremerged` and persists the document as-is without re-capture. Intended for callers that already ran `mergeCaptured` at a separate site (workflow timer create, workflow timer recovery). Application code should not set this field — use `DelayedJob.metadata` for caller-supplied metadata. |

```java
job.enqueue(payload, DelayedJobOptions.builder()
    .runAt(Instant.now().plusHours(1))
    .queue("priority")
    .jobId("webhook-pay_123")
    .build());
```

### `DelayedJobClientFactory`

Singleton factory that creates client proxies for `DelayedJobClient` contract interfaces. Inject it and call `create(Class<T>)`:

```java
@Provides @Singleton
static DeliverWebhookJob deliverWebhookClient(DelayedJobClientFactory factory) {
    return factory.create(DeliverWebhookJob.class);
}
```

`create` validates that the interface is annotated with `@DelayedJobContract` and routes all `enqueue` calls to `DelayedJobService` using contract defaults merged with config overrides and per-call `DelayedJobOptions`.

**Runtime proxy selection:** `create` first tries `Class.forName` for a generated `{Contract}_DelayedJobProxy` (produced by the `vertique-codegen-delayed-job` annotation processor). When found, that zero-reflection static proxy is returned. On `ClassNotFoundException` it falls back to the JDK dynamic `DelayedJobClientProxy`. A present-but-broken generated class (`ReflectiveOperationException` or `LinkageError`) throws `IllegalStateException` — it never silently degrades to the JDK proxy. See ADR-0070 for the selection rationale and `dev.vertique:vertique-codegen-delayed-job` for processor setup.

**Configuration priority (highest first):**
1. Per-enqueue `DelayedJobOptions`
2. Application config under `delayedJob.contracts.{name}.*`
3. `@DelayedJobContract` annotation defaults

### Config Overrides for Contracts

```json
{
  "delayedJob": {
    "contracts": {
      "deliver-webhook": {
        "maxAttempts": 5,
        "queue": "priority",
        "priority": 10
      }
    }
  }
}
```

### Dagger Wiring

Contribute the executor to the `@DelayedJobs` multibinding and bind the client proxy via `DelayedJobClientFactory`:

```java
@Module
public class WebhookModule {

    // Server side — executor registered into the @DelayedJobs multibinding
    @Provides @IntoSet @DelayedJobs
    static Object deliverWebhookExecutor(DeliverWebhookJobImpl impl) {
        return impl;
    }

    // Client side — typed proxy created by the factory
    @Provides @Singleton
    static DeliverWebhookJob deliverWebhookClient(DelayedJobClientFactory factory) {
        return factory.create(DeliverWebhookJob.class);
    }
}
```

The `DelayedJobContractContributor` (registered by `DelayedJobModule`) picks up all objects in the `@DelayedJobs` set at startup, resolves their type arguments, and registers each executor as a service contract entry with address `jobs/delayed/{name}/execute`. The contributor namespace is `"delayed-job"`.

### `DelayedJobTargetResolver`

Interface for resolving delayed-job targets by target ID. The default implementation (`DefaultDelayedJobTargetResolver`) is built at startup from all registered typed contracts and handler registrations.

```java
public interface DelayedJobTargetResolver {
    ResolvedDelayedJobTarget resolve(String targetId);
}
```

### `ResolvedDelayedJobTarget`

Immutable record returned by `DelayedJobTargetResolver.resolve(String)`. Carries the complete, effective execution settings for a delayed-job target.

| Field | Type | Description |
|-------|------|-------------|
| `targetId` | `String` | Stable durable target ID (`@DelayedJobContract.name()` or `@DelayedJobHandlerMethod.value()`) |
| `handlerName` | `String` | Handler name used for dispatch |
| `handlerAddress` | `String` | Current event bus address (`jobs/delayed/{targetId}/execute`) |
| `queue` | `String` | Effective queue (config override → annotation → default) |
| `priority` | `int` | Effective priority (config override → annotation → default) |
| `maxAttempts` | `int` | Effective max attempts (config override → annotation → default) |

Resolution precedence for defaults follows the same order as `DelayedJobClientFactory`:
1. Application config at `delayedJob.contracts.{name}.*`
2. `@DelayedJobContract` annotation
3. Framework defaults

```java
ResolvedDelayedJobTarget target = resolver.resolve("deliver-webhook");
// target.handlerAddress() → "jobs/delayed/deliver-webhook/execute"
// target.queue()          → effective queue from config or annotation
// target.maxAttempts()    → effective max attempts
```

### `@DelayedJobs` Qualifier

Dagger `@Qualifier` for the executor multibinding set. Use `@Provides @IntoSet @DelayedJobs` to contribute executor instances. The `DelayedJobModule` declares the empty `@Multibinds` binding so applications can contribute zero or more executors.

**Generated auto-wiring:**

Applications inheriting `vertique-app-parent` declare `vertique-job-delayed` as a runtime
dependency and receive the complete processor facade automatically. Custom-parent applications
use the BOM plus `vertique-codegen-all` recipe in `docs/packaging.md`.
`vertique-codegen-dagger` owns the generated `@Provides @IntoSet @DelayedJobs Object` bindings for
classes that implement `DelayedJobExecutor<P, C>` and carry a single `@Inject` constructor. Include
`GeneratedDelayedJobsModule.class` in the `@Component`; annotate an executor with `@NoAutoWire` to
keep its manual binding canonical. Because `@NoAutoWire` is a source-retained annotation,
applications using that opt-out also declare `vertique-codegen-core` with `provided` scope as
documented in `docs/packaging.md`.

### Throughput Knobs

| Setting | Where | Effect |
|---------|-------|--------|
| `delayedJob.queues.{name}.instances` | Config | Number of poller verticles per queue; safe with `FOR UPDATE SKIP LOCKED` |
| `delayedJob.queues.{name}.maxConcurrentJobs` | Config | Max in-flight jobs per poller instance |
| `services.contracts.delayed-job.{name}.instances` | Config | Executor service verticle instances (from `ServiceContractEntries.deploymentOptions`) |

Total queue throughput = `poller.instances × poller.maxConcurrentJobs`. Executor instances control parallel dispatch capacity on the service side.

---

## Configuration

```json
{
  "delayedJob": {
    "queues": {
      "default": {
        "sleepDelayMs": 5000,
        "maxConcurrentJobs": 5,
        "backoffStrategy": "LINEAR",
        "backoffBaseDelayMs": 30000,
        "backoffMaxDelayMs": 3600000,
        "instances": 1
      },
      "emails": {
        "sleepDelayMs": 2000,
        "maxConcurrentJobs": 10,
        "backoffStrategy": "EXPONENTIAL",
        "backoffBaseDelayMs": 10000,
        "backoffMaxDelayMs": 600000,
        "instances": 2
      }
    }
  }
}
```

If no queues are configured, a single `"default"` queue is created with default settings.

Execution timeout and progress-flush interval are sourced from `JobCoordinatorConfig` (`job.coordinator.executionTimeoutMs` and `job.coordinator.progressFlushIntervalMs`), ensuring all pollers share the same timeout policy.

---

## Extension Points

### `JobInterceptor`

Register interceptors via Dagger `@IntoSet` multibinding. Interceptors fire around every dispatch on all queues:

```java
@Provides @IntoSet
static JobInterceptor metricsInterceptor(MetricsService metrics) {
    return new MetricsJobInterceptor(metrics);
}
```

See `dev.vertique:vertique-job-core` for the full `JobInterceptor` interface.

---

## Dagger Wiring

`DelayedJobModule` includes `JobModule`, `JobPostgresqlModule`, and `JobCoordinatorModule` automatically.

```java
@Component(modules = {
    VertxModule.class,
    DispatchModule.class,
    DbPostgresqlModule.class,
    DbFlywayModule.class,
    DelayedJobModule.class,
    AppModule.class
})
public interface AppComponent { ... }
```

**What `DelayedJobModule` provides:**

| Binding | Type | Description |
|---------|------|-------------|
| `JobCompletionHandler` | Singleton | Shared completion handler wired with `JobRepository` |
| `DelayedJobService` | Singleton | Enqueue API |
| `DelayedJobHandlerRegistrar` | Singleton | Startup handler scan |
| `Set<VerticleDeployment>` | `@ElementsIntoSet` | One `DelayedJobPoller` per configured queue, deployed in `SERVICES` phase at priority 100 |

**Startup sequence:**

Call `delayedJobHandlerRegistrar.scan()` to validate and register all handlers before the pollers start. The `VerticleDeploymentManager` handles poller deployment automatically.

---

## Related ADRs

- ADR-0070: Delayed-Job Static Proxy Codegen — records the factory-selection mechanism: generated proxy preferred via `Class.forName`, reflective proxy retained as fallback, loud-fail for broken generated class, origin-package pinning, and full-member contract-shape validation.
- ADR-0071: `DelayedJobService.enqueuePremerged` Visibility Widening — records why `enqueuePremerged` was widened from package-private to `public` so generated proxies (living in the contract's package) can call it.
- ADR-0112: Framework exception hierarchy and REST mapping — establishes `DelayedJobConfigurationException` → core `ConfigurationException` and `DelayedJobTechnicalException` → core `TechnicalException` as the delayed-job semantic roots; `DelayedJobRegistrationException` extends `DelayedJobConfigurationException`; `DelayedJobPersistenceException` extends `DelayedJobTechnicalException`; enqueue methods wrap escaping `DataAccessException` via `DelayedJobExceptionMapper` so the public API never leaks persistence-layer types.
- ADR-0165: Deferred-Execution Provenance and Bounded SYSTEM-Minting — establishes why `DelayedJobPoller` binds a `DeferredExecutionOrigin` into the dispatch context, letting the receive-side identity-snapshot reconstruction initializer distinguish proven deferred execution from an ordinary context-empty dispatch.

---

## Dependencies

- **job-core** — `JobRepository` SPI, `JobContext`, `JobDispatchContext`, `JobInterceptor`, `JobCompletionHandler`, `JobCoordinatorConfig`, state machine
- **job-postgresql** — `PgJobRepository` (transactional save overload), `JobPostgresqlModule`
- **services** — `ServiceContractRegistry` (handler discovery), `ServiceMethodInvoker` (handler dispatch with MDC/context injection)
- **deploy** — `VerticleDeployment`, `LifecyclePhase`
- **core** — `BackoffStrategy`, `DispatchEnvelope`, `Result`, event bus codec

> **MDC note.** `DelayedJobPoller` enriches MDC before dispatching each job execution.
> Enrichment that runs on the poller verticle thread (non-duplicated context) uses
> `org.slf4j.MDC` directly because the framework facade (`dev.vertique.logging.MDC`) requires
> a duplicated Vert.x context and would throw otherwise. Enrichment inside event-bus consumer
> handlers (which run on duplicated contexts) uses the framework facade normally.

---

## Version History

| Date | Change |
|------|--------|
| 2026-04-04 | Phase 1: `DelayedJob` builder, `DelayedJobConfig` (per-queue config with `instances` field), `DelayedJobService` (standalone + transactional enqueue, handler name validation, maxAttempts bounds `[1,1000]`), `@DelayedJobHandlerMethod`, `DelayedJobHandlerRegistrar`, `DelayedJobPoller` (AbstractVerticle: poll timer, `AtomicInteger` concurrency guard, fire-and-report dispatch, MDC propagation, `JobInterceptor` chain, consumer timeout, cancel listener, progress flush), `BackoffStrategyType` (FIXED/LINEAR/EXPONENTIAL), `DelayedJobModule`, `DelayedJobRegistrationException`; no SCHEDULED state — future jobs use `ENQUEUED` with future `scheduled_at` |
| 2026-04-05 | Typed contract pattern: `@DelayedJobContract`, `DelayedJobClient<P>` (6 enqueue overloads), `DelayedJobExecutor<P, C>` (server-side execute interface), `DelayedJobOptions` (per-enqueue nullable overrides), `DelayedJobClientFactory` (JDK dynamic proxy via `create(Class<T>)`), `DelayedJobClientProxy` (InvocationHandler), `DelayedJobContractContributor` (implements `ServiceContractContributor` SPI), `@DelayedJobs` Dagger qualifier; event bus address pattern: `job/{name}/execute`; config overrides at `delayedJob.contracts.{name}.*`; `@DelayedJobHandler` renamed to `@DelayedJobHandlerMethod` |
| 2026-04-08 | `DelayedJobTargetResolver` interface + `DefaultDelayedJobTargetResolver` for O(1) stable-target-id lookup; `ResolvedDelayedJobTarget` record (targetId, handlerName, handlerAddress, queue, priority, maxAttempts); canonical address namespace changed to `jobs/delayed/{name}/execute`; contributor type discriminator changed from `"job"` to `"delayed-job"` |
| 2026-04-10 | `DelayedJobPoller` now uses `EventBusClient.send()` for fire-and-forget dispatch instead of calling `vertx.eventBus().send()` directly — consistent `dispatch.envelope` codec wiring (codec was named `dispatch.body` at the time of this entry; renamed when the context-propagation substrate landed) |

---

## Planned Additions

- **Batch enqueue** — `enqueue(List<DelayedJob>)` and `enqueue(List<DelayedJob>, SqlClient)` for bulk inserts
- **Dead-letter management API** — re-enqueue dead-letter jobs with reset attempt counter; configurable retention cleanup
- **Per-handler retry config** — override `backoffStrategy`/`maxAttempts` per handler name via config
