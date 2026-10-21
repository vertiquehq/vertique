<!--
SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
SPDX-License-Identifier: EUPL-1.2
-->

# Workflow

> **Status:** Stable

Vertique Workflow is the framework's durable orchestration layer. It lets applications model long-running work as versioned workflow definitions, persist each instance, wait for external signals, create human tasks, schedule timers, dispatch service side effects through the transactional outbox, and resume safely after process restarts.

Use Workflow when the business process outlives a single request or requires explicit coordination between services, people, timers, and state transitions.

---

## Core Use Cases

| Use case | What Workflow provides |
|---|---|
| Service orchestration | Dispatch work to service contracts through durable outbox entries |
| Approval flows | Create human tasks, capture decisions, and update workflow state |
| Scheduled transitions | Wait until a timer, due date, publish time, or expiration time |
| Signal-driven processes | Suspend until an external event or service callback arrives |
| Fan-out/fan-in | Run parallel branches and join them before continuing |
| Object lifecycle workflows | Orchestrate review, publish, expire, archive, or remediation around object ids and versions |
| Document-authored flows | Load typed YAML/JSON workflow definitions without changing the runtime engine |

Workflow is not a replacement for simple request/response service calls. If all work finishes inside one request and does not need durable retry, waiting, human action, or long-running state, use regular services.

---

## Mental Model

### Definition

A workflow definition describes the step graph for one workflow version. Definitions can be written in Java with the builder DSL or loaded from YAML/JSON through the workflow definition module.

Definitions are registered by id and version. New workflow instances pin the definition version they start with.

### Plan

Registration compiles a definition into a deterministic workflow plan. The plan stores serializable step metadata, callback ids, type names, and a plan hash. Java callback functions are kept in a callback registry and referenced by id.

The engine executes plans. It does not parse source files or YAML during runtime transitions.

### Instance

A workflow instance is one running copy of a definition. It stores:

- definition id and pinned version
- current step
- status
- serialized workflow state
- optional business key
- optional subject reference
- plan hash

Instances are persisted by `workflow-postgresql`.

### State

Workflow state is the private state of the orchestration. It should contain the facts needed to continue the workflow, not arbitrary copies of external domain data.

For object-backed workflows, state should usually carry object type, object id, object version, transition id, and workflow-local progress. The object store remains the source of truth for object content.

### Signals

A signal is an external event delivered to a waiting workflow instance. Signals have names, payloads, and dedup keys. They are useful for service callbacks, webhooks, message consumers, and user actions that resume a waiting workflow.

### Tasks

A human task suspends a workflow until a user or system completes a named decision. Tasks can be assigned to users, roles, or queues. Decisions may carry typed payloads and update workflow state.

Tasks can require subject-version stability, which is useful for approvals over mutable domain objects.

### Timers

Timers suspend a workflow until a time is reached. Timers support standalone waits, signal timeouts, task due dates, reminders, publish-at steps, and expire-at steps.

