<!--
SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
SPDX-License-Identifier: EUPL-1.2
-->

# Job Delayed Module

> **Status:** Implemented
> **Package:** `dev.vertique.job.delayed`
> **Artifact:** `vertique-job-delayed`
> **Depends on:** job-core, job-postgresql, services, deploy, core, context, db-core, logging, config-core

`vertique-job-delayed` is a durable one-shot job queue. An application enqueues a job — now, at an
instant, or after a delay — and the framework persists it, claims it on some node, dispatches it to a
handler method, and applies retry, backoff and dead-letter policy to the outcome. Multiple named
queues are supported, each with its own concurrency, backoff and poller-instance count.

Two authoring styles coexist. The **typed contract** style pairs a `@DelayedJobContract` client
interface with a `DelayedJobExecutor` implementation and gives compile-time type safety between the
enqueue site and the handler. The **annotation** style marks an existing service implementation
method with `@DelayedJobHandlerMethod` and enqueues against it by name through `DelayedJobService`.

This is not a cron replacement. Use `dev.vertique:vertique-job-cron` for recurring schedules; use
this module when the work fires once, at a known time, and must survive a restart.

---

## When To Use It

Install it when work must run **later** and **exactly once**, and losing it on a restart is
unacceptable — send a reminder in an hour, retry a webhook with backoff, expire a reservation at a
deadline. Enqueue inside your own transaction when the job must not exist unless the business write
commits. Reach for `dev.vertique:vertique-job-cron` instead for recurring wall-clock schedules, and
for a plain `dev.vertique:vertique-services` call when you need sub-second latency — this queue is
poll-based. PostgreSQL is required: the module pulls in `dev.vertique:vertique-job-postgresql`, and
the `JobRepository` binding is not optional.

---

## Core Concepts

**There is no scheduled state.** A future-dated job is persisted as `ENQUEUED` with a future
`scheduledAt`; claim queries filter by `scheduled_at <= NOW()`, so the job becomes claimable exactly
when its time arrives. Nothing transitions it and nothing wakes up early.

**One row, many attempts.** A delayed job reuses a single execution row: `executionId` is stable and
`attemptNumber` increments. `maxAttempts` is inclusive, so an attempt remains while
`attemptNumber + 1 < maxAttempts`. When the last attempt fails the row moves to `DEAD_LETTER` and
stays there — nothing re-drives it.

**Retry versus interruption.** A handler that returns a **failed** future consumes an attempt: if
attempts remain, the failure and the re-enqueue are recorded in one transaction at
`now + backoff.delay(nextAttempt)`; otherwise the row is dead-lettered. An execution that never
replies within `job.coordinator.executionTimeoutMs` is an **interruption**, not a handler failure —
it is atomically marked `ABANDONED` and re-enqueued when attempts remain, or dead-lettered directly
when they are exhausted. Either way exactly one state transition is recorded.

Backoff is configured **per queue**, not per job:

| Strategy | Delay for attempt *n* (zero-based) |
|---|---|
| `FIXED` | `backoffBaseDelayMs` |
| `LINEAR` *(default)* | `min(backoffBaseDelayMs × (n + 1), backoffMaxDelayMs)` |
| `EXPONENTIAL` | `backoffBaseDelayMs × 2ⁿ`, capped at `backoffMaxDelayMs` |

**Ordering and concurrency.** Claiming is exclusive across nodes and poller instances, so deploying
more instances is always safe, and higher `priority` is claimed first. There is **no** per-job
ordering guarantee: two jobs enqueued in order may run concurrently or out of order.

**What a handler receives.** Handlers run as ordinary service operations, so `JobContext` and
`JobDispatchContext` from `dev.vertique:vertique-job-core` are injected simply by declaring them as
parameters; cancellation, progress and structured logging all go through `JobContext`.

---

## Key Classes

### DelayedJobService

The untyped enqueue API — inject it for the `@DelayedJobHandlerMethod` style.
`enqueue(DelayedJob)` uses the default pool; `enqueue(DelayedJob, SqlClient)` joins the caller's
transaction. Two `enqueuePremerged` overloads exist for framework-internal use: they persist a
pre-assembled propagation document without re-capturing context, are `public` only so generated
proxies can reach them, and fail closed on a document carrying identity context. Application code
uses `enqueue`.

```java
Future<UUID> executionId = delayedJobService.enqueue(
        DelayedJob.builder()
                .handler("send-welcome-email")
                .payload(new WelcomeEmailPayload(userId, email))
                .runAt(Instant.now().plusSeconds(60))
                .maxAttempts(5)
                .build());
```