Durable timer execution is provided by `workflow-delayed`. Each `TimerRecord` carries a `metadata: DurableMetadata` field that holds the namespaced durable propagation context captured at timer-create time. See [Durable Context Propagation](#durable-context-propagation) below.

### Side Effects

The engine does not make live downstream calls inside a workflow transaction. Instead, it emits side-effect intents. Installed recorders persist durable handoff records inside the same transaction.

Common side effects are service dispatch, timer scheduling, and workflow events.

---

## Module Map

| Module doc | Artifact | Purpose |
|---|---|---|
| [workflow-core.md](../vertique-workflow/vertique-workflow-core/src/main/resources/META-INF/vertique/module.md) | `vertique-workflow-core` | Public APIs, builder DSL, registry, contracts, task/timer/side-effect SPIs |
| [workflow-postgresql.md](../vertique-workflow/vertique-workflow-postgresql/src/main/resources/META-INF/vertique/module.md) | `vertique-workflow-postgresql` | Durable Postgres engine and stores, the workflow schema migration declaration (`WorkflowMigrationsModule`) and the optional `db.schemas.workflow` placement |
| [workflow-services.md](../vertique-workflow/vertique-workflow-services/src/main/resources/META-INF/vertique/module.md) | `vertique-workflow-services` | Service-dispatch outbox recorder and workflow signal service contract |
| [workflow-delayed.md](../vertique-workflow/vertique-workflow-delayed/src/main/resources/META-INF/vertique/module.md) | `vertique-workflow-delayed` | Durable timers through delayed jobs and cron recovery |
| [workflow-tasks.md](../vertique-workflow/vertique-workflow-tasks/src/main/resources/META-INF/vertique/module.md) | `vertique-workflow-tasks` | Application-facing task list/get/complete/reassign API |
| [workflow-events.md](../vertique-workflow/vertique-workflow-events/src/main/resources/META-INF/vertique/module.md) | `vertique-workflow-events` | Durable external workflow event stream |
| [workflow-definition.md](../vertique-workflow/vertique-workflow-definition/src/main/resources/META-INF/vertique/module.md) | `vertique-workflow-definition` | Typed YAML/JSON workflow definition authoring |
| [workflow-schema.md](../vertique-workflow/vertique-workflow-schema/src/main/resources/META-INF/vertique/module.md) | `vertique-workflow-schema` | Payload schemas for decision and start payloads, checked at definition registration |

---

## Which Modules Do I Need?

| Need | Install |
|---|---|
| Define workflows and use core APIs | `WorkflowCoreModule` |
| Persist and execute workflows | `WorkflowPostgresqlModule` (also contributes the workflow schema migration declaration) |
| Run the workflow schema migration without starting the application | `WorkflowMigrationsModule` in a migration-only component (see below) |
| Dispatch workflow service calls | `WorkflowServicesModule` plus transactional messaging service relay |
| Wait on timers or signal timeouts | `WorkflowDelayedModule`, delayed jobs, persistent cron |
| Human task inbox/actions | `WorkflowTasksModule` |
| Emit workflow lifecycle events | `WorkflowEventsModule` plus an outbox destination binding |
| Load YAML/JSON definitions | `WorkflowDefinitionModule` |
| Describe and pre-validate decision and start payloads | `WorkflowSchemaModule` |
| Migrate in-flight instances between versions | `WorkflowMigrationModule` with migration handlers (not `WorkflowMigrationsModule`: see below) |

Most production workflow apps install core, postgresql, services, transactional messaging, database modules, and then add delayed/tasks/events/definition based on the workflow features they use.

---

## Common App Wiring

Minimal durable service-orchestration setup:

```java
@Singleton
@Component(modules = {
    VertxModule.class,
    WorkflowCoreModule.class,
    WorkflowPostgresqlModule.class,
    WorkflowServicesModule.class,
    TransactionalMessagingPostgresqlModule.class,
    TransactionalMessagingServiceModule.class,
    DbModule.class,           // pool configuration and the optional db.schemas placement
    DbPostgresqlModule.class,
    DbFlywayModule.class,     // only when the application runs the migration at startup
    AppModule.class
})
public interface AppComponent {
    WorkflowOperations workflowOperations();
    WorkflowClientFactory workflowClientFactory();
}
```

Add human tasks and timers:

```java
@Singleton
@Component(modules = {
    VertxModule.class,
    WorkflowCoreModule.class,
    WorkflowPostgresqlModule.class,
    WorkflowServicesModule.class,
    WorkflowDelayedModule.class,
    WorkflowTasksModule.class,
    DelayedJobModule.class,
    CronPersistenceModule.class,
    TransactionalMessagingPostgresqlModule.class,
    TransactionalMessagingServiceModule.class,
    DbModule.class,
    DbPostgresqlModule.class,
    DbFlywayModule.class,
    AppModule.class
})
public interface AppComponent {
    WorkflowOperations workflowOperations();
    TaskService taskService();
    WorkflowDelayedComposeValidator workflowDelayedComposeValidator();
    WorkflowTasksComposeValidator workflowTasksComposeValidator();
}
```

Add document-authored workflows:

```java
@Singleton
@Component(modules = {
    WorkflowCoreModule.class,
    WorkflowPostgresqlModule.class,
    WorkflowServicesModule.class,
    WorkflowDefinitionModule.class,
    AppWorkflowDefinitionCallbacksModule.class,
    AppWorkflowDefinitionSourcesModule.class
})
public interface AppComponent {
    WorkflowOperations workflowOperations();
    WorkflowDefinitionService workflowDefinitionService();
}
```

Exact wiring depends on the application's persistence, messaging, delayed-job, and event-destination modules.

### Schema placement and migrations

The workflow tables live in the connection's default schema unless `db.schemas.workflow` maps them to another one. With `DbModule` installed, that single setting places all nine PostgreSQL workflow stores and, in named Flyway mode (`flyway.named`), the migration itself, so the migration and every statement agree. Without the entry, SQL stays unqualified. Components that migrate and components that run the workflows read the same `db.schemas` configuration.

The migration can run without the application: a migration-only component installs `DbModule`, `DbFlywayModule` and `WorkflowMigrationsModule` (plus the declaration modules of the other instances it migrates), needs no connection pool, and calls `FlywayMigrationRunner.run(vertx)` from a deploy step or job. The runtime component then installs `DbModule` and `DbPostgresqlModule` and needs no Flyway module unless it migrates at startup. See the [workflow-postgresql reference](../vertique-workflow/vertique-workflow-postgresql/src/main/resources/META-INF/vertique/module.md#flyway-configuration) for the configuration, the legacy single-Flyway mode and the class loader the runner uses.

Do not confuse the two similarly named modules: `WorkflowMigrationsModule` (plural, in `vertique-workflow-postgresql`) declares the database schema migration, while `WorkflowMigrationModule` (singular, in `vertique-workflow-core`) wires migration of in-flight workflow instances between definition versions.

---

## Authoring Models

### Java DSL

Use the Java DSL when workflow logic is application-owned and benefits from compile-time types:

```java
wf.init(PlaceOrder.class, cmd -> new OrderState(cmd.orderId()))
  .initialStep("reserve-inventory")
  .dispatch("reserve-inventory", "inventory.reserve",
      state -> new ReserveInventoryRequest(state.orderId()),
      "await-inventory")
  .waitFor("await-inventory", "inventory.reserved",
      InventoryReserved.class,
      (state, event) -> state.withInventoryReserved(true),
      "ship")
  .complete("done");
```

### Typed Documents

Use YAML/JSON definitions when workflow shape should be data-driven while Java still supplies the type model and callback catalog.

```yaml
definitionId: order-fulfillment
definitionVersion: 1
stateType: com.example.OrderState
contract: com.example.OrderFulfillmentWorkflowV1
startPayloadType: com.example.PlaceOrder
initialStateMapper: order.fromPlaceOrder
initialStep: reserve-inventory
steps:
  - id: reserve-inventory
    type: service
    target: inventory.reserve
    payloadMapper: order.reserveInventoryPayload
    next: done
  - id: done
    type: complete
```

Document definitions are not currently no-code object schemas. They still reference Java classes and named callbacks.

Runtime management uses Futures. Load a candidate into a scope, then publish its durable key:

```java
Future<CompiledDefinitionRef> published = definitionService
        .load("acme", content, DocumentFormat.YAML, sourceMetadata)
        .compose(ref -> definitionService.publish(new DefinitionKey(
                "acme", ref.definitionId(), ref.definitionVersion())).map(ref));
```

With PostgreSQL installed, every definition, code-first or document-authored, is also a row in a durable
registry that survives restarts and converges across nodes. See [Durable Definition Registry](#durable-definition-registry)
for the lifecycle and operations, and [Upgrading to 0.4.0](#upgrading-to-040) before moving an existing application.

---

## Durable Definition Registry

The durable registry stores one row per workflow definition version in the `workflow_definitions` table, so
definitions, their lifecycle status and their document sources survive restarts and reach every node.

### When it is active

- **Store bound.** The registry is active when a `WorkflowDefinitionRegistryStore` is bound. `WorkflowPostgresqlModule`
  binds one unconditionally, so every PostgreSQL application has it. It needs PostgreSQL 15 or later.
- **No store.** Without a store (graphs that do not install the PostgreSQL module) nothing changes: definitions stay
  in memory, the service keeps its local outcomes behind the same `Future` signatures, the startup step does no I/O
  and no timer is scheduled. `deprecate`, `retire` and `reinstate` need the store and fail without one.
- **Identity.** A row is identified by `DefinitionKey(scopeKey, definitionId, definitionVersion)`. The scope key is
  required; `WorkflowScopes.DEFAULT` is the default scope, which code definitions always use. Any number of versions and scopes of one id are available at once.
- **Definition ids.** Ids are unprefixed: the document id is the definition id everywhere it is used
  (`StartCommand.definitionId`, pinned resolution, migration targets). A document may use the id of a code definition or of
  another scope's document, and each scope numbers its own versions. `load` and `publish` take any scope key, so restrict
  them to trusted callers and never forward their refusal messages to tenants: a refusal can name other scopes and plan hashes.

### Code and document definitions

| Kind | Where it comes from | Row |
|---|---|---|
| `CODE` | Definitions the application registers in Java, including contributors | Default scope, recorded at startup |
| `DOCUMENT` | Packaged YAML/JSON sources and documents loaded at runtime | Any scope, with source text, format and provenance |

Code registers itself from the deployed application, so `load` and `publish` apply to documents only.
`deprecate`, `retire` and `reinstate` apply to both kinds.

### Lifecycle

| Status | Meaning |
|---|---|
| `CANDIDATE` | Loaded and compiled, not published |
| `PUBLISHING` | A node holds a short-lived publication claim; a claim older than twice the staleness bound counts as abandoned |
| `AVAILABLE` | Published: it can be started explicitly, pinned and resolved. Any number of versions and scopes of one id are available together; which version starts by default is selected separately |
| `DEPRECATED` | Live for pinned instances; refused for new starts and migration targets |
| `RETIRED` | Retained metadata only; no executable membership |

| Operation | Moves | Typical failure reasons |
|---|---|---|
| `publish` | `CANDIDATE` to `AVAILABLE` through `PUBLISHING` | `STALE_CLAIM`, `DRIFT`, `LOADER_MISSING`, `INVALID_TRANSITION` |
| `deprecate` | `AVAILABLE` to `DEPRECATED` | `INVALID_TRANSITION` |
| `retire` | `CANDIDATE`, or a cooled `DEPRECATED` row, to `RETIRED` | `COOLING`, `PINNED_INSTANCES`, `INVALID_TRANSITION` |
| `reinstate` | `DEPRECATED` or `RETIRED` to `AVAILABLE` | `CODE_UNAVAILABLE`, `DRIFT`, `LOADER_MISSING`, `INVALID_TRANSITION` |

Every other status pair is refused with `INVALID_TRANSITION`. Repeating a completed operation (publishing an available
version, deprecating a deprecated one, retiring a retired one) is accepted without a change.

Refusals are `WorkflowDefinitionLifecycleException` instances with a `reason()` and the `key()`; other failures, such as
no bound store, an invalid key or a lock timeout, use their own exception types. They arrive as failed `Future`s, never as
synchronous exceptions.

- **Deprecation.** An explicit-version start and a `migrate` whose target is deprecated fail with
  reason `DEPRECATED`. Signals, timers and resumes of instances already pinned to the version keep working.
  Deprecating a version leaves every other version available; the default scope's current version cannot be
  deprecated until another version is activated.
- **Cooling window.** A deprecated version cannot retire until `workflow.definitions.maxStalenessSeconds` (default 30)
  has passed on the database clock. After that, retirement is refused with `PINNED_INSTANCES` while any `RUNNING`,
  `WAITING` or `COMPENSATING` instance, archived ones included, is pinned to the exact version.
- **Reinstatement.** `reinstate` of a retired version needs an executable source: the deployed code for a code version, the
  stored text for a document. A deprecated version is still registered, so its reinstatement only clears the deprecation
  mark. Either way the version becomes `AVAILABLE`.
- **No authorization.** `load`, `publish`, `deprecate`, `retire` and `reinstate` perform no access check. Restrict
  them to authorized callers in your application.

### Async usage

```java
DefinitionKey v1 = new DefinitionKey(WorkflowScopes.DEFAULT, "order-fulfillment", 1);
SourceMetadata source = new SourceMetadata("upload", null, null, "ops", null, Instant.now());

// Load a candidate from bytes and publish it.
Future<Void> published = definitionService
        .load(yamlBytes, DocumentFormat.YAML, source, null)
        .compose(ref -> definitionService.publish(
                new DefinitionKey(WorkflowScopes.DEFAULT, ref.definitionId(), ref.definitionVersion())));

// The snippets below are separate steps: run each one when its turn comes, not all at once.

// Stop new starts of the old version; pinned instances keep running.
Future<Void> deprecated = definitionService.deprecate(v1);

// Later, after the cooling window and once no instance is pinned. Until then this
// fails with reason COOLING or PINNED_INSTANCES, so treat it as retryable.
Future<Void> retired = definitionService.retire(v1)
        .onFailure(failure -> {
            if (failure instanceof WorkflowDefinitionLifecycleException e) {
                log.info("retire of {} refused: {}", e.key(), e.reason());
            }
        });

// Bring a retired version back.
Future<Void> restored = definitionService.reinstate(v1);

Future<List<WorkflowDefinitionRecord>> live = definitionService.list(new DefinitionFilter(
        WorkflowScopes.DEFAULT, null, Set.of(DefinitionStatus.AVAILABLE)));
```

`list` returns immutable metadata rows without source text. `sourceMetadata(key)` returns the source attribution
without reading the source body.

### Startup reconciliation

Before any `BOOTSTRAP` verticle deploys, the engine records and restores the registry, in this order:

1. It records every code definition as a default-scope `CODE` row and every packaged document as a `DOCUMENT` row, both `AVAILABLE`.
2. It restores every live `DOCUMENT` row of every scope (`AVAILABLE`, `DEPRECATED`) from its stored text,
   checking the compiled plan hash and the stored text digest.
3. It applies deprecation marks, removes runtimes of `RETIRED` rows that belong to the row's own key, and resets
   abandoned publication claims.

Startup fails, naming the definition, in these cases, among others (a loader or compile failure of a stored document, a failing
loader callback, a stored document over the envelope after lowering the limit, a non-UTF8 database, a second loader for one
format, an unattributed direct registration, and a lock timeout):

| Situation | Failure |
|---|---|
| Code compiles to a different plan hash while instances are pinned to that exact version | `DRIFT`, both hashes named |
| A stored row of the same id and version is of the other kind | `INVALID_TRANSITION` (kind conflict) |
| A live `CODE` row whose version the deployed code no longer provides | `CODE_UNAVAILABLE` |
| A code definition whose id or version breaks the key rules | `WorkflowDefinitionException` naming the definition |
| A live document row with no loader, or whose compiled hash or text digest differs | `LOADER_MISSING` or `DRIFT` |
| A packaged document whose text differs from the stored text under an equal plan hash | `DRIFT` (digest) |

- **Code hash change without pins.** When no instance is pinned to that exact version, the stored hash is replaced and
  a WARN logs both hashes. Changing a deployed version's compiled shape is still a rolling-deploy hazard, see
  [Upgrading to 0.4.0](#upgrading-to-040); prefer a new version.
- **Digest.** Each `DOCUMENT` row stores the SHA-256 of its stored UTF-8 text. It detects accidental or concurrent change
  of the stored text; it does not authenticate the writer, so restrict write access to the table.
- **Compose validators** run in `VALIDATE` and do not re-run for restored, propagated or runtime-published definitions.

### Peer propagation

Each node polls the table and applies differences, so a change committed on one node reaches the others.

- **Cadence.** The period is `workflow.definitions.maxStalenessSeconds` (default 30 seconds). The real bound is the
  cadence plus the time one pass needs to apply; a row that keeps failing gives no bound. The first pass runs one period
  after startup.
- **`pollNow()`.** `WorkflowDefinitionPropagationPoller.pollNow()` runs one pass immediately and returns its `Future`.
  A call while a pass runs returns that pass. The future succeeds even when some rows failed, and fails only when the
  table cannot be read.
- **What a pass does.** It restores live rows the node does not hold, marks deprecated rows, and removes runtimes of
  retired rows that the node holds for that row's own key. A `CODE` row needs the same version in the code deployed on that
  node with an equal plan hash, otherwise the row fails with `CODE_UNAVAILABLE` or `DRIFT`. Polling never records a code
  hash, never reinstates and never changes a durable row.
- **Failures.** A row that cannot be applied, or is not applied within twice the period, is logged with its key and
  reason (never source text; repeats of the same failure log at debug level), counted, and retried on the next pass.
- **Observability.** The bound `WorkflowDefinitionRegistryView` reports `registered()`, the live records this node
  has applied, and `applyFailures()`, the number of rows currently failing. It lists every scope's records, so it is an
  operator view: do not expose it to tenants unfiltered. It describes one node; another node can differ until its own
  pass has run.
- **Do not rely on peers for safety.** Deprecation and retirement checks use each node's own registry until its pass
  runs. Starts and migrations are protected by a database check at pin time (below), not by polling.

### Database coordination

- **Pin admission.** Before a new instance pin is written, a migration re-pins an instance, or a finished instance is
  resurrected (failed retry), the repository takes a shared advisory lock on the definition id and reads the id's rows in
  the caller's transaction. A refused pin fails with `WorkflowDefinitionLifecycleException` and rolls the transaction
  back. Starts share the lock, so they do not serialize each other, but `retire` and startup writes wait for them.
- **Lock timeout.** Every admission, and every registry write, sets `SET LOCAL lock_timeout = '30000ms'`. It overrides a
  stricter value set on the role or session and bounds every later lock wait in that transaction until commit or rollback.
  A timeout rolls back the caller's transaction (retry the whole transaction); registry operations surface it as a
  retryable `WorkflowPersistenceException`.
- **Stalls.** Long caller transactions can delay lifecycle and startup writes, and PostgreSQL queues later admissions
  behind a waiting writer, including a restarting node's startup reconciliation. Do not await a lifecycle call for an
  id while your own transaction holds an admission for it.
- **Stronger isolation.** Under `REPEATABLE READ` or `SERIALIZABLE`, an id with no visible definition row is refused with
  `INVALID_TRANSITION`, because the snapshot cannot tell an unmanaged id from a newly managed one. This includes engines
  assembled by hand that skip the registry bootstrap. `READ COMMITTED` admits an id with no rows.
- **Brief refusals.** For a short window between local publication and the durable commit of a publication, new starts of
  that version are refused. After a same-version code rehash (below), nodes still running the old code refuse new pins.
- **Registry writes** always run at `READ COMMITTED`, whatever the server default.
- **Retirement cost.** `retire` counts the pinned instances while it holds the exclusive id lock, and no index covers that
  predicate, so on a very large instance table a retirement can delay new starts of that id.

### Registry mutation outside the lifecycle

- **Direct registration.** The engine tracks which runtime it published for which key. A runtime registered directly
  through `WorkflowRegistry` is never adopted or removed by the lifecycle: `deprecate`, `retire` and `reinstate` of that
  version fail with `INVALID_TRANSITION`, and a startup or poll apply that needs the slot fails the same way and leaves the
  runtime in place. Do not register definitions directly at runtime and expect the lifecycle to manage them.
- **Concurrency boundary.** Managed operations serialize per definition id on each node. A direct `register` or `unregister`
  of the same id during a managed operation is outside that guarantee. Each individual registry operation stays thread safe.
- **Metadata reads.** With a store bound, `list` and `sourceMetadata` read the durable table. Metadata of definitions
  that application contributors or packaged sources register appears only after the managed `BOOTSTRAP` step has recorded
  it, and there is no in-memory fallback.

### Configuration

| Key | Default | Meaning |
|---|---|---|
| `workflow.definitions.maxStalenessSeconds` | `30` | Poll period, cooling window before retirement, and (doubled) the age of an abandoned publication claim |
| `workflow.definitions.maxDocumentSizeBytes` | `262144` | Largest runtime `load` input, in bytes (L); the stored form is capped at floor(3L/2) UTF-8 bytes and L code points |

Both keys are optional. To read them from configuration, install `WorkflowDefinitionsConfigModule` together with
`ConfigParsingModule`; otherwise the defaults apply, or bind `WorkflowDefinitionsConfig` directly. Use the same values on
every node sharing the table. Raise L on every node before admitting larger documents. Retire documents that exceed the
new envelope before lowering L; a lower limit makes restoration fail startup, a poll counts and retries the row, and a later
`publish` or `reinstate` of a retained row that exceeds the new envelope also fails.
Non-positive values are rejected at startup with a configuration error, as is an L whose stored envelope would overflow an
integer or a staleness period whose milliseconds would overflow a long.
Raising L increases memory and parse cost; the parser's nesting and expression limits are independent. The
[workflow definition module reference](../vertique-workflow/vertique-workflow-definition/src/main/resources/META-INF/vertique/module.md)
holds the per-path size rules.

---

## Upgrading to 0.4.0

This section covers the durable definition registry. Other changes in the release, such as the payload schema validation,
are described in their own sections. This release adds the durable definition registry. It contains a breaking change to `WorkflowDefinitionService` and
changes startup for every PostgreSQL application. Do the following in order.

### 1. Migrate the schema

- Flyway applies one new migration, `V002__workflow_definitions_and_scopes.sql`, from `db/migration/workflow`
  (`V1__` is unchanged). It creates the `workflow_definitions` table, keyed by `(scope_key, definition_id,
  definition_version)`, with its `document_digest`, `contract_name` and `state_type_name` columns, and it converts the
  released tables to scope keys:
  `workflow_instances` gains `scope_key` and `definition_scope_key` and `workflow_tasks` gains `scope_key`, each
  `VARCHAR(255) NOT NULL DEFAULT 'default'` (existing rows read `default`; no row is rewritten); the business-key index
  `idx_workflow_instances_business_key` becomes unique per `(scope_key, definition_id, business_key)`; the keyset indexes
  `idx_workflow_instances_scope_updated_at` and `idx_workflow_tasks_scope_updated_at` serve scope-filtered listings, each
  on `(scope_key, updated_at DESC, <id>)`; and `workflow_dedup.scope` is renamed `dedup_namespace`, gains `scope_key`, and
  its primary key becomes `(kind, scope_key, dedup_namespace, key)`. Stored fingerprints and namespaces are kept as they were.
- **Stop every 0.3.0 node first.** All nodes that run 0.3.0 must be stopped before the first 0.4.0 node starts and applies
  the migration. The 0.3.0 dedup statements use `ON CONFLICT (kind, scope, key)`; the renamed column and the new primary
  key leave that conflict target without a matching index, so a 0.3.0 node that is still running after the migration
  fails every start. Rolling deploys apply only from 0.4.0 on, and rolling code back to 0.3.0 is not supported.
- **Lock and duration.** Each `ALTER TABLE`, the business-key index rebuild and the primary-key rebuild on
  `workflow_dedup` hold an `ACCESS EXCLUSIVE` lock until the migration commits, and each index build is a sequential scan
  of its table under that lock, so the time the tables are blocked grows with their size. Run the migration when no long
  transaction is open on `workflow_tasks`, `workflow_instances` or `workflow_dedup`. It waits at most 30 seconds per
  statement for a lock (`SET LOCAL lock_timeout`), during which new statements on that table queue behind it, and then
  fails, so rerun it after the blocking transaction ends. A manual run must be one transaction
  (`psql --single-transaction -f V002__workflow_definitions_and_scopes.sql`), or the timeout has no effect.
- **Large installations.** The column and keyset-index statements are `IF NOT EXISTS`, so you can add the scope columns
  and build the two keyset indexes ahead of the upgrade and let the migration find them done, but only after the last
  0.3.0 node has stopped is it safe to change `workflow_dedup`, which always happens inside the migration. Run these exact
  statements outside a transaction (each on an autocommit connection), with the `ALTER` statements first. A statement that
  times out on its lock changes nothing and can be rerun:

  ```sql
  SET lock_timeout = '30s';
  ALTER TABLE workflow_tasks ADD COLUMN IF NOT EXISTS scope_key VARCHAR(255) NOT NULL DEFAULT 'default';
  ALTER TABLE workflow_instances ADD COLUMN IF NOT EXISTS scope_key VARCHAR(255) NOT NULL DEFAULT 'default';
  CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_workflow_tasks_scope_updated_at
      ON workflow_tasks (scope_key, updated_at DESC, task_id ASC);
  CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_workflow_instances_scope_updated_at
      ON workflow_instances (scope_key, updated_at DESC, id ASC);
  ```

  The migration then builds nothing for them. It always drops and rebuilds the business-key index, and it always renames
  the dedup column (`ALTER TABLE ... RENAME COLUMN` has no `IF EXISTS` form, so do not run it ahead of time). The
  `IF NOT EXISTS` forms match by name only, so a column or index of that name with a different definition is silently
  accepted: use the statements above unchanged. A failed concurrent build leaves an `INVALID` index that `IF NOT EXISTS`
  would skip: check `pg_index.indisvalid` for both index names and drop an invalid one before the migration runs.
- **Existing rows.** Instances, tasks and start deduplication rows of 0.3.0 read the default scope after the upgrade; the
  instances resume in it and complete their stored task decisions. The engine starts every new instance in the default
  scope as well.
- **Development databases.** A database that applied an earlier 0.4.0 snapshot must be recreated: version 2 changed its
  checksum and description, so Flyway validation fails, and the versions 3 and 4 it recorded are no longer shipped.
- Anything else that writes `DOCUMENT` rows, such as operator scripts or other modules, must supply `document_digest`:
  the lowercase hex SHA-256 of the stored UTF-8 text, present on `DOCUMENT` rows and absent on `CODE` rows. Where Flyway
  is disabled, run the migration script yourself, in one transaction, before the first 0.4.0 node starts.
- Durable `DOCUMENT` bodies need `server_encoding = UTF8`. The check runs before the first document read or write. `CODE`
  rows and applications without documents work on any encoding.

### 2. Port `WorkflowDefinitionService` callers (breaking)

Four operations are replaced in place, on the interface and on `DefaultWorkflowDefinitionService`, with no deprecation
cycle. Eight old declarations are removed; constructors and all other members stay.

| Before | After |
|---|---|
| `CompiledDefinitionRef load(byte[], DocumentFormat, SourceMetadata)` | `Future<CompiledDefinitionRef> load(byte[], DocumentFormat, SourceMetadata, @Nullable String scopeKey)` |
| `void activate(String id, long version)` | `Future<Void> publish(DefinitionKey key)` |
| `Collection<CompiledDefinitionRef> list()` | `Future<List<WorkflowDefinitionRecord>> list(DefinitionFilter filter)` |
| `Optional<SourceMetadata> sourceMetadata(String id, long version)` | `Future<Optional<SourceMetadata>> sourceMetadata(DefinitionKey key)` |

- **Added.** `deprecate(DefinitionKey)`, `retire(DefinitionKey)` and `reinstate(DefinitionKey)`, all returning `Future<Void>`.
  With no store, the four replaced operations keep their outcomes behind the new signatures, while the three added ones fail.
- **Custom implementations.** A class that implements `WorkflowDefinitionService` must now implement all seven operations.
  Classes that implement the new `WorkflowDefinitionRegistryStore`, `WorkflowDefinitionLifecycle` or
  `PersistedDefinitionLoader` types implement every method they declare.
- **`ActivationStatus`** is `CANDIDATE`, `PUBLISHING`, `AVAILABLE`, `DEPRECATED` and `RETIRED`: `ACTIVATING`, `ACTIVE` and `SUPERSEDED` are removed and `DEPRECATED` and `RETIRED` are new. A `switch` over it needs the new cases.
- **Public `currentStatus`.** `WorkflowDefinitionStore.currentStatus(id, version)` now also reports the persisted status of a
  global version the node knows only as metadata (a candidate or retired row it did not compile); it returns `null` only when
  nothing is known about the version.

### 3. Check what changes at startup

Every PostgreSQL application, including code-first ones that never touch documents:

- **First boot writes `CODE` rows.** Each code definition is recorded as a default-scope row. The `V002__` migration must have run.
- **Code drift can fail startup.** If a version's compiled shape changed and instances are pinned to that exact version
  (`RUNNING`, `WAITING` or `COMPENSATING`), startup fails with `DRIFT` naming both hashes. Keep the code of a version unchanged
  until its instances finish, or bump the version. With no pinned instance the stored hash is replaced and a WARN logs it.
- **Key rules.** A code definition with a blank, over-long or control-character id, or a non-positive version, fails
  startup with a message naming the definition. Definition ids and scope keys are at most 255 code points.
- **Kind conflicts** fail startup naming both rows.
- **Definition ids are unprefixed.** A document may use the id of a code definition or of another scope's document; each
  scope holds its own versions of the id.

Applications that combine the automatically bound PostgreSQL store with static document sources gain extra startup
prerequisites. Each packaged document now must:

- **Fit the stored envelope.** At the default L it is at most 393216 UTF-8 bytes and 262144 Unicode code points after
  decoding. Without a store, static JSON had no document size cap; this is a new boundary for JSON sources. A YAML source keeps
  its existing code-point guard, which now follows L.
- **Be strictly representable.** The whole source must decode without malformed encoding, decoded NUL or unpaired surrogates,
  including trailing bytes the parser ignores. Without a store such trailing bytes were accepted. Escaped source sequences
  stay as source text.
- **Be stored as UTF-8 text.** Durable storage keeps decoded characters, comments, whitespace and line endings, not the
  original bytes. JSON may arrive in UTF-8, UTF-16 or UTF-32; YAML must be UTF-8. The database must be `UTF8`.
- **Not change text without a new version.** An equal plan hash no longer proves equal text. A packaged document whose
  text differs from the stored text of the same id and version, even by a comment, whitespace or line endings only, fails
  startup with `DRIFT`, on every node whose packaged text differs from the stored text, including during a rolling deploy.
  Bump the version of a changed document: a row of the same id and version blocks startup in every status, retired
  ones included.

Metadata limits now apply in every mode, with or without a store, to `load` and static sources: definition id and tenant
key at most 255 code points; source type at most 64; URI at most 2048; revision, author and upload id at most 255. The
retained `WorkflowDefinitionStore` methods check only the key (id length and control characters, positive version). NUL and unpaired surrogates are rejected in every text field, and
ISO control characters in ids and tenant keys. A version must be positive. An over-limit source is rejected before anything
is registered, and a static source failing a limit fails startup naming the resource.

### 4. Plan the rollout

- **Same-version rehash.** The first 0.4.0 node that finds no pinned instance for a changed version rewrites the stored
  hash. From then on, every node still running the old code refuses new starts and migrations of that version with `DRIFT`, until it restarts
  on the new code. A failed instance pinned before the rewrite can no longer be retried on any node, because its recorded
  hash is the old one. Prefer a new version over editing a deployed one.
- **Rollback or old-code restart.** A node that restarts on code lacking a version the database holds as live fails startup
  with `CODE_UNAVAILABLE`.
- **Deleting a version.** To remove a version's code safely: `deprecate` it, wait out the cooling window, `retire` it
  while no instance is pinned, then remove the code in a later deployment.
- **Finish the rolling upgrade first.** `retire` and `reinstate` rely on every node taking the shared id lock when it
  admits a pin, so use them only once all nodes run 0.4.0.
- **Startup stalls.** Startup writes wait for open caller transactions on the same ids, up to 30 seconds per lock, and then
  fail with a retryable persistence error naming the id.

### 5. Adapt callers of the engine

- **Transaction clients.** Public transaction-aware engine entrypoints (`start`, the `signal` overloads, `cancel`, `retry`,
  `migrate`, and the task and timer callbacks) require a `SqlConnection` with a pending transaction begun through Vert.x,
  for example with `Pool.withTransaction`. A `Pool`, a bare or completed connection, a raw `BEGIN`, a wrapper or a mock
  fails the returned `Future` with `IllegalArgumentException` before any SQL. Await the operation before committing or
  rolling back. A custom `WorkflowTransactionRunner` must keep the transaction pending until the work future completes.
- **Pin admission.** Starts and migrations now run the admission check described under
  [Database coordination](#database-coordination), including the 30-second `SET LOCAL lock_timeout` and the stronger-isolation
  refusal.
- **Registry implementations.** `WorkflowRegistry` gains `unregister`, `markDeprecated`, `isDeprecated` and
  `registeredDefinition` as default methods, so existing implementations compile. The defaults of the first two throw
  `UnsupportedOperationException`, `isDeprecated` returns `false` and `registeredDefinition` returns empty.
  A decorator that forwards only the old members therefore makes `deprecate` and `retire` fail after the durable commit,
  keeps serving deprecated versions to new starts, and reports a code version as `CODE_UNAVAILABLE` on reinstatement.
  Forward all four members. `DefaultWorkflowRegistry` implements them.
- **Instance query.** `WorkflowInstanceQueryService.countNonTerminal(id, version)` is a default method that fails with
  `UnsupportedOperationException`; the PostgreSQL implementation overrides it.
- **Added bindings.** Applications gain the bound `WorkflowDefinitionLifecycle`, `WorkflowDefinitionRegistryView` and
  `WorkflowDefinitionPropagationPoller`. The poller adds one periodic timer per node; it is scheduled after startup and
  stopped at shutdown.
- **Instance scopes.** `V002__` adds the scope columns, which default to `default`. The engine starts every new instance in
  the default scope for now (see [Instance Scopes](#instance-scopes)). Scopes other than `default`
  use the `<scopeKey>:<name>` namespace for definition loads. Record patterns that deconstruct `WorkflowInstance`,
  `TaskRecord`, `WorkflowInstanceQuery` or `TaskFilter` by component list need the new trailing `scopeKey` components
  (`scopeKey` and `definitionScopeKey` on the instance). Other modules or tools that read `workflow_instances` or
  `workflow_tasks` see extra non-null columns, and tools that read `workflow_dedup.scope` must read `dedup_namespace`.

### 6. Configuration

Two optional keys, `workflow.definitions.maxStalenessSeconds` (30) and `workflow.definitions.maxDocumentSizeBytes` (262144).
Existing configuration needs no change. See [Configuration](#configuration).

## Instance Scopes

`WorkflowScopes.DEFAULT` (`"default"`) is the reserved default scope. It is a value, not a stored record: it always exists
and needs no registration. `WorkflowInstance` carries a non-null start scope (`scopeKey`) and the scope of its pinned
definition version (`definitionScopeKey`); `TaskRecord` carries a non-null `scopeKey`; `WorkflowInstanceQuery` and
`TaskFilter` carry a nullable `scopeKey` filter. The engine starts every instance in the default scope, and no engine
setting exists yet. The value is stored as given and is not validated beyond the column length of 255. Migration and
resurrection keep an instance's scopes for its life.

- **Tasks follow the instance.** The PostgreSQL task store writes the owning instance row's start scope on every task
  insert, whatever the `TaskRecord` carries, so a task created after another writer stamped its instance carries that
  scope and a task created earlier stays in the default scope.
- **Filters.** `WorkflowInstanceQuery.scopeKey` and `TaskFilter.scopeKey` match exactly when non-null; `null` matches every
  scope. Never pass an unresolved scope as `null` expecting scoping.
- **Information, not isolation.** The scope is a label and a filter. Operations addressed by instance or task id (query,
  signal, cancel, retry, migrate, task complete or reassign) do not check it, and timers, branch tokens and definition
  resolution are global: before acting on a caller-supplied id, load the instance and compare its `scopeKey()` with the
  caller's scope. Definition-lifecycle refusal messages can name another scope, status and plan hashes: do not
  forward them to tenants.

## Durable Context Propagation

Workflow timers, branches, instances, signals, and commands all participate in the framework's durable context propagation substrate. Every engine drive binds an *effective* durable-context document computed from a **base** (the most specific context available for that drive) with an **instance-fill** step that fills only namespaces the base does not already carry.

### New metadata fields (workflow-core)

| Type | Field | Description |
|------|-------|-------------|
| `TimerRecord` | `metadata: DurableMetadata` | Namespaced durable propagation context captured at timer-create time; persisted as `{"context": …}` JSONB; immutable after creation |
| `BranchToken` | `metadata: DurableMetadata` | Namespaced durable propagation context captured at branch-create (fork) time; persisted as `{"context": …}` JSONB; immutable through all subsequent state transitions |
| `WorkflowInstance` | `metadata: DurableMetadata` | Namespaced durable propagation context captured once at `start` time; persisted as `{"context": …}` JSONB on `workflow_instances`; `null` when the ambient capture at start was empty (no empty-document rows); immutable for the life of the instance — no transition, migration, retry, or repair operation overwrites it |

### Instance carrier and the `WorkflowContextBinder`

At `start`, the engine captures the ambient durable context via the propagator at the `WORKFLOW` boundary and persists it on the new instance row within the start transaction. `start` itself is capture-only — it opens no new durable scope; the ambient it captures *is* the context being persisted, and fork-at-start branch capture continues to read live ambient exactly as it did before this carrier existed.

Every later drive computes an **effective document**: `base.merge(instanceMetadata, MergePolicy.CALLER_WINS)`. The base is authoritative for every namespace it carries; the instance carrier contributes only namespaces the base lacks. An empty base with a non-`null` instance carrier therefore binds the instance context in full. The base is chosen per drive, in priority order: an explicit signal carrier for that transition, else the drive's own persisted carrier (branch-token or timer-row metadata), else the ambient inbound context for caller-invoked and transport-dispatched drives, else the empty document.

**Bind gate (the dark-path guarantee):** when the instance carrier is `null` and the drive carries no explicit signal carrier, the drive binds exactly as it always has — no instance fill, no new scope. An application that registers zero durable namespaces therefore sees every instance carrier persist as `null` and every drive stay bit-for-bit unchanged; there is no separate kill switch, the `null`-skip chain *is* the dark path. An explicit signal carrier always binds regardless of the instance carrier — it is the sender's deliberate handoff, not instance fill.

**Bind-once routing rule:** every drive gets exactly one durable bind, at exactly one seam.

- **Binder rows** — instance-owned single-path drives (`signal` without a branch target, `cancel`, `retry`, `migrate`, and single-path `taskCompleted`/`taskReassigned`/`timerFired`/`timerFiringFailed`/`taskDueFired`/`taskReminderFired` drives with no branch token involved) bind through the internal `WorkflowContextBinder` collaborator (`vertique-workflow-engine`), which implements the gate, base selection, and instance-fill merge above, then installs the effective document via `InboundExecutionContextScope.installDurable(...)` before running the drive.
- **Branch-owned drives** — branch-targeted signals, branch-owned timer fires and task completions, and both branch recovery sweeps — keep their bind on the existing `BranchTransitionEngine`/branch-recovery carrier seam (below), which now receives the merged effective base through an added `effectiveBaseOverride` parameter instead of using raw `token.metadata()` directly. A drive is never bound at both seams.

Instance fill happens before registered `InboundContextInitializer`s run (notably the correlation seeder), so a namespace the instance carrier supplies is never re-seeded — a drive with an absent-from-base correlation namespace and a `null` instance carrier still gets a freshly seeded correlation, matching existing timer-recovery behavior.

**Fork absorption:** a branch forked during a filled drive persists the *effective* (base + instance-fill) document into its token, because fork-time capture reads the bound context holder unchanged by this feature. This intentionally closes the "carrier holds only what was ambient at fork time" gap for every branch forked after instance carriers exist, without any change to how forks capture context.

### Explicit signal carrier

A signal can carry an explicit durable-context carrier on the wire: `WorkflowSignalRequest.metadata` — an optional `@Nullable JsonObject` holding the raw `{"context": …}` shape produced by `DurableMetadata.toCarrier()`. When present, it is authoritative as the base for that transition only (including, for a branch-targeted signal, winning over the branch token's persisted metadata); it is never persisted onto the instance or the branch token. `WorkflowSignalContributor` decodes it via `DurableMetadata.fromCarrier(...)` before calling the metadata-aware `TransactionalWorkflowOperations.signal(..., @Nullable DurableMetadata signalMetadata, TX tx)` overload. That overload's default implementation delegates to the legacy 7-arg overload when the carrier is `null` (behavior unchanged) and fails fast with `UnsupportedOperationException` on a non-`null` carrier unless the implementation overrides it — the carrier is never silently dropped. `WorkflowEngine` overrides it with the real bind.

`DurableMetadata.fromCarrier`/`fromJson` validate carrier shape at decode time and raise `MalformedDurableMetadataException` (extends `ValidationException`) rather than letting a malformed nested value surface as a raw `ClassCastException` in a later merge or read path.

**Security note:** the signal ingress endpoint (`workflow.signals.post`) is internal-relay-only, and an explicit carrier is bound as the authoritative durable-context base for its transition — it MUST NOT be populated from untrusted or end-user input. Only the relay that produced the carrier via `DurableMetadata.toCarrier()` may supply it. Provenance authentication must derive trusted namespaces from the authenticated `SecurityContext` rather than trust a carrier verbatim.

### Command correlation stitching

Command drives — task completion, task reassignment, cancel, retry, migrate — append history with an additive `commandCorrelationId` field, sourced from `ContextValues.current(CorrelationContext.class)` inside the bound drive. This is a causal record of who drove the transition, independent of which namespaces filled the effective document. The field is additive on `TaskCompletedHistoryPayload`, `TaskReassignedHistoryPayload`, `TaskCancelledHistoryPayload`, `RetriedHistoryPayload`, `WorkflowMigratedHistoryPayload`, and the workflow-level cancel history payload — old rows deserialize with `commandCorrelationId = null`.

### Duplicated-context requirement for newly bound drives

**Consumer-visible behavior break, accepted pre-release:** application callers invoking the public `TransactionalWorkflowOperations` tx-variants or `TaskService` mutations from a **non-duplicated** Vert.x context — which succeed today — observe `IllegalStateException` (FR-CTX-157b) once those drives pass the bind gate (non-`null` instance carrier or an explicit signal carrier). The break is data-dependent: it manifests only when the gate passes; `null`-carrier instances never trip it. Every dispatcher entry point in production (event bus, Kafka per-record, cron `@CronJob` callback, service-method invoker) already runs on a duplicated Vert.x context, and `vertx-sql-client` 5.x preserves the caller's duplicate through `pool.withTransaction(tx -> …)`. Tests that enter the engine from a JUnit thread MUST first switch to a duplicated context via `((ContextInternal) vertx.getOrCreateContext()).duplicate().runOnContext(...)` (the same duplicated-context pattern the branch-drive seam below already requires); transport relays that cannot reach a duplicated context MUST use `decodeToDispatchContext(...)` instead.

### Timer piggyback model

Workflow timers are implemented as delayed-job executions. Durable context is captured once at timer-create and written into two locations in the same transaction:

- `workflow_timers.metadata` — recovery-state source of truth; survives delayed-job dead-letter or DELETE
- `job_executions.metadata` — dispatch-time wire format used by `DelayedJobPoller`

`WorkflowTimerSideEffectRecorder` performs the dual write by encoding the captured `DurableMetadata` document into the `{"context": …}` carrier and writing it to both columns. The caller-supplied context document is empty by construction, so no collision with framework-captured namespaces is possible.

Timer fire restores context through the standard delayed-job dispatch pipeline (`DelayedJobPoller.dispatch` decode + receive-side `InboundDispatchScope`). There is no timer-fire-specific binding in `WorkflowTimerFireExecutor`.

Timer recovery (`WorkflowTimerRecoveryService`) copies the `DurableMetadata` document from `workflow_timers.metadata` into the replacement delayed-job via `DelayedJobOptions.premergedMetadata`, which routes through `DelayedJobService.enqueuePremerged` with no re-capture.

### Branch-drive seam

`BranchTransitionEngine.driveBranchTransitions` binds `token.metadata()` once per per-branch drive via `InboundExecutionContextScope.installDurable(metadata, DispatchBoundary.WORKFLOW)`. The bind covers all branch step types: timer-step, signal-step, service-dispatch, and decision. The scope closes on `Future.eventually(...)`.

`driveBranchTransitions` takes an additional `@Nullable DurableMetadata effectiveBaseOverride` parameter. When non-`null`, the drive binds that already-computed effective document instead of raw `token.metadata()` — this is how branch recovery (below) and a branch-targeted explicit signal carrier route their merged base + instance-fill document into the branch carrier seam without a second, competing bind. When `null`, behavior is exactly what it was before instance carriers existed: bind `token.metadata()` with the empty-document fallback.

The substrate helper wraps the authoritative `DurableContextPropagator.bindFrom(...)` — every registered durable namespace absent from `token.metadata()` is cleared for the drive's scope lifetime. This prevents the drive's ambient context (e.g. a service handler that triggered the workflow signal) from leaking into a subsequent durable-context encode at a downstream seam — specifically the `WorkflowTimerSideEffectRecorder` dual-write. An empty `DurableMetadata` document still triggers the bind; it clears whatever ambient durable namespaces are live on the call-site context. After the durable bind, registered `InboundContextInitializer`s run (notably the correlation seeder), so branches whose metadata carries no encoded `CorrelationContext` still get one bound for the drive (FR-COR-125).

Production reaches this on a duplicated context: every dispatcher (event bus, Kafka per-record, cron `@CronJob` callback, service-method invoker) is on a duplicated Vert.x context, and `vertx-sql-client` 5.x preserves the caller's duplicate through `pool.withTransaction(tx -> …)`. Per FR-CTX-157b, `installDurable` propagates the strict `bindFrom` invariant: `IllegalStateException` on a non-duplicated Vert.x context (no-op only when there is no Vert.x context at all). Tests that enter the engine from a JUnit thread MUST first switch to a duplicated context via `((ContextInternal) vertx.getOrCreateContext()).duplicate().runOnContext(...)`; transport relays that cannot reach a duplicated context MUST use `decodeToDispatchContext(...)` instead.

### Branch-recovery seam

`PgWorkflowBranchRecoveryService.withBranchDurableBound` wraps every per-branch advance in the recovery sweep — both the due-branch path (`processDue`) and the stale-RUNNING path (`processStale`). Branches are processed sequentially so branch N's scope closes before N+1's opens.

Recovery loads the owning instance before the durable scope opens (never inside it) and computes `token.metadata().merge(instance.metadata(), MergePolicy.CALLER_WINS)` — a no-op merge when the instance carrier is `null` — passing that effective document into `driveBranchTransitions`'s `effectiveBaseOverride`. This means a branch parked with a degraded carrier (a namespace hole from a pre-feature fork, or a transport relay that lost a namespace) picks up the missing namespace(s) from the instance carrier the next time recovery drives it, while the carrier itself remains authoritative for every namespace it already holds.

### Branch metadata immutability

Branch metadata is set at fork time and is immutable thereafter. State transitions — retry-scheduled and terminal failure (FailNode-driven or recovery-driven) — preserve the original `token.metadata()` unchanged. No subsequent engine operation overwrites it.

---

## Operational Principles

- Always provide idempotency or dedup keys where APIs require them.
- Treat workflow state as orchestration state, not a shadow copy of domain records.
- Use subjects to link workflows to domain objects.
- Keep downstream calls behind side-effect recorders and transactional outbox handoff.
- Install durable timer infrastructure for any workflow that waits on time.
- Keep in-flight instances pinned to their started version unless explicitly migrated.
- Put authorization in the application layer. Workflow records actors and decisions; it does not decide access policy.

---

## Where To Go Next

- Start with [workflow-core.md](../vertique-workflow/vertique-workflow-core/src/main/resources/META-INF/vertique/module.md) for concepts and public APIs.
- Add [workflow-postgresql.md](../vertique-workflow/vertique-workflow-postgresql/src/main/resources/META-INF/vertique/module.md) for durable execution.
- Add [workflow-services.md](../vertique-workflow/vertique-workflow-services/src/main/resources/META-INF/vertique/module.md) for service dispatch.
- Add [workflow-delayed.md](../vertique-workflow/vertique-workflow-delayed/src/main/resources/META-INF/vertique/module.md) for timers and scheduled transitions.
- Add [workflow-tasks.md](../vertique-workflow/vertique-workflow-tasks/src/main/resources/META-INF/vertique/module.md) for human task APIs.
- Add [workflow-events.md](../vertique-workflow/vertique-workflow-events/src/main/resources/META-INF/vertique/module.md) for external event streams.
- Add [workflow-definition.md](../vertique-workflow/vertique-workflow-definition/src/main/resources/META-INF/vertique/module.md) for YAML/JSON authoring.

---