Transactional (outbox) enqueue — the job exists only if the business write commits:

```java
pool.withTransaction(conn ->
        orderRepository.save(order, conn)
                .compose(v -> delayedJobService.enqueue(welcomeEmailJob, conn)));
```

Validation runs before the database is touched and returns a **failed future** rather than throwing:
the handler name must match `[a-zA-Z0-9._-]{1,128}` and be registered (the message lists the
registered names), and `maxAttempts` must be in `[1, 1000]`. The handler's event bus address is
resolved at enqueue time from the registered handler map, so the persisted address is the one the
poller will dispatch to.

### DelayedJob

Lombok `@Builder` value object. Only `handler` is required.

| Field | Default | Description |
|---|---|---|
| `handler` | *required* | Handler name; must match a registered handler |
| `payload` | `null` | Any Jackson-serializable object; stored as JSONB |
| `runAt` | `null` | Eligibility time; `null` or past means immediately claimable |
| `queue` | `"default"` | Logical queue name |
| `priority` | `0` | Higher values claimed first |
| `maxAttempts` | `3` | Attempts before dead-letter; must be in `[1, 1000]` |
| `jobId` | auto-generated | Logical job id, stable across retries; generated as `delayed-<uuid>` when `null` |
| `metadata` | empty `DurableMetadata` | Durable propagation context; normally left alone |

`metadata` exists for advanced callers that must supply a pre-built `DurableMetadata` document. In
the normal path the framework captures ambient durable context at enqueue time and merges it over
whatever the caller supplied; a caller-supplied namespace that collides with a currently bound
framework context fails the enqueue rather than silently overwriting.

### @DelayedJobHandlerMethod

Method-level annotation marking a service **implementation** method as the handler for a named job
type. Its single attribute `value()` is the handler name and must be unique across every registered
handler. Placing the annotation on the contract interface is a startup error.

```java
@DelayedJobHandlerMethod("send-welcome-email")
public Future<Void> sendWelcomeEmail(WelcomeEmailPayload payload, JobContext ctx) {
    ctx.logger().info("Sending welcome email");
    ctx.progress().setTotal(1);
    return emailService.send(payload.email())
            .onSuccess(v -> ctx.progress().incrementSucceeded());
}
```

### Exceptions

Two semantic roots: `DelayedJobConfigurationException` (core `ConfigurationException`) for startup
problems, with `DelayedJobRegistrationException` under it exposing `violations()`; and
`DelayedJobTechnicalException` (core `TechnicalException`) for runtime failures, with
`DelayedJobPersistenceException` under it.

Every `enqueue*` method translates persistence failures automatically, so callers never see a raw
`DataAccessException`. They surface as `DelayedJobPersistenceException`, whose `retryable()` flag is
`true` for optimistic-locking, pessimistic-locking and transient data-access failures (deadlock,
query timeout, connection failure) and `false` for any other data-access failure. Anything that is
not a data-access failure — including validation errors — passes through unchanged. The translated
message deliberately excludes the database message text; the original exception is the cause.

### Invariants & Gotchas

- **Handler names are globally unique.** A name claimed by both a `@DelayedJobContract` and a
  `@DelayedJobHandlerMethod` is a startup failure, not a last-one-wins merge — and registration
  reports every violation at once, so one restart shows the full picture.
- **Enqueue validation is a failed future, not an exception** — chain `onFailure`/`recover`. A
  `runAt` in the past is not an error; it simply makes the job immediately claimable.
- **`Duration`-based enqueue is resolved at call time.** `enqueue(payload, Duration.ofMinutes(5))`
  computes `Instant.now().plus(delay)` in the client, so a slow transaction shifts the effective
  delay earlier relative to commit.
- **A dead-lettered job stays dead-lettered.** There is no automatic re-drive and no built-in
  dead-letter management API.
- **Retry backoff is per queue.** Two jobs with different retry profiles need different queues.
- **In-flight work is not interrupted on shutdown.** The poller stops claiming and unregisters its
  reply consumers; already-dispatched executions run to completion, and their completion callbacks
  may land after the verticle stopped. Each in-flight execution's buffered job logs are drained one
  last time before `stop()` completes, but this is a bounded cutoff snapshot, not a final flush — the
  handler is not interrupted, so entries it logs afterward are lost. The drain awaits a write left
  outstanding by the progress tick, so the snapshot cannot come back empty just because a tick write
  was still running.

---

## Typed Job Contracts

The typed pattern splits the enqueue API from the execution logic across two generic interfaces
linked by one annotation: `@DelayedJobContract` carries the name and defaults,
`DelayedJobClient<P>` is the client side (enqueue only, never implemented by hand), and
`DelayedJobExecutor<P, C>` is the server side (execute only, holding your business logic). At
startup the framework resolves `P` and `C` from the executor's type arguments, reads
`@DelayedJobContract` from `C`, and registers the executor as a service contract entry at the event
bus address `jobs/delayed/{name}/execute`.

### @DelayedJobContract and DelayedJobClient

| Attribute | Default | Description |
|---|---|---|
| `name` | *required* | Handler name; must match `[a-zA-Z0-9._-]{1,128}` |
| `maxAttempts` | `3` | Must be in `[1, 1000]`; overridable via config |
| `queue` | `"default"` | Overridable via config |
| `priority` | `0` | Higher values claimed first; overridable via config |

```java
@DelayedJobContract(name = "deliver-webhook", maxAttempts = 2)
public interface DeliverWebhookJob extends DelayedJobClient<WebhookPayload> {}
```

`DelayedJobClient<P>` declares six `enqueue` overloads: `(P)`, `(P, Instant runAt)`,
`(P, Duration delay)`, `(P, SqlClient tx)`, `(P, DelayedJobOptions)`, and
`(P, DelayedJobOptions, SqlClient tx)`. Passing `null` for the `SqlClient` argument of a
transactional overload fails the returned future with a `NullPointerException` rather than silently
degrading to a non-transactional enqueue.

### DelayedJobExecutor

```java
@Singleton
class DeliverWebhookJobImpl implements DelayedJobExecutor<WebhookPayload, DeliverWebhookJob> {

    private final WebhookClient webhookClient;

    @Inject
    DeliverWebhookJobImpl(WebhookClient webhookClient) {
        this.webhookClient = webhookClient;
    }

    @Override
    public Future<Void> execute(WebhookPayload payload, JobContext ctx) {
        ctx.logger().info("Delivering webhook for payment {}", payload.paymentId());
        return webhookClient.notify(payload.paymentId());
    }
}
```

`execute` receives the deserialized payload and a `JobContext`. A failed future consumes an attempt
and triggers retry according to the effective `maxAttempts`.

Implement `DelayedJobExecutor` **directly with concrete type arguments**. An intermediate abstract
base class that forwards the type variables (`abstract class BaseJob<P, C> implements
DelayedJobExecutor<P, C>`) is not supported — startup type resolution cannot trace `P` and `C`
through it, and registration fails.

### DelayedJobOptions

Per-enqueue overrides; every field is nullable and `null` means "use the effective contract default".
`runAt` (`Instant`), `queue` (`String`), `priority` (`Integer`), `maxAttempts` (`Integer`) and
`jobId` (`String`, a stable id for idempotency) are the application-facing fields.
`premergedMetadata` is framework-internal — leave it `null`.

```java
job.enqueue(payload, DelayedJobOptions.builder()
        .runAt(Instant.now().plusHours(1))
        .queue("priority")
        .jobId("webhook-pay_123")
        .build());
```

### DelayedJobClientFactory

Creates the client proxy for a contract interface via `create(Class<T>)`, rejecting a non-interface
or an interface without `@DelayedJobContract` with `IllegalArgumentException`. See
[Registering a typed job](#registering-a-typed-job) for the binding.

When `dev.vertique:vertique-codegen-delayed-job` has generated a static
`{Contract}_DelayedJobProxy`, the factory uses it; otherwise it falls back to an equivalent
reflective proxy. A generated class that is present but cannot be instantiated raises
`IllegalStateException` — it never degrades silently.

**Effective configuration priority**, highest first: per-enqueue `DelayedJobOptions`, then
application config at `delayedJob.contracts.{name}.*`, then `@DelayedJobContract` defaults.
Precedence applies **per field** — setting only `maxAttempts` in config leaves `queue` and `priority`
at their annotation values.

### DelayedJobTargetResolver

Resolves a target to its runtime address and effective defaults — for integrations that persist a
stable target id and need the current dispatch address.

```java
public interface DelayedJobTargetResolver {
    ResolvedDelayedJobTarget resolve(Class<? extends DelayedJobClient<?>> contractInterface);
    ResolvedDelayedJobTarget resolve(String targetId);
    Set<String> supportedTargetIds();
}
```

Both `resolve` overloads throw `IllegalArgumentException` when the target is not registered.
`supportedTargetIds()` returns every id this node can dispatch, which is what capability-aware relays
claim against. `ResolvedDelayedJobTarget` is a record with components, in order: `targetId`,
`handlerName`, `handlerAddress` (e.g. `jobs/delayed/deliver-webhook/execute`), `queue`, `priority`,
`maxAttempts`.

Typed contracts and annotation-based handlers are both resolvable by id; only typed contracts are
resolvable by class. A typed contract resolves to its effective config/annotation values, while an
annotation-based handler resolves to the framework defaults (`"default"` queue, priority `0`,
`maxAttempts` `3`), because `@DelayedJobHandlerMethod` carries no defaults of its own.

---

## Configuration

### Queues — `delayedJob.queues.{name}`

| Field | Default | Description |
|---|---|---|
| `sleepDelayMs` | `5000` | Base interval between poll cycles. While a queue yields nothing the poller backs off — doubling up to 4× this value, capped at 60 s — and resets to the base as soon as work is found |
| `maxConcurrentJobs` | `5` | Maximum in-flight dispatches per poller instance |
| `backoffStrategy` | `"LINEAR"` | `FIXED`, `LINEAR` or `EXPONENTIAL`, case-insensitive |
| `backoffBaseDelayMs` | `30000` | Base retry delay |
| `backoffMaxDelayMs` | `3600000` | Retry delay cap |
| `instances` | `1` | Poller verticles deployed for this queue |

Every numeric field must be `> 0` and `backoffStrategy` must name a known strategy; a violation fails
startup with a message naming the offending queue. If no queues are configured, a single `"default"`
queue is created with the defaults above.

```json
{
  "delayedJob": {
    "queues": {
      "emails": { "sleepDelayMs": 2000, "maxConcurrentJobs": 10, "instances": 2,
                  "backoffStrategy": "EXPONENTIAL", "backoffBaseDelayMs": 10000,
                  "backoffMaxDelayMs": 600000 }
    },
    "contracts": {
      "deliver-webhook": { "maxAttempts": 5, "queue": "priority", "priority": 10 }
    }
  }
}
```

### Contract overrides — `delayedJob.contracts.{name}`

`maxAttempts`, `queue` and `priority` are all optional; an omitted field inherits the annotation
value rather than a config default. The contract name must be non-blank.

### Shared timing and throughput

`executionTimeoutMs` and `progressFlushIntervalMs` come from `job.coordinator` in
`dev.vertique:vertique-job-core`, so every queue shares one timeout policy; setting either to `0`
disables that behaviour. A per-execution `JobLogFlusher` drains buffered `JobLogger` entries to
`job_logs` on the same `progressFlushIntervalMs` tick (default `10000`) — unconditionally, since log
entries change independently of the progress snapshot — and on every path that ends the execution:
completion, timeout, and poller `stop()`. The tick calls `flush()`; every ending site calls
`drain()`, which awaits a write still outstanding from the tick and re-flushes while entries remain,
bounded at 4 rounds — the ending site has just cancelled the tick that would otherwise have retried.
Setting `progressFlushIntervalMs` to `0` disables only the *periodic* flush; the ending-site drains
still run, so logs remain durable but are not visible until the execution ends. Total claim capacity
for a queue is
`delayedJob.queues.{name}.instances × maxConcurrentJobs`, while executor-side parallelism is set
separately by `services.contracts.delayed-job.{name}.instances` — raising claim capacity without
raising executor capacity just moves the queue.

---

## Extension Points

### JobInterceptor

Interceptors from `dev.vertique:vertique-job-core` fire around every dispatch on every queue, in the
framework extension order — `onDispatch` before the send, `onComplete` when the reply arrives, before
persistence. Register them with a plain `@IntoSet` multibinding:

```java
@Provides @IntoSet
static JobInterceptor metricsInterceptor(MetricsService metrics) {
    return new MetricsJobInterceptor(metrics);
}
```

For durable outcomes on *every* path — including timeout, dead-node recovery and cancellation — use
`JobExecutionStateTransitionListener` instead. See `dev.vertique:vertique-job-core` for both SPIs.

---

## Module Dagger Bindings

`DelayedJobModule` is the single module to include. It transitively includes `JobModule`,
`JobPostgresqlModule`, `JobCoordinatorModule`, the context runtime and the logging context module.

```java
@Component(modules = {VertxModule.class, DispatchModule.class, DbPostgresqlModule.class,
                      DbFlywayModule.class, DelayedJobModule.class, AppModule.class})
public interface AppComponent { ... }
```

| Binding | Kind | Description |
|---|---|---|
| `DelayedJobService` | `@Singleton` | Untyped enqueue API |
| `DelayedJobClientFactory` | `@Singleton` | Typed client proxies |
| `DelayedJobTargetResolver` | `@Singleton` | Target id → address and effective defaults |
| `DelayedJobHandlerRegistrar` | `@Singleton` | Handler scan; **already scanned** when injected |
| `JobCompletionHandler` | `@Singleton` | Shared completion handling, wired to `JobRepository` |
| `ServiceContractContributor` | `@IntoSet` | Registers typed executors as service contracts |
| `Set<VerticleDeployment>` | `@ElementsIntoSet` | One poller deployment per queue, `SERVICES` phase, priority 100 |
| `Set<Object>` | `@Multibinds @DelayedJobs` | Empty executor set, so contributing zero executors is valid |

Handler registration runs eagerly inside the `DelayedJobHandlerRegistrar` provider — application code
does **not** call `scan()`. A registration violation therefore fails Dagger graph construction at
startup, before any poller is deployed.

### Registering a typed job

Both sides of a typed job — the server-side executor and the client-side proxy — are generated for
applications using the annotation-processor facade. Applications inheriting `vertique-app-parent`
get it automatically; custom-parent applications follow the BOM plus `vertique-codegen-all` recipe
in `docs/packaging.md`. Include both generated modules in the `@Component`:

```java
@Component(modules = {
    // ... existing modules ...
    GeneratedDelayedJobsModule.class,            // server side — from vertique-codegen-dagger
    GeneratedDelayedJobClientsModule.class       // client side — from vertique-codegen-delayed-job
})
interface AppComponent { ... }
```

- `dev.vertique:vertique-codegen-dagger` emits the `@Provides @IntoSet @DelayedJobs Object` binding
  for every class implementing `DelayedJobExecutor<P, C>` with a single `@Inject` constructor.
  Annotate an executor with `@NoAutoWire` to keep a hand-written binding canonical; because
  `@NoAutoWire` is source-retained, applications using that opt-out also declare
  `vertique-codegen-core` at `provided` scope.
- `dev.vertique:vertique-codegen-delayed-job` emits the `@Provides @Singleton` client binding for
  every `@DelayedJobContract` interface, each delegating to `DelayedJobClientFactory.create(…)`.

Either binding can still be hand-written when the generated one does not apply — for example when a
contract lives in a module the processor does not see. Write only the side you need, and do not
duplicate a binding an installed generated module already provides (Dagger rejects the duplicate at
compile time):

```java
@Module
public class ManualWiringModule {

    // Server side — the executor joins the @DelayedJobs multibinding
    @Provides @IntoSet @DelayedJobs
    static Object deliverWebhookExecutor(DeliverWebhookJobImpl impl) {
        return impl;
    }

    // Client side — the typed proxy, the same shape the generated module emits
    @Provides @Singleton
    static DeliverWebhookJob deliverWebhookJobClient(DelayedJobClientFactory factory) {
        return factory.create(DeliverWebhookJob.class);
    }
}
```

---

## Dependencies

- **job-core** — `JobRepository`, `JobContext`, `JobDispatchContext`, `JobInterceptor`,
  `JobCompletionHandler`, `JobCoordinatorConfig`, `JobExecution` and the state machine.
- **job-postgresql** — the `JobRepository` binding and the transactional
  `save(execution, SqlClient)` overload behind transactional enqueue.
- **services** — handler discovery through `ServiceContractRegistry`, and dispatch through the
  services invoker, which is what injects `JobContext`/`JobDispatchContext` into handler methods.
- **deploy** — `VerticleDeployment` and `LifecyclePhase` for the per-queue poller deployments.
- **core** — event bus dispatch (`DispatchEnvelope`, `EventBusClient`, `Result`), the exception
  roots, `ConfigParser` and the keyed-config support.
- **resilience** — the `BackoffStrategy` contract used for retry delays.
- **context** — durable context propagation across the persistence hop.
- **db-core** — the `DataAccessException` family the enqueue exception mapper translates.
- **logging** — the MDC context facade used on the dispatch and reply paths.
- **config-core** — the `ConfigParser` implementation the module's config boundary resolves.

> **MDC note.** The poller enriches MDC before dispatching each job execution. Enrichment that runs
> on the poller verticle thread (a non-duplicated Vert.x context) writes through SLF4J's `MDC`
> directly, because the framework facade (`dev.vertique.logging.MDCContexts`) requires a duplicated
> Vert.x context and would throw otherwise. Enrichment inside event-bus consumer handlers, which do
> run on duplicated contexts, uses the framework facade normally.
