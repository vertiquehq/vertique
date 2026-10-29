<!--
SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
SPDX-License-Identifier: EUPL-1.2
-->

# Workflow Engine Module

> **Status:** Stable
> **Package:** `dev.vertique.workflow.engine`
> **Artifact:** `vertique-workflow-engine`
> **Depends on:** workflow-core, db-core, context, deploy, vertx-sql-client

`vertique-workflow-engine` is the portable, dialect-neutral workflow orchestration runtime. It holds the
execution kernel — the durable saga state machine, fork/join coordinator, task and timer lifecycle
services, signal delivery, migration executor, and history recorder — decoupled from any specific SQL
dialect. Persistence is injected at application composition time through six `<TX>`-generic repository
SPIs and a transaction-runner SPI.

The module is the seam for DB portability: a second Vert.x SQL backend needs only dialect-specific
implementations of those SPIs; the orchestration logic is reused verbatim. A maven-enforcer
`bannedDependencies` rule in the module's `pom.xml` makes this boundary machine-checked — the build
fails if `io.vertx:vertx-pg-client`, `vertique-db-postgresql`, or `vertique-workflow-postgresql` appear
in the engine's dependency tree.

---

## When To Use It

Install `vertique-workflow-engine` indirectly — it is `include`d by every workflow persistence adapter.
Applications install only the adapter module (`vertique-workflow-postgresql`) and the engine comes with
it. Access the engine through the interfaces `WorkflowOperations` and `TransactionalWorkflowOperations<SqlClient>`.

Implement the SPIs in this module when building a new Vert.x SQL workflow persistence backend.

---

## Core Concepts

### Three-Layer Hierarchy

```
vertique-workflow-core       — SQL-free DSL, registry, operation interfaces, side-effect SPI
        ↑
vertique-workflow-engine     — portable orchestration runtime, <TX>-generic SPIs
        ↑
vertique-workflow-postgresql — PostgreSQL adapter: SPI impls, schema, Pg runner
```

The engine depends on `io.vertx:vertx-sql-client` (the abstract Vert.x SQL API JAR) because the six
repository SPIs and `WorkflowTransactionRunner` are parameterized with `SqlClient` as the concrete
transaction-handle type. This is an honest declaration of scope — the engine targets the Vert.x SQL
family, not arbitrary persistence stores — but carries no Pg-specific APIs.

### Package-Private Internals, Public Boundary

`WorkflowEngine` and its fifteen collaborators are all package-private within
`dev.vertique.workflow.engine`. Dagger generates binding code inside that package and wires the engine
through `WorkflowEngineModule` using `@Binds`. Code outside the package (adapter modules, application
code) never references the concrete class — it uses `WorkflowEngineHandle`, `WorkflowEngineFactory`,
or the four public ops/callback interfaces.

### Record-Time Payload Checks

The engine validates the encoded JSON of every recorded signal payload and explicit signal carrier, and of every
task completion's payload, before it takes the execution lock. Its byte limit is
`workflow.recording.maxPayloadBytes` (default `262144`, minimum `1024`); its fixed depth limit is 64,
counting every object and array as one level. Values exactly at either limit pass. Size is measured
over UTF-8 JSON bytes, including escaping and structural characters.

The validator walks containers iteratively before encoding, so excessively nested JSON is refused
without a stack overflow, and it counts bytes and nesting while it encodes and stops as soon as a bound is crossed, so an
oversized value is never fully encoded in memory. The bounds perform no I/O, locking or transaction work.
Exceeding either bound throws `WorkflowInvalidPayloadException` naming `maxPayloadBytes` or depth 64;
messages, violation details and causes expose no submitted values. A recorded signal's refusal also names the instance and
the signal.

A task completion's payload is also validated against the JSON Schema that the task's decision snapshot stores for the
requested decision, still before the lock and without the plan. A violation throws `WorkflowInvalidPayloadException` with
value-free violation details. The schema is checked first: one with a non-local `$ref` (anything not starting with `#`,
at any schema position) is refused before it is compiled, so no reference is ever fetched from a file or the network.
Compiled schemas are cached by the SHA-256 of the schema's canonical (key-sorted) JSON, so equal schemas share an entry
whatever their key order; the cache holds at most `workflow.recording.schemaCacheMaxEntries` entries (default `256`,
minimum `1`) and evicts the oldest-used first. Pattern keywords run through the framework's pattern-input guard, which
bounds the strings they inspect. A schema that cannot be compiled is not the caller's fault: validation is skipped for
it with a warning and the executor validates the completion against the plan when it applies it. A decision the snapshot
does not offer, or one with no stored schema (a task created by an earlier version), is left to the checks under the lock
and to the executor.

### Recorded Task Completions

`TransactionalTaskCallbacks.taskCompleted` (reached by `TaskService.complete`) records a completion and returns; it does not
apply it. In the caller's transaction it:

1. returns the earlier result of a committed duplicate (the same idempotency key and fingerprint) before any other check;
2. reads the task row without a lock and checks the payload's bounds and schema (above); a refusal writes nothing and takes no lock;
3. takes the instance's execution lock, then looks for a duplicate again (a key with another fingerprint is a
   `WorkflowIdempotencyConflictException`) and locks the task row: the task must still be `OPEN`
   (`WorkflowTaskNotWaitingException` otherwise), a supplied `expectedAssignment` must still match, the reviewed subject
   version must match when the row records that the task requires version stability, and the decision must be in the
   snapshot (the existing unknown-decision refusal);
4. claims the idempotency key, inserts a `COMPLETION` command whose target holds the decision, the reviewed subject
   version and the completing actor, and moves the task `OPEN` to `COMPLETING` through `TaskReservations.markCompleting`,
   storing the command id as the reservation owner.

Recording loads no plan and no payload class. A task created before decision schemas and the stability column were stored
skips the checks that need them; the executor makes them from the plan.

The executor applies the command in a segment, under a savepoint: the task must still be `COMPLETING` under this command
(otherwise the command is `STALE`), the instance must wait for the task, and the decision is applied exactly as the
plan describes (version stability from the plan, payload coercion and constraint checks, the due-date and reminder timers
cancelled, the task `COMPLETED`, the decision applicator, the instance advanced to the decision's next step). A
deterministic failure (an invalid payload, a decision applicator that throws, a stale reviewed version found from the
plan) or a step-limit overrun rolls back to the savepoint: the command is `REJECTED` with a sanitized reason that starts
with `DETERMINISTIC_FAILURE` or `STEP_LIMIT` (never exception text or payload values), the task is reopened
(`COMPLETING` to `OPEN`, owner cleared) and the instance is unchanged. A completion for an instance that has `FAILED` is
rejected and the task reopened; for an instance that has ended it is rejected and the task cancelled. A `COMPLETING` task
refuses a second completion and a reassignment with `WorkflowTaskNotWaitingException`. The task's due-date and reminder timers are held
back while the task is `COMPLETING` (see Timers as commands), so a due date that passes meanwhile never overtakes the completion.

### Timers as commands

A segment that enters a timed wait records the wait's timer itself: a timer node, a signal timeout, a task's due date and a task's
reminder, on an instance or on a branch. Each timer is a `workflow_timers` row linked by `command_id` and a `TIMER` command in the
instance's queue, inserted together under the execution lock through `WorkflowCommandRepository.recordTimer`. No timer is a delayed
job and no engine path records a timer as a side-effect intent. The command's timer kind follows the timer's purpose: `FIRE` (timer node),
`TIMEOUT` (signal timeout), `DUE` (due date) and `REMINDER` (reminder). Its `fire_at` is read once from the database clock when the
command is recorded and never changes; `available_at` equals it at the insert. A delay in the plan counts on the database
clock; an absolute instant already in the past is taken as the time of recording. A segment that leaves a wait cancels the timer and
its command in one statement (`TimerStore.markCancelled`), so a completion that is applied cancels its task's due date and reminders
with their commands. A `TIMEOUT` command stores the wait it belongs to as its target: the step, `forkStepId` and `branchId` of a
branch wait, and `signalNames`, the signals the wait accepts.

The executor wakes for the earliest timer and runs it as a segment; the command is `COMPLETE` when the timer fired. What fires:

- `FIRE` advances the instance or branch past the timer node and appends `TIMER_FIRED`;
- `TIMEOUT` applies the wait's timeout branch and appends `TIMEOUT`;
- `DUE` expires the task, cancels its reminders, applies the due-date branch and appends `TASK_EXPIRED`;
- `REMINDER` increments the task's reminder counter, appends `TASK_REMINDER_FIRED`, emits the reminder event and, for a recurring
  reminder, records the next reminder command anchored at the fire time of the one that fired (the next fire is the previous
  scheduled fire plus the interval, not the time the segment ran).

A timer whose wait is gone settles `STALE` (history `COMMAND_STALE`, with a reason that names no value) and changes nothing else: the
timer row is not scheduled any more, the instance moved on, the task is not `OPEN`, or the instance has failed or ended. A deterministic
failure of the segment fails the command and its instance, and closes the timer row as `FAILED`; a retry offers it again as
described under retry. A node that lacks the instance's pinned plan defers the command like any
other command and never fails the instance.

**Ordering.** A timer before its `fire_at` blocks no later command. A timeout is held back by an earlier operation for its wait: a
`SIGNAL` whose name the wait accepts and that carries the wait's branch address, or the `COMPLETION` of the wait's task, recorded before
the timeout's `fire_at` and still pending, deferred or claimed (a dead-lettered or parked signal holds nothing back). A signal recorded
at or after the timeout's `fire_at` is never applied at that wait while the timeout is pending, deferred, claimed or dead-lettered: it is
parked. A due date or reminder is held back while its task is `COMPLETING`, whatever its fire time, and keeps its position and fire time;
a rejected completion reopens the task and the due date then runs with the sequence and fire time it had.

**Context.** The timer row's `metadata` holds the durable context of the recording drive, signed for the `workflow-timer` carrier and
without the identity-snapshot namespace. A `TIMER` segment binds that metadata as an executor drive: the identity-snapshot namespace is
stripped from the metadata, from the command's own context and from the instance carrier, so a timer written by an earlier release that
carries an identity snapshot binds no security context. The command itself stores no context. A branch timer binds the branch token's
carrier, as every branch drive does.

There is no public timer callback: a timer fires only as its `TIMER` command, and the same holds for a task's due date and reminders.
A branch failure inside a segment is handled by the segment's outcome and the command's retry, so there is no branch recovery sweep.

### Who asked for a command

Every recorded command carries who asked for it: `actor_kind`, `actor_id`, `actor_reason`, `on_behalf_of` and
`attribution_status`. The engine takes them once, when the command is recorded and in the recording caller's transaction, from the
caller's current `SecurityContext` (read through the `ContextHolder`, which defaults to `DefaultContextHolder`) and the
`WorkflowActorMapper` (default `DefaultWorkflowActorMapper`). Both can be replaced on `WorkflowEngineParts.Builder`
(`contextHolder`, `actorMapper`); a Dagger graph supplies them through its `ContextHolder` and `WorkflowActorMapper` bindings.

| Caller | Logged as | `attribution_status` |
|---|---|---|
| A user | `USER` with the user id | `VERIFIED` |
| A service principal | `SERVICE` with the service id | `VERIFIED` |
| The system | `SYSTEM` with the reason, and no id | `VERIFIED` |
| No security context, or an anonymous identity | `NONE`, with no id | `ABSENT` |
| Any of the first three, in a context that carries the security module's snapshot-degradation marker | as above | `DEGRADED` |

- **Never from a payload.** The actor is never taken from a start or signal payload, a header, a carrier or a request object; a
  payload that names an actor changes nothing. `completedBy` and `reassignedBy` on the task commands stay application data.
- **`NONE` is decided first.** An absent or anonymous identity is logged as `NONE` before the mapper runs, so the mapper never sees an
  anonymous identity. An exception the mapper throws refuses the recording and reaches the caller unchanged; nothing is written.
- **On behalf of.** `on_behalf_of` holds the identity's subject when its id differs from the actor's id, for example the user a
  service acts for. It holds a principal id only, never a claim or a token.
- **Bounded.** Ids and reasons are logged without control characters, without the line and paragraph separators U+2028 and
  U+2029 and without Unicode format characters (bidirectional overrides, zero-width characters), then cut at 255 characters. An id
  that nothing of is left after that is logged as `[unprintable]`, so a verified user or service is never recorded without an id.
- **Timers.** A timer command is logged as `SYSTEM` with the reason `timer`, whoever's segment recorded it.
- **Observers.** A command recorded with `NONE` is reported to the executor observers as `ActorAbsent(kind)` with the command kind
  (`START`, `SIGNAL`, `COMPLETION`, `RETRY` or `MIGRATION`) when the recording call succeeds. A caller that owns the transaction
  commits later, so the event can precede a rollback it does not know of; an observer failure never changes the recording.
- **The executor reads the command, not the caller.** A segment runs under no security context. It copies the actor of the command it
  executes onto every history entry it writes, and an entry that is about another command (a parked signal it applies, a command it
  settles at the end of the instance) carries the actor that recorded that command, and so do the attribution headers of the
  intents a parked signal's handler writes: while a segment applies a parked signal, its intents are attributed to the signal's
  recorder and the executing command's attribution resumes afterwards. A command recorded before actors were
  logged is treated as recorded by `NONE`.
- **Cancellation and reassignment.** `cancel` logs the cancellation entry and every `COMMAND_CANCELLED` entry with the canceller, and
  `taskReassigned` logs the `TASK_REASSIGNED` entry with the reassigner, both taken from the calling identity. The cancelled
  commands keep the actor that recorded them, and so does every entry already written about them. A `COMMAND_CANCELLED` entry that
  a segment or a claim writes for a command that meets the cancelled instance is logged with the canceller too, taken from the
  instance's `CANCELLED` entry.
- **Attribution headers on dispatched and published intents.** A service dispatch a segment writes reaches its recorder with the
  headers `x-workflow-actor-kind`, `x-workflow-actor-id`, `x-workflow-actor-reason`, `x-workflow-on-behalf-of` and
  `x-workflow-attribution`, taken from the actor of the command the segment executes (the kind and the status are the enum names;
  absent values are omitted). Any other intent, such as a workflow event that reaches brokers and webhooks, gets only
  `x-workflow-actor-kind` and `x-workflow-attribution`, so no subject identifier leaves the deployment. Before they are added, `RecorderRouter` removes every application-set header
  whose name starts with `x-workflow-` in any letter case, so an application cannot set or forge them; other headers are kept. The
  headers are unauthenticated audit data: no relay, contributor or recording path reads them, binds an identity from them or derives an
  actor from them, including a signal relayed back into a workflow.
- **A dispatched operation runs as the relay.** A segment binds no identity snapshot, so the outbox relay delivers a service operation
  that a segment dispatches under its own identity: `SYSTEM` with the relay's reason by default, or the service a bound
  `ServiceIdentityResolver` names. It does not run as the user who recorded the command. To have the downstream operation check a
  dedicated principal, bind a `ServiceIdentityResolver` that names a relay service and grant that service the permissions the operation
  checks; never re-derive an identity from the attribution headers.
- **Startup warnings.** When the executor deployment is built it logs one warning for each of `identity.snapshot.carriageRequirements`
  entries `outbox-relay` and `workflow` that is `REQUIRED` (the relay treats the intents of a segment as carrying no identity snapshot;
  executor drives bind none, so workflow carriage cannot be met on them). `WorkflowEngineModule` reads the two entries through the
  typed configuration binding; an absent section or entry is not required, and a graph without a configuration binding warns about
  nothing. A recording-only node deploys no executor and does not warn. A factory-built engine reads no configuration and does not warn.
- **Relayed signals.** A signal that the outbox relay delivers to `workflow.signals.post` is recorded under the identity the relay
  rebuilt for the delivery: `SYSTEM` with the outbox row's event type as the reason, or the service a bound `ServiceIdentityResolver`
  names, acting for the user who wrote the row. The security module's degradation policy decides before the signal is recorded: a
  snapshot that cannot be verified refuses the delivery under the default policy, and under `CONTINUE_WITHOUT_IDENTITY` the signal is
  recorded as `DEGRADED` and acts for no one. Without identity carriage the signal is recorded as `NONE`.

### The decision log

Every segment logs what the engine decided, in the segment's transaction and under the instance's execution lock, so a rolled-back
savepoint (a rejected parked signal, completion or migration) takes its entries with it and the history has no gap in its sequence.
Entries are append-only and carry `entry_version = 2`.

| Entry | Written when | Columns and payload |
|---|---|---|
| `DECISION` | the engine takes a step that changes the instance row: a decision node, a service dispatch (`intent`), a wait entered (`wait-signal`, `wait-timer`, `wait-task`), a signal applied (`signal-applied`), a task decision or expiry (`task-decided`, `task-expired`), a timer fire or timeout (`timer-fired`, `timeout`), a fork dispatched or a join decided (`fork-dispatched`, `joined`, `join-failed`), completion, failure, compensation and a retry | the owning command, `step_id`, `outcome` (the chosen step id for a decision node, otherwise one of the words above; at most 40 characters), the definition scope, id and version, the plan hash, `state_version_before` and `state_version_after`, `executor_id`, the actor of the command and `patch_json` |
| `STATE_BASELINE` | the first segment of a start, a migration, the first claim of an instance that existed before 0.4.0, and after a change too large to log as a patch | the full state at `payload_json.state` |

- **Patch.** `patch_json` is an RFC 6902 patch of the state change, computed from the canonical JSON of the state before and after (never from
  in-memory objects) by `io.github.vishwakarma:zjsonpatch` with `move` and `copy` omitted, so it holds only `add`, `remove` and `replace`;
  a `remove` carries no value and no operation carries an old value. A decision that did not change the state has the patch `[]`.
  Applying an instance's patches in order from its latest full-state entry reproduces its stored state, except at redacted pointers.
  Numbers are kept exactly: the state is read once with decimals as `BigDecimal`, so a decimal that changes beyond the precision of a
  `double` is a change that the patch records and a baseline writes back unrounded. `1` becoming `1.0` is a change (an integer and a
  decimal are different values to the log); `1.0` becoming `1.00` is not.
- **State version.** `state_version_before` is the instance version the decision read; `state_version_after` equals it when the state did
  not change, and is the new instance version when it did.
- **Not state patches.** Branch tokens, join rows and the open signals are not part of the instance state: forks and joins appear as
  decisions with the outcomes above, and their rows are not patched. A parked, rejected, stale or cancelled command, and the cancellation of an instance, are logged by their own
  receipt entries (`SIGNAL_PARKED`, `COMMAND_REJECTED`, `COMMAND_STALE`, `COMMAND_CANCELLED`, `CANCELLED`), not by a `DECISION`.
- **Size.** Before diffing, the canonical JSON of the (redacted) states before and after a decision is measured; when either is over
  65,536 bytes the library is not called, and the `DECISION` entry has no patch (SQL `NULL`) and a `STATE_BASELINE` with the state after it
  follows at once. A patch whose encoding would be over 65,536 bytes is replaced the same way. A decision that left the state
  unchanged logs `[]` whatever the state's size. Storage grows with the changed fields of each decision, plus one full state at the first
  segment, at every migration and after every oversized change; plan retention accordingly.
- **Instances from before 0.4.0.** The upgrade conversion clears `workflow_instances.baseline_logged`; the first segment that claims such
  an instance (any command except `START`) consumes the mark and logs one `STATE_BASELINE` with the state as it was, before its own
  decisions. An instance created by 0.4.0 never has the mark cleared.
- **Command payloads.** The `COMMAND_RECEIVED` entry of a `START` and of a `SIGNAL` carries a copy of the command's payload at
  `payload_json.payload`; `SIGNAL_RECEIVED` carries the applied signal's payload as before. A task completion's payload is not copied into
  the history, and no entry carries a document body. `workflow_commands.payload_json` itself is never redacted.
- **Redaction.** When the application binds a `WorkflowHistoryRedactor` (see the core module), the values at its pointers for the
  instance's definition version become `"[redacted]"` in the logged payloads, in the states a patch is diffed from, and in every full state;
  so no logged form holds them. Across a migration the source version's pointers cover the migration command and everything before it,
  the target version's the migrated baseline and everything after, and both the migration's own patch (which is written before its
  baseline). A reason written to history (a command outcome, the message of a failed instance, a cancellation reason) also has any value
  found at a redacted pointer of the command's payload or the instance's state, rendered as text, replaced. A redactor that throws or an
  invalid pointer fails the segment as a transient failure. The redactor is asked at most once per definition version in a segment.
- **Actor.** Each entry carries the actor of the command it belongs to (see 'Who asked for a command').
- **Command and definition.** The attribution that closes a segment also gives every entry that names no command the command the
  segment executes, and every entry that records no definition the definition the instance is pinned to; an entry that already
  records a value keeps it. A segment that migrates the instance gives the pin it began with only to the entries it writes before the
  repin, its `COMMAND_RECEIVED` and `COMMAND_REJECTED` entries; the entries after it log the target themselves. The history service
  therefore shows `commandId` and `definitionKey` on more rows than before.

### Announcing history to listeners

The engine tells the `WorkflowHistoryListener`s of `vertique-workflow-core` what a transaction wrote to an instance's history,
after the transaction committed, and which caller action failed. The seams below are INTERNAL: applications contribute listeners
and never call them, and they sit outside this module's maturity promise.

- **`WorkflowHistoryEventPublisher`** (`dev.vertique.workflow.engine.history`) wraps the work that runs in a transaction. Every public
  engine method that records history (`start`, the `signal` forms, `cancel`, `retry`, both `migrate` forms, task completion and
  reassignment), the executor segment, and the transactions that settle, dead-letter, defer or fail a command run inside it.
  `HistoryEventMapper` turns the committed entries into events, `CapturedActor` carries the caller's actor from the calling thread to
  the failure report, and `DefinitionLifecycleEvents` does the same for registry changes.
- **How a commit is found.** Inside the transaction the publisher reads the instance's highest history sequence before the work and
  after it. When the transaction commits (`TransactionCompletion.onSettled` of `vertique-db-core`), it reads the entries of that range
  on a pooled connection with `WorkflowHistoryRepository.listRange`, maps them and hands one `WorkflowHistoryCommit` per instance to
  every listener. Nothing is read when the work wrote nothing.
- **Delivery.** At most once, after the commit, one listener after another, on the thread that completes the read. A listener that
  throws is logged and the others still run. A read, mapping or settlement failure is logged and never reaches the action, whose
  result is always returned unchanged.
- **Failures.** An action that fails, whose transaction does not commit, or whose commit is not confirmed is reported once to
  `onHistoryFailed`, through whichever signal arrives first, with an `attemptId` minted per attempt and the actor captured on the
  calling thread. The failure carries the simple class name only.
- **No listener, no overhead.** With no listener bound the wrappers only call the work: no statement runs and no actor is captured.
  The Dagger graph binds the publisher from the `Set<WorkflowHistoryListener>` multibinding; a factory-built engine uses
  `WorkflowEngineParts.Builder.historyPublisher`, whose default announces nothing, and the lifecycle events of the next point exist only
  in a Dagger graph.
- **The execution lock moves earlier.** With a listener bound, an action on an existing instance takes the instance's execution lock
  before it reads the starting sequence, so no other transaction can write entries inside its range; the work takes the lock again at
  no cost. A call that would have returned without writing, such as a duplicate signal, therefore waits for the lock (at most
  `workflow.recording.lockTimeoutMs`, then a retryable `WorkflowPersistenceException`). A `start` takes the new instance's lock as
  before.
- **Nested calls.** A public method reached from inside another wrapped site of the same transaction for the same instance only runs
  its work; the outer call announces the entries, once. A start that replays an existing instance announces nothing.
- **Needs a watchable transaction.** The handle must be a `SqlConnection` with an open transaction (the connection `transaction()`
  reports it). Otherwise the call succeeds, the events are not announced and an error is logged.
- **Entries.** A `STATE_BASELINE` entry yields no event. A stored entry type this node does not know (written by a newer node) is left
  out of the range and logged. An entry type with no variant of its own, and a task entry whose payload cannot be read, map to
  `WorkflowHistoryEvent.Other`. The mapper never throws.
- **Registry events.** `DefaultWorkflowDefinitionLifecycle` wraps every operation: it captures the actor on the calling thread before
  the operation is queued, mints a change id, and reports the change after the future completes, or the failure with the typed
  `Reason`. An accepted repeat is reported like any change. `WorkflowDefinitionRegistryBootstrapStep` reports the first insert of a
  version after its transaction committed, as a `PUBLISH` by the `SYSTEM` actor with the reason `bootstrap`, followed by an `ACTIVATE`
  of the default scope when the store answered `ReconcileOutcome.INSERTED_AND_ACTIVATED`. The outcomes `UNCHANGED` and `REHASHED`
  report nothing.

### Transaction Ownership

The engine performs no transaction management of its own. Public operation methods (`start`, `signal`,
`cancel`, `retry`, `migrate`, `query`) open a transaction through `WorkflowTransactionRunner.inTransaction`
and pass the resulting `SqlClient` handle into their transactional variants. Callers that already own a
transaction use `TransactionalWorkflowOperations<SqlClient>` directly.

**Recorded starts.** `start` records and returns; it does not run the workflow. In the caller's transaction, under the new
instance's execution lock, it inserts the instance as `PENDING` (its pin, business key and durable carrier; no state,
current step or subject), the instance's command queue row and a `START` command holding the start payload, and returns
the new instance id. The payload is still validated and bound to its declared type in the caller's transaction, as before;
the initial state, the subject reference and every step run later, in the executor's first segment of the instance, which
also emits `WORKFLOW_STARTED`. A caller that needs the instance running awaits the probe (`executionProbe().awaitIdle`)
before it reads the instance or signals it. A rolled-back caller leaves no instance, command, queue row or dedup row. The
carrier captured from the caller's workflow-boundary context drops the identity snapshot namespace before it is stored, so no
later drive can bind an identity from the instance. A `PENDING` instance counts as a live pin: retirement of its version is
refused with reason `PINNED_INSTANCES`. Timers fire as `TIMER` commands in the executor (see Timers as commands), and `retry` and `migrate` record commands too (see Recorded retry and migration); `cancel` stays synchronous and task reassignment still runs inline in the caller's transaction (see Cancel and the outcome table).

**Execution lock.** Every transaction that changes an instance takes that instance's execution lock before its first
workflow change, through `WorkflowCommandRepository.lockInstance`, so one transaction at a time changes an instance and
appends to its history. This covers a new instance's `start` (the lock of the new id, after the lock-free checks and
before the dedup claim; a replay of a start that already exists takes none), the three `signal` forms, `cancel`, `retry`,
both `migrate` forms, and the two task callbacks (`taskCompleted` and `taskReassigned` take it for the instance of the task
they look up). A caller waits for a busy instance for
at most `workflow.recording.lockTimeoutMs` (default 5000); a timeout or a deadlock fails the call with
`WorkflowPersistenceException` whose `retryable()` is true and rolls the caller's transaction back, so retry the whole
transaction. The lock lasts until the transaction ends, and taking it again inside the same transaction costs nothing.

**Recorded signals.** Every `signal` overload (instance- and branch-addressed, with or without an explicit carrier) records a
`SIGNAL` command in the caller's transaction and returns once it is recorded; it does not apply the signal. An executor applies
it afterwards, in a segment of its own, in queue order behind the commands recorded before it, so a signal sent right after a
start waits for the start's segment instead of failing as not waiting. A caller that needs the effect awaits the probe
(`executionProbe().awaitIdle`). The call checks, in this order and without the execution lock until the bounds have passed:

1. a committed duplicate (same instance, branch address and dedup key) returns success and reads nothing else;
2. the instance exists (`WorkflowInstanceNotFoundException`) and has not ended (`WorkflowSignalRejectedException`);
3. a caller that names a contract (`signal(id, contractName, ...)`) matches the contract of the pinned definition
   (`WorkflowDefinitionContractMismatchException`); the contract is read from the node's plan or, without one, from the pinned
   definition row, so recording needs no plan on this node;
4. the pinned definition row declares the name for the addressed wait (`WorkflowSignalRejectedException` naming the instance,
   the signal and the version). A row that stores no declaration admits every name and the executor rejects an undeclared one;
5. the payload and the encoded explicit carrier meet the size and depth bounds (`WorkflowInvalidPayloadException`).

The call then takes the execution lock, repeats the duplicate, instance and name checks on the locked read, raises the instance's
open-signal count with one conditional update against `workflow.recording.maxOpenSignalsPerInstance` (default 100; zero updated
rows fails with `WorkflowSignalLimitException`, which holds under any isolation level), claims the dedup key and inserts the
command. A refusal before the claim writes nothing, so a caller that catches it can commit other work in the same transaction and
the refused key stays free. The explicit carrier is stored as the command's explicit carrier after it is sanitized and stripped of
the identity-snapshot namespace. The durable context the recording caller has bound is stored with every recorded command (the start
included) as its recorded context, also without the identity-snapshot namespace; the executor binds that recorded context when it runs
the operation, as described under `WorkflowContextBinder`.

**Executing a signal.** The executor applies a claimed `SIGNAL` under a savepoint:

- the signal is applied and the command is `COMPLETE` when the instance waits for it (an instance-addressed signal at a
  `WAITING` signal wait of that name, a branch-addressed one at a waiting branch) and no timeout of that wait was fired before
  the signal was recorded;
- the command is `PARKED` when the instance does not await it, is `FAILED`, or the signal is deadline-blocked there; a parked
  command keeps its place and its slot of the open-signal count, blocks nothing, and spends no attempt;
- the command is `REJECTED` when the pinned plan never waits for the name, when the instance had ended, when the application fails
  deterministically (an invalid payload, or a callback or definition failure), or when it runs more nodes than
  `workflow.executor.maxStepsPerSegment` (reason `STEP_LIMIT`). The savepoint is rolled back, so the instance, its version and its
  wait are unchanged;
- a branch-addressed signal for a branch that was superseded or ended, or for an ended instance, is `STALE`.

Every reason written to the command's `last_error` and to history is a reason code and framework-composed text naming the signal;
it never contains payload values, exception text or class names. History records `COMMAND_RECEIVED`, `SIGNAL_RECEIVED` and
`COMMAND_COMPLETED` for an applied signal, `SIGNAL_PARKED` for a parked one, `COMMAND_REJECTED` or `COMMAND_STALE` with the reason,
and `SIGNAL_APPLIED` for a signal that is applied after it was parked.

**Recorded retry and migration.** `retry` and `migrate` record a command in the caller's transaction, under the instance's execution
lock, and return once it is recorded; they run no workflow code. An executor runs the command afterwards, in queue order, in a
segment of its own, so a caller that needs the effect awaits the probe (`executionProbe().awaitIdle`).

- `retry` records a `RETRY` command. It is refused with `WorkflowSignalRejectedException` (naming the instance and its status) when the
  instance has ended, or is neither `FAILED` nor holds a dead-lettered command; the recording writes nothing then. `retry` is the single
  recovery operation. Its segment initializes an instance whose start failed before initialization from the start payload stored with
  the `START` command (a dead-lettered `START` becomes `SUPERSEDED`, so initialization and its side-effect intents commit once; a `START`
  settled `FAILED` stays so); it drives a `FAILED` instance that has state again from its current step, except that an instance that
  failed while it waited returns to that wait: its wait is not entered again, so no second timer, task or fork is recorded, and each
  timer whose command failed (or was settled stale because the instance had failed) gets one new `TIMER` command with the timer's
  original fire time, which fires at once when it is due and runs the body again; a signal recorded after that fire time therefore stays
  parked at that wait; after either, it applies the
  parked signals the reached wait accepts in the same segment (so a dead-lettered timeout still holds its deadline during the drive); then
  it offers every `DEAD` command of the instance again, `PENDING` with `attempts` reset to zero and the same position in the queue, so
  that they run next in sequence order (a re-offered completion keeps its task `COMPLETING`; a re-offered signal counts again against
  `workflow.recording.maxOpenSignalsPerInstance`, without the limit check, because the signal was counted when recorded and released
  when it was dead-lettered). If the drive ended the instance, the `DEAD` commands are rejected instead. A retry that finds the
  instance neither `FAILED` nor holding a dead-lettered command is `STALE` (`COMMAND_STALE`, reason `NOTHING_TO_RECOVER`). A `retry`
  that is called while another retry of the instance is still pending, deferred or claimed succeeds and records nothing. A deterministic failure or a step-limit overrun of the drive fails the command and
  leaves the instance `FAILED`, like a start.
  A `RETRY` command that names a fork step and a branch (recorded by the 0.4.0 upgrade for a branch whose retry was scheduled) is not a
  recovery of the instance: it drives that branch once from its current step, and is `STALE` (reason `BRANCH_ENDED`) when the branch is no
  longer running or its join has already decided.
- `migrate` records a `MIGRATION` command holding the target version and, when named, the target scope. It is refused when the
  instance has ended (`WorkflowMigrationIllegalStateException` naming the status), when the named target scope is none of the
  instance's definition scope, its start scope and the default scope (`WorkflowMigrationIllegalStateException`, "target scope not
  admitted"; nothing is stored and no plan of that scope is built) or when a migration to another target is still
  pending, deferred or claimed (the same exception); a repeat of the pending migration succeeds and records nothing. It is refused, too,
  with `UnsupportedOperationException` when no
  `WorkflowMigrationRegistry` is bound. Before it claims the command the executor makes the source and the target plan resident; a
  node that holds either plan not defers the command (`DEFERRED`, no attempt spent) for a node that does. In the segment, an instance
  pinned to another plan than the one made resident releases the claim, and the rest runs under a savepoint: a failed instance, an
  instance with active branches, a target outside the instance's start and default scopes or marked failing, a target of another
  contract or with an incompatible state class, a target whose row is not `AVAILABLE` (deprecated or retired, whatever plan this node still
  holds), a missing handler, a handler whose state types are not the state
  types of the two definitions, a failing handler and a refused pin admission all roll back to the savepoint and settle the command
  `REJECTED` with a reason code `MIGRATION_REJECTED` and fixed framework text (never the handler's text, a state value or a class
  name); a run of more than `maxStepsPerSegment` nodes in the handler's re-drive is `REJECTED` with reason `STEP_LIMIT`. The instance,
  its pin and its state are unchanged by a rejection. An applied migration re-pins the instance, drives it in the target plan, appends
  `WORKFLOW_MIGRATED`, rejects the parked signals the target does not declare and applies the rest at the wait the instance reached. The re-pin waits for the
  shared definition lock under a 30-second `lock_timeout`; when that wait is in flight at the segment deadline, the rollback queues
  behind it, so the instance's execution lock can be released up to 30 seconds late (it is never released early, and the claim recovers by
  lease expiry like any deadline outcome). Size `workflow.executor.leaseSeconds` with that in mind for applications that migrate.

**Cancel and the outcome table.** `cancel` stays synchronous and records no command. In the caller's transaction, under the
instance's execution lock (it waits up to `workflow.recording.lockTimeoutMs`), it refuses an instance that has ended
(`WorkflowSignalRejectedException`) and otherwise, in lock order: cancels in one statement every `PENDING`, `DEFERRED`, `CLAIMED`,
`PARKED` and `DEAD` command of the instance, whatever its kind, with the token and lease cleared, a `COMMAND_CANCELLED` entry each and
an `INSTANCE_CANCELLED` reason that names the kind and never a payload value; cancels the timer rows of the cancelled timer commands
(the wait's timer and the task's reminders write `TIMER_CANCELLED`); cancels the task the instance waits for and every task a cancelled
completion reserved (`COMPLETING` as well as `OPEN`), and the open tasks of active branches; cancels the active branches; and marks the
instance `CANCELLED` with the `CANCELLED` entry and `WORKFLOW_CANCELLED` (the caller's reason is cut at 255 code points, and its control
characters are replaced, before it is stored). Every task the instance still has open or reserved is cancelled with its due date and
reminders, also when the instance failed and no longer names a wait. A `FAILED` instance can be cancelled,
because `retry` could have resumed it; a `PENDING` instance, or a `FAILED` one whose start failed before initialization, is cancelled
without computing an initial state, resolving a subject or running any workflow code, and keeps `state_json`, `current_step_id` and
subject null; `query` returns it with null state and step. Each cancelled `SIGNAL` that was open frees its slot of the open-signal
count in the same statement, so the count is zero right after a cancel (a dead-lettered signal had released its slot already). A
segment that was running holds the lock, so the cancel follows its commit; a command that was claimed is cancelled, so a late
settlement finds nothing to settle and writes nothing, and a deferral or a failure disposition that was in flight finds its fence
failing. A signal, completion or retry recorded while a cancel waits for the lock is refused once the lock is free, or was recorded
first and is cancelled with the rest.

`EndedInstanceOutcomes` is the one place that decides what becomes of a command that meets an instance that has ended or failed, in
the executor's claim and in the segment of every kind:

| Command | `FAILED` | `COMPLETED`, `COMPENSATED`, `EXPIRED` | `CANCELLED` |
| --- | --- | --- | --- |
| signal (instance-addressed) | parked | rejected | cancelled |
| signal (branch-addressed) | stale if its branch ended, else parked | stale | cancelled |
| task completion | rejected, reservation reopened | rejected, task cancelled | cancelled, task cancelled |
| retry | executes | rejected | cancelled |
| migration | rejected | rejected | cancelled |
| timer | stale | stale | cancelled |

A start that meets an ended instance is rejected, or cancelled when the instance was cancelled. Rejected, stale and cancelled
commands log `COMMAND_REJECTED`, `COMMAND_STALE` and `COMMAND_CANCELLED` with a reason code (`INSTANCE_ENDED`, `INSTANCE_CANCELLED`,
`INSTANCE_FAILED` and the like) and framework text. The parked and dead-lettered commands of an instance that ends are settled
together in one write (`settleEnded`): `REJECTED`, or `CANCELLED` for a cancelled instance. Active commands of an ended instance settle
by this table when they next execute.

**Parked signals.** After a segment has driven the instance to a wait, the parked signals that wait accepts are applied in queue
order, each under its own savepoint, settled in the segment's transaction as `COMPLETE` (history `SIGNAL_APPLIED`, `COMMAND_COMPLETED`)
or, when its application fails deterministically, `REJECTED` alone. The segment applies a parked signal only while the nodes it has
executed are at most half of `maxStepsPerSegment`. When the next one would start beyond that, or its application overruns the cap
inside its savepoint, that signal and every other signal the wait accepts are set back to `PENDING` at their original position and the
segment commits at the wait; each then runs as the executing command of its own segment. A parked signal that is deadline-blocked at
the wait stays parked. When a segment ends the instance, its parked and dead-lettered commands are `REJECTED` with an `INSTANCE_ENDED` reason in one write.
`START`, `SIGNAL`, `COMPLETION` and `TIMER` segments call `ParkedSignals.drain` after their drive, outside the savepoint of the executing command. Flows that run
inline in the caller's transaction (direct calls of the task callbacks, and branch continuations through the fork/join
coordinator) apply the parked signals of the waits they reach at the end of the drive, after everything the drive wrote
(`WorkflowTransitionDriver.afterInlineDrive`); a transaction whose caller drains itself is left alone, so a parked signal is applied
exactly once and never nested inside another command's savepoint. `RETRY` and `MIGRATION` segments drain like the other segments.

Every recording call refuses a signal name, fork step id or branch id of more than 255 characters with a value-free
`WorkflowInvalidPayloadException`. Applying a recorded signal binds the instance's stored carrier without its identity-snapshot namespace,
so no identity can be rebuilt from a carrier written before that namespace was dropped. A parked signal applied inside another
command's segment binds its own recorded context, and the enclosing command's context is restored after it.

A wrapper or decorator around `WorkflowEngine` must forward both overloads to the engine; inheriting the defaults silently drops the caller-contract check. The check guards against developer error, such as a client built for the wrong contract; it is not an authorization boundary, because the caller supplies the contract name. A client proxy generated by an older annotation processor does not call these overloads and fails with `WorkflowClientProxyLinkageException` until it is rebuilt.

Task completion validates the caller's decision and payload inside the completion transaction and
rejects caller-caused problems with `WorkflowInvalidPayloadException` (HTTP 400 class, structured
pointer details):

- an unknown decision name is reported with type `decision`;
- a non-null payload for a decision without a payload type, or a missing payload for a decision that
  declares one, is reported with type `payload`;
- a payload that does not bind to the declared payload type is reported with type `binding`, and the
  decode exception is the cause.

Messages and details built by the engine are value-free: they carry no payload value, decision name,
class name, decode message or map key. The cause is logged at DEBUG level only, but it stays on the
exception and can carry submitted text, so applications must not log the cause of a
`WorkflowInvalidPayloadException` at INFO or above. The engine logs the cause at DEBUG unsanitized: it can
quote submitted text, including line breaks. Keep DEBUG off for the `dev.vertique.workflow.engine` loggers in
production, or sanitize at the log sink. A decision payload type is resolved from the name the plan holds: the engine looks the name up in the
`WorkflowTypeNames` index first, so a class named with `@WorkflowType` resolves by its logical name, and an
instance written before the class was annotated still resolves by the class's binary name. A name the index
does not know is looked up as a binary class name, as before, without running the class's static
initializer. A payload class that cannot be resolved remains a
definition error (`WorkflowDefinitionException`), not a caller error. A failure thrown by an application
decision applicator is also reported as a definition error, with the applicator exception as its cause
and without its message.

The rejection happens inside the completion transaction, so the idempotency claim rolls back with it, and
so does any earlier side effect of the same transaction (on the branch path the timer cancel, locks and
history work run first). A retry with the same idempotency key and a corrected payload completes the task.

**On the transactional entry points, rollback of a rejected payload is the caller's.** A caller that uses a
transactional task service owns the transaction and must roll it back when the completion future fails. If
the caller maps the 400 and still commits, the deduplication claim and any earlier side effect of that
transaction are kept.

Starting an instance validates the start payload the same way and rejects caller-caused problems with
`WorkflowInvalidPayloadException` (HTTP 400 class, message `invalid start payload`, structured details with
a root pointer path):

- a missing payload for a start type that needs one, or a payload for a start type without one (`void`),
  is reported with type `payload`;
- a payload that does not bind to the declared start type is reported with type `binding`.

The details the engine builds are value-free: they carry no payload value, class name, decode message or map
key. The decode cause is logged at DEBUG level only, and the same caution applies: do not log it at INFO or
above. The rejection happens before the instance is started and rolls the start transaction back, so no
instance row, history entry or deduplication claim is kept; a retry with the same idempotency key and a corrected
payload starts the instance. The application's initial-state function and subject resolver run in the executor, not in the
caller: one that throws (a `WorkflowDefinitionException` with the thrown exception as its cause and without its message) rolls
the segment back and is logged at error level, and the instance stays `PENDING` while the claim lapses and is claimed again.

#### Payload Constraint Enforcement

When a `BeanValidator` is bound, the engine also checks the coerced payload against its Bean Validation
constraints: the decision payload (instance and branch tasks) and the start payload. The check runs after
coercion and before the task is completed or the instance started, inside the same transaction, and fails with `WorkflowInvalidPayloadException` carrying one detail per
violation:

- **path** is an RFC 6901 pointer over the payload's wire (JSON) names, for example `items[0].name`
  becomes `/items/0/name`. Index segments are numeric, a container-element node contributes only its index
  (nested lists give `/lists/0/1`), and `~` and `/` are escaped as `~0` and `~1`. A map entry or set member
  ends the pointer at the map or set property, so the caller's key never appears in the path. A segment
  uses the payload's primary wire name, and only for a member the mapper writes through a setter or a
  creator parameter (record components included): the check resolves in mapper-only mode, against the
  payload type and the process JSON mapper without the submitted document, so a member bound only through
  a field (a public field, or a private field with a getter and no setter) ends the pointer at its parent,
  `""` at the root, and a payload bound through a builder gives `""` for every violation, because no
  member of it can be verified. A segment that cannot be
  verified against the payload type ends the pointer at the deepest verified ancestor: an unknown member,
  a member the mapper does not write, a bean or property whose binding Jackson reshapes (a custom
  deserializer or converter, `@JsonDeserialize(as)`, a builder, a delegating or single-argument scalar
  creator, an array shape, an any-setter or unwrapped member), a position on anything but a list or
  array, and a position in a list that skips null elements or accepts a single value as an array. A
  polymorphic value (`@JsonTypeInfo` on its type or on the holding property) ends the pointer at the
  property holding it: the check does not know which subtype the mapper bound, and a subtype may bind
  even an inherited member under another name. A pointer may therefore be shorter than the violated
  location but never names a different one for the shapes the check detects; a deserializer installed
  by a registered Jackson module is not detected. The path is never a map key or other caller data.
- **type** is the validation module's mapped name: `size`, `required`, `min`, `max`, `pattern`, `email`.
  An unmapped constraint keeps the annotation simple name.
- **message** is exactly what the validator produced.

Every violation is returned, in the order the validator produced them, and every pointer is converted.
The transport must still cap the request body before calling the engine.

The rejection happens inside the transaction, so any earlier side effect of the same transaction rolls
back together with the deduplication claim. An idempotent replay or a stale or already-completed task
is answered before payload validation.

Installing the validation module (`ValidationModule`) switches enforcement on. With no `BeanValidator`
bound, behavior is unchanged and the payload is checked for binding only. The engine logs one INFO line at
construction saying whether payload constraint enforcement is enabled or disabled. The rejection rolls back
the completion or start transaction like the binding checks above.

Details built by the engine are value-free. Constraint messages produced by an application's constraints
pass through unchanged, so applications must not interpolate the validated value:

- Do not interpolate the validated value into a constraint message.
- Do not build custom violations from untrusted text through the template-building API
  (`buildConstraintViolationWithTemplate`).

Residual: a `@Pattern` constraint on this path runs on unbounded strings, the same posture as REST bean
validation. Keyed validation is the bounded path.

---

## Key Classes

### WorkflowEngineHandle

```java
public interface WorkflowEngineHandle
        extends WorkflowOperations,
                TransactionalWorkflowOperations<SqlClient>,
                TransactionalTaskCallbacks<SqlClient> {
    WorkflowExecutionProbe executionProbe();
    WorkflowExecutorControl executor();
}
```

Public composite handle re-bundling the four already-public engine interfaces into a single type, plus `executionProbe()`:
the probe of the same engine, built over the command repository part (see Execution Probe), and `executor()`: the control
that starts and stops the engine's executor (see Executor). It is the return type of `WorkflowEngineFactory.create(WorkflowEngineParts)` so callers outside
the engine package can hold a fully wired engine without depending on the package-private concrete class.

### WorkflowEngineFactory

Static factory that is the non-Dagger parallel to `WorkflowEngineModule`. `create(WorkflowEngineParts)` is its only
assembly method; it assembles the entire package-private collaborator graph from the parts — including hand-wiring
the `WorkflowTransitionDriver` ↔ `ForkJoinCoordinator` mutual-recursion cycle using a one-element holder array —
and returns the engine as a `WorkflowEngineHandle`.

`WorkflowEngineParts` is a final class with a builder as its only way in (`WorkflowEngineParts.builder()`), so a later
release can add a part without breaking a caller. One setter per part, named like its accessor: `txRunner`, `registry`,
`definitionStore`, `instances`, `history`, `dedup`, `branchTokens`, `joinStates`, `timerStore`, `taskStore`, `taskReservations`, `commands`, `clock`,
`recorders`, `optionalIntentKinds`, `migrationRegistry`, `propagator`, `inboundContextInitializers`, `beanValidator`,
`typeNames`, `stateCompatibility`, `executorConfig`, `recordingConfig`, `executorObservers`, `commandWakeups`, `schemaProducer`, `historyRedactor`, `historyPublisher` and `vertx`. `build()` throws a `NullPointerException` that names the first required part
that is missing. Required: the repositories and stores (including the command repository, `commands`, and the task reservations, `taskReservations`, through which a recorded completion owns its task), the registry, the **definition store**, the type-name index and the
clock. Defaulted: `recorders` and `inboundContextInitializers` to empty sets, `optionalIntentKinds` to
`DEFAULT_OPTIONAL_INTENT_KINDS`, `executorConfig` to `WorkflowExecutorConfig.defaults()`, `recordingConfig` to `WorkflowRecordingConfig.defaults()` (the payload bound and the open-signal limit of recorded signals and the schema cache size of recorded completions), `schemaProducer` to empty (the producer of the payload schemas that a new task's decision snapshot stores; without it new tasks carry no schemas and the executor alone validates their completions), `executorObservers` to an empty set (the observers of the executor's events, see the executor observer in `vertique-workflow-core`), `commandWakeups` to `CommandWakeups.none()` (the source that wakes the executor's instances, see Executor), and `migrationRegistry`,
`beanValidator`, `stateCompatibility` and `historyRedactor` to empty, and `historyPublisher` to `WorkflowHistoryEventPublisher.disabled()`, which announces nothing. `vertx` may be `null`; the executor of such an engine cannot be started. The
`propagator` may be `null`. The `typeNames` index is the one the registry fills (`DefaultWorkflowRegistry#typeNames()`);
with a different index a decision payload name resolves as a binary class name only. An empty `beanValidator` leaves
payload constraints off, an empty `stateCompatibility` makes the engine refuse a migration between differing state
classes, and an empty `migrationRegistry` makes `migrate` fail with `UnsupportedOperationException`.

A factory-built engine builds its plan cache (see Plan Cache) with the default size, 500 plans, no expiry and an empty observer set,
so it emits no cache events; `WorkflowEngineParts` has no part for it. The cache of a factory-built engine reads rows
through the `definitionStore` part but has no document loaders, so a document the registry does not hold fails to load
as not runnable there; an application that needs on-demand documents composes the engine with `WorkflowEngineModule`.
When `registry` is not the framework's `DefaultWorkflowRegistry`, the engine does not bind the cache to it.

An engine always runs over a durable definition store: there is no store-less mode. A factory-built engine runs no
startup step, so `create` seeds the engine's view of the activation records from the records the store holds when it
is called; a start without a version fails with `WorkflowNoCurrentVersionException` when the store holds none that
applies. The 0.3.0 positional `create` overloads were removed.

Key members:

| Member | Description |
|---|---|
| `DEFAULT_OPTIONAL_INTENT_KINDS` | Shared constant (`Set.of(IntentKind.WORKFLOW_EVENT)`) — single source of truth for both Dagger and non-Dagger assembly paths |
| `create(WorkflowEngineParts)` | Assembles and returns a `WorkflowEngineHandle` |

```java
// Broad engine IT setup (non-Dagger path via PgWorkflowEngineTestSupport)
WorkflowEngineHandle engine = WorkflowEngineFactory.create(WorkflowEngineParts.builder()
        .txRunner(txRunner).registry(registry).definitionStore(definitionStore)
        .instances(instances).history(history).dedup(dedup)
        .branchTokens(branchTokens).joinStates(joinStates)
        .timerStore(timerStore).taskStore(taskStore).taskReservations(reservations).commands(commands)
        .clock(clock).recorders(recorders)
        .typeNames(registry.typeNames())
        .build());
```

### WorkflowEngineModule

Dagger module owning the engine-internal bindings:

- Binds `WorkflowEngine` as `WorkflowOperations`, `TransactionalWorkflowOperations<SqlClient>`, and
  `TransactionalTaskCallbacks<SqlClient>`.
- Contributes the `workflow-executor` `VerticleDeployment` to `Set<VerticleDeployment>` (see Executor), unless `workflow.executor.enabled` is `false`, which makes the node recording-only and contributes none (see Node Roles): the `SERVICES` phase,
  priority 100, `workflow.executor.instances` instances on the event loop. The configuration is the optional
  `WorkflowExecutorConfig`; install `WorkflowExecutorConfigModule` (workflow-core) to read it from `workflow.executor`, or the
  defaults apply. It also binds `WorkflowExecutorControl` for `WorkflowEngineHandle.executor()`.
- Provides `WorkflowExecutionProbe` over the `WorkflowCommandRepository<SqlClient>` the adapter binds. The probe paces its
  polling with the graph's `Vertx` when one is bound (declared `@BindsOptionalOf`) and with a shared daemon thread otherwise.
- Binds `WorkflowDefinitionLifecycle` for durable document publication and contributes the document registry startup step.
- Keeps no copy of the definition table and arms no timer: a node reads the row it needs when it needs it (see Plan Cache),
  so a change another node committed reaches it with the next read of that row.
- Provides the `PlanCache` (see Plan Cache) and binds it as the `DocumentPlanSource` of the framework registry when it is built
  (a `BOOTSTRAP` startup step builds it right after the registry is restored, and the executor builds it on its first warm-up
  otherwise, so a graph that never starts or runs an executor does not build the registry for it); it takes its
  size and expiry from `workflow.definitions.cache` (`maximumEntries`, default 500; a positive `ttlSeconds` expires a plan
  that long after it was built, default `0`: no expiry; a key left out keeps its default, and the cache module's keys do not apply, nor does
  `cache.enabled` disable it), read from the optional `@VertxConfig` configuration through the optional `ConfigParser`
  (without them the defaults apply) and reports
  its events to the `Set<CacheObserver>` multibinding. The module declares that multibinding itself with `@Multibinds`, so an
  application without a cache provider still builds its graph; it does not include `CacheCoreModule`, and a graph that does
  install it (any application with a cache provider) declares the same multibinding, which Dagger merges. An application
  that wants the cache events installs a cache observer module (for example the Micrometer or OpenTelemetry cache
  bridge) next to the engine.
- Declares the `@WorkflowRecorders`-qualified `Set<WorkflowSideEffectRecorder<SqlClient>>` multibinding.
- Provides the `@OptionalIntentKinds Set<IntentKind>` (delegates to `WorkflowEngineFactory.DEFAULT_OPTIONAL_INTENT_KINDS`).
- Declares `WorkflowMigrationRegistry` as `@BindsOptionalOf` (opt-in migration support). The migration executor also
  injects the optional `WorkflowStateCompatibility` that `WorkflowCoreModule` declares; install
  `vertique-workflow-schema` to bind it.
- Declares `WorkflowHistoryRedactor` as `@BindsOptionalOf` (opt-in redaction of the instance history; see the decision log).
- Declares `BeanValidator` as `@BindsOptionalOf` (opt-in payload constraint enforcement; bound by installing the validation module).
- Includes `WorkflowCoreModule` and `ContextRuntimeModule`. The task-lifecycle service takes the singleton
  `WorkflowTypeNames` index that `WorkflowCoreModule` provides.

Adapter modules (`WorkflowPostgresqlModule`) include this module, so applications installing only the
adapter module get all engine bindings automatically.

```java
// Application component — install only the adapter; engine is included transitively
@Singleton
@Component(modules = {
    VertxModule.class,
    WorkflowPostgresqlModule.class,   // includes WorkflowEngineModule
    WorkflowServicesModule.class,
    DbPostgresqlModule.class,
    AppModule.class
})
public interface AppComponent {
    WorkflowOperations workflowOperations();
}
```

### Node Roles

`workflow.executor.enabled` (default `true`) selects the role of a node, once, when the graph is built. A **recording-only**
node (`false`) contributes no `workflow-executor` deployment, so it claims no command and runs no segment, and its startup
step only seals the registry: it reconciles no code or packaged document, performs no `CODE_UNAVAILABLE` check, builds no
plan, resets no publication attempt and writes no definition row. It starts over live code rows it has no code for.

Every recording path runs on it unchanged, because recording needs no plan: starts select their version from the rows and
validate the start payload against the stored schema, signals, task completions (whose decision payload is validated against the
snapshot's stored schema before the execution lock), retries, migrations, cancels and reassignments are recorded in the caller's
transaction, and queries, including `query(id, contractName)`, read rows. Operations that build a plan are refused with
`WorkflowNodeRoleException`: the lifecycle's `publish` and `reinstate`, and, through the definition, registry and schema modules,
document load, `WorkflowRegistry.load` and `WorkflowPayloadSchemas`. `activate`, `follow`, `deactivateDefault`, `deprecate` and
`retire` need no build and run on every node.

Starting the executor control (`WorkflowEngineHandle.executor().start()`) of a recording-only node fails with `WorkflowNodeRoleException`, so no code path deploys an executor on it. A retry of a failed instance records an instance-level `RETRY` unless one is already pending; a pending retry addressed to one branch does not count.

**Separation of duties.** The lifecycle operations stay open on a recording-only node by design, and the framework has no authorization of its own: any code that can call them can change which version starts new instances. Put an authorization layer in front of them, and restrict the database role of a recording-only node to the privileges it needs.

A node that runs the executor requires the `WorkflowPlanSchemaProducer` of `vertique-workflow-schema`: without it the startup step
fails before any write with a `WorkflowConfigurationException` that names the module and `workflow.executor.enabled`.

### Instance Scopes

`WorkflowEngine.start` resolves a null `StartCommand.startScope` to the default scope (`WorkflowScopes.DEFAULT`) and `doCreateInstance` stamps that start scope and the scope of the resolved definition version on the new instance; the engine takes no scope source of its own. The order of a start is: look up the start dedup row of `(start scope, definition id, idempotency key)` (`WorkflowDedupRepository.findStart`); on a first start, one selection read of the database on the caller's transaction (see Activation Records And The Current Version), then the choice of the version, the compare of the command contract with the definition contract and the refusal of a deprecated version from that read, then the subject, payload size, payload depth and start-schema checks, then the execution lock on the new instance, the dedup claim, pin admission and the `PENDING` insert with its `START` command. Everything before the lock runs without a lock. A subject whose type is longer than 64 characters, whose id is longer than 255 or whose version is longer than 64 (the widths of the instance's columns), and an idempotency key or a business key longer than 255 characters, is refused with `WorkflowInvalidPayloadException` before the selection read and the lock; the refusal repeats no value. A replay that names a version or target scope other than the existing pin fails with `WorkflowIdempotencyConflictException`; the stored fingerprint stays `definitionVersion=N` and is not used for the comparison. `MigrationExecutor` carries both scopes of the instance into the re-pinned snapshot, and resurrection keeps them, so a scope written by another writer stays with the instance. The scopes are information and a filter, not an isolation boundary.

`migrate(id, targetScope, targetVersion)` (and the transactional form with a trailing transaction) resolves the target in `targetScope`, which must be the instance's start scope or the default scope. The executor, before it looks up the handler, rejects the migration for a target of another contract, a target whose state class differs from the source's and does not extend it (or any differing state class when no `WorkflowStateCompatibility` is bound), and a target version that this node evaluated as failing (see Failing scope rows), before any write; the reason is fixed text and never names a state class; it then finds the handler by both scopes and re-pins the instance's definition scope to the target. `migrate(id, targetVersion)` keeps meaning the instance's definition scope.

Work this node cannot run. A key that the node's registry holds no plan for is not runnable on that node, whether or not another node can run it (a rolling deploy publishes a version that reaches the other nodes as a committed record before they have its code; a failing scope row has no registered plan at all). A start needs no plan: it selects its version from the database and records a `PENDING` instance and a `START` command on any node, and the executor of a node that cannot run the plan defers the command until a node that can runs it. The plan lookup then fails with `WorkflowVersionNotRunnableException`: a task completion, a typed-client query, and the join advance leave the instance, its history and its dedup rows as they were, because the caller's transaction rolls back (inside a timer transaction a join advance that resolves the plan again would commit, but it cannot fail there today: it uses the pin the caller already resolved, and migration refuses an instance with active branches). A signal (including the branch-aware forms), a retry and a migration need no plan to be recorded, so a node without the pinned code records them like any other node; an executor of a node that cannot run the plan, or, for a migration, the target plan, defers the recorded command (a deferral spends no attempt) and a node that can run it applies it. A timer (a standalone or signal-timeout timer, a task due date or a task reminder, on an instance or on a branch) is a `TIMER` command, so the executor of a node that cannot run its plan defers it like any other command and a node that can run it fires it; a deferral spends no attempt and never fails the instance, however long it lasts. There is no public timer callback: a timer fires only as its `TIMER` command, which needs the pinned plan. Branch recovery leaves the token scheduled for a node that has the code. No node marks an instance failed because it cannot run its pin, while `query(id)` and the contract-checked `query(id, contractName)` read the stored instance and history without the plan (the contract is compared with the one stored on the pinned definition row). A node whose deployed code lacks a non-retired version still fails startup with `CODE_UNAVAILABLE`, so deployments are roll-forward only: the not-runnable failures occur on nodes that are already running when another node publishes or activates.

Every lookup of an instance's plan (signals, tasks, timers, retries, branch and join continuations, queries and migration) resolves the instance's full pin: its definition scope, definition id and definition version. An instance pinned to a definition scope never runs the plan that another scope holds under the same id and version, and migration resolves its target version in the instance's definition scope. The lifecycle and the bootstrap step read and write the rows of the scope they name, and the plan cache builds a plan under the scope of the row it reads.

Every event and side-effect correlation the engine records, single-path and branch, takes both scopes from the owning instance row, so events and outbox records name the scopes the instance really holds rather than a default. A branch drive reads them from the instance.

### Activation Records And The Current Version

A start without a version runs the version that activation records select. A first start (one with no start dedup row) makes one selection
read on the caller's transaction connection: `TransactionalDefinitionReads.selectionRead(startScope, definitionId, tx)` returns, in
one statement, the stored versions of the id in the start scope and the default scope (version, status, kind, contract, state type,
failure mark, plan hash, and for an `AVAILABLE` version only the signal names and start-payload schema, no document text) and the activation records of both scopes.
The version, the contract ownership and the refusal of a deprecated version are decided from that result; no plan is loaded, no
registry is consulted and no payload class is read. A replay returns before the read. The engine consults, in this order: the
start scope's record of the definition id and the default scope's record. No record means the start fails
with `WorkflowNoCurrentVersionException(scopeKey, definitionId)` at once, including after `deactivateDefault`; no current
version is ever computed from the registry. Only the start scope, the default scope and the default scope's
versions are ever consulted: the versions of another scope are never borrowed, and nothing in the start path weights, samples or
splits traffic between versions. The choice also follows the client's contract: a scope's own record applies only to clients of the contract that the scope's own versions implement, and without a record such a client follows the default scope's current version only when that version implements the same contract, or fails with `WorkflowNoCurrentVersionException` otherwise. Any other client uses the default scope's record when the default scope's versions implement its contract, and fails with `WorkflowNoCurrentVersionException` when they do not. `activate` of a scope to a default-scope version of another contract than the scope's own versions fails with `WorkflowDefinitionContractMismatchException`. The choice reads at most two activation records and the stored versions of one id, whatever the number of scopes. A record resolves its version like an explicit start of it, so a record whose target is
deprecated fails with the `DEPRECATED` lifecycle reason and one whose target has no published row (a candidate, or no row) fails as an unavailable
pin (`WorkflowVersionNotRunnableException`) and a retired one is refused by pin admission as a lifecycle failure; a deprecated version is refused whether it was named or selected by a record. A
version this node holds no code for is not refused: the start is recorded and a node that can run the plan runs it.

**Freshness and isolation.** The selection read is the linearization point for activation changes, and it runs on the caller's
connection, so what it sees follows the caller's transaction isolation level. At READ COMMITTED (PostgreSQL's and the framework's
default) the next start on every node sees an activation, deprecation or retirement committed before its read. At REPEATABLE READ or
SERIALIZABLE the read sees the caller's snapshot, so an activation change committed after that snapshot can be missed and the start can pin
the earlier version. The engine neither checks nor changes the caller's isolation level, and no level is refused. Pin admission stays the fence for status: a
version deprecated or retired between the read and the insert is refused at every level with the lifecycle exception whose reason is
`INVALID_TRANSITION` and whose message names the status.

**Start payload.** The payload of a start is checked against the JSON Schema stored with the selected row, through the bounded
record-time validator, before the execution lock: a payload larger than `workflow.recording.maxPayloadBytes` or nested deeper than 64
levels is refused with `WorkflowInvalidPayloadException`, a stored schema with a non-local `$ref` is refused naming the definition
version (the stored row is not changed), and a payload that violates the schema is refused naming the definition version and the path
of the first violation, never the payload. A row stored without a schema (written before the schema was stored) leaves validation to the
executor, which deserializes the payload into the plan's start type and applies its constraints; the size and depth bounds still apply.

**Failing versions.** The failure mark column is read and refuses nothing: a start of a failing version is recorded, and the executor
fails it (see Failing scope rows).

**Ids with no row.** An id for which the database holds no row in the start scope or the default scope is not startable: the
selection read returns no version and no record, and the start fails with `WorkflowNoCurrentVersionException`, or
with `WorkflowVersionNotRunnableException` when it names a version. The engine keeps no registry copy of a definition to select from, so a store that cannot read on the caller's
transaction (`TransactionalDefinitionReads`) cannot serve a first start, which fails with `IllegalStateException`.

The activation records are rows of the table and the engine keeps no copy of them: the lifecycle writes them
(`activate`, `deactivateDefault`, `follow`) and every start reads them in its selection read, so a record committed on
any node is seen by the next start on every node. `activations` reads the store. The bootstrap step reads the default
scope's records and writes the record of a version it records for the first time; it reads no record of another scope,
however many there are.

On first publication the startup step passes `activateDefault` to the store for every code version and packaged document
version that does not opt out of activation on deploy, in ascending version order per id; the store writes the default scope's
record in the insert's transaction when the version is higher than the record. The step contains no rollout logic. See the
core module for the rule and the rolling-deploy advice.

To decommission an id: `deactivateDefault(id)`, then `deprecate` and `retire` of its versions. Starts without a
version in any following scope fail with `WorkflowNoCurrentVersionException` as soon as the default record is cleared. A restart
does not bring the record back, because it is not a first publication. See the durable registry section of `dev.vertique:vertique-workflow-core` for the
record rules and the guard that refuses to deprecate or retire the default scope's current version.

`query(id)` reads the instance and its history from the database and does not need the pinned plan, so a finished instance of a retired version stays readable after its class is removed. A typed client's `query(id, contractName)` compares the client's contract with the contract stored on the pinned definition row (`TransactionalDefinitionReads.pinnedContract`, read in the query's transaction), so it needs no plan and loads no class on any node, executor or recording-only; a pinned row that no longer exists refuses with `WorkflowVersionNotRunnableException`, and a different contract with `WorkflowDefinitionContractMismatchException`.

### Document Publication And Startup Restoration

When a `WorkflowPlanSchemaProducer` is bound (`WorkflowSchemaModule`), startup reconciliation gives each code and packaged document record the signal names and start schema of the plan this node built, and the store writes them in the same statement as the plan hash (insert and rehash). A row with an unchanged hash keeps its columns. A row whose two columns are null, written before they were stored, is filled by a short statement guarded by the null column, after reconciliation for code and packaged documents and after restoration for stored documents, from the plan whose hash the row holds; the hash is never changed by the fill. Without a producer nothing is written and startup behaves as before.

The engine orchestrates `WorkflowDefinitionLifecycle.publish` through the required SQL-free registry
store and format-specific loaders. Parsing remains in the loader module. Publication claims a durable
candidate, recompiles its stored source, verifies its key and plan hash, builds its plan with the checks of an
on-demand load (plan graph, signals, callbacks, contributed definition validators, per-plan validators, and for a scope other than the default one the check that its
state type extends the state type of the default scope's current version of the id, as the stored row of a document or the registered code definition gives it), and completes the fenced claim only when the build succeeds, which makes the
version `AVAILABLE`. A document that does not build is refused with a `WorkflowDefinitionException` (a failure that is not one is its cause and its message ends the text), the claim is released and the row stays a `CANDIDATE`. Publication registers nothing in memory and
leaves the plan to the plan cache of each node, which builds it when a command first needs it. Publishing a version that is already available
succeeds without a change, and publishing does not touch any other version or scope of the id. JVM fatal errors
are not ordinary recovery cases.

Publication and the other lifecycle operations of one definition id share a per-definition-id queue. A definitive
stale-claim refusal from completion preserves the original error and releases only its own claim token. For an
uncertain completion response, the engine rereads the committed row before it compensates; a row that is available with
the claimed plan hash means the publication won. The node keeps nothing of a publication: the plan is built again from the
row by the plan cache when a command needs it.

`WorkflowDefinitionRegistryBootstrapStep` runs in `BOOTSTRAP` with priority `Integer.MIN_VALUE / 2`,
after schema migration and before any `BOOTSTRAP` verticle deploys. Its reads are bounded: it reads the rows of the default
scope (which hold every code row and every packaged document), the rows of any scope that are being published (`PUBLISHING`),
and the default scope's activation records, and nothing else, so no startup statement grows with the rows of other scopes. It
builds a plan only for what the node deploys: the code definitions, which the registry holds, and the packaged documents,
which it builds to check them and records but does not register. It works in this order, all through the shared
per-definition queue, and fails startup (nothing deploys) on any violation:
1. It snapshots the code definitions available in the deployed code and collects the packaged documents of the default
   scope.
2. It records every definition as `AVAILABLE`: packaged document origins are DOCUMENT rows (an equal plan hash AND an equal
   text digest reconcile; a different hash, or the same hash with different text, is drift), every code definition
   is a global CODE row. A changed code hash is rehashed only while no RUNNING, WAITING
   or COMPENSATING instance is pinned to that exact version (decided inside the store transaction, WARN logs both
   hashes); with a live pin startup fails naming both hashes. A row recorded with a name binding keeps the
   type names its hash used: a code definition registered under current names is rebuilt under the stored binding
   (`DefaultWorkflowRegistry.adoptNameBinding`, internal to this step) when that gives the stored hash, and a
   packaged document is compiled and hashed with the row's binding, so annotating a state or payload
   class after the version was published does not change its hash, and a rehash replaces the binding together
   with the hash. A non-retired row whose binding names a class that no longer resolves (for example one moved
   while the version is bound to its binary name) fails startup with `WorkflowUnknownTypeNameException`
   naming the scope, the definition, the version and the name; a retired row is exempt. A kind conflict fails naming both rows; a CODE row
   in AVAILABLE or DEPRECATED state whose code is no longer deployed fails with `CODE_UNAVAILABLE`. Rows of other
   scopes for the same id and version never conflict.
3. It verifies every live DOCUMENT row of the default scope that the packaged documents cover, through its loader:
   the compiled hash and the stored text digest must match. A live default scope
   document that is not packaged, one that was published at run time, is not built at startup; its plan is built from
   its row on first use (see Plan Cache), and a document that does not build is then not runnable, so its first command is
   deferred and a warning names it once. With `workflow.definitions.buildDefaultScopeDocumentsAtStartup` set to `true`,
   startup instead builds each such document into the plan cache, by the build a first use runs, and a document that does
   not build fails startup with its failure (`WorkflowUnknownTypeNameException` for a recorded name that no class resolves,
   a `WorkflowDefinitionLifecycleException` for drift or a missing loader). No row of another scope is read at startup:
   the rows of other scopes are evaluated against the code this node ships when their plan is built on use (see Failing
   scope rows below). A CODE row that conflicts still fails startup.
4. It resets stale PUBLISHING claims (older than `workflow.definitions.publicationClaimTimeoutSeconds`, in any scope) to CANDIDATE.
5. It seals the registry: `DefaultWorkflowRegistry.register(WorkflowDefinition)` throws `IllegalStateException` naming the
   definition from then on. Registration is a startup operation (code definitions register while
   the registry is constructed); `DefaultWorkflowRegistry.seal()` is internal to this step and applications never call it.
   A graph without this step, such as one assembled by `WorkflowEngineFactory.create`, is never sealed.
Required rows with no unique loader fail startup with the key and diagnostic when the step restores them.
Compose validators ran in VALIDATE and do not re-run for stored definitions. The registry holds only the deployed code
(`WorkflowRegistry.codeDefinitions()`): a packaged document is not registered, not listed there, and is built from its row
by the plan cache like any other document.

**Failing scope rows.** Evaluating a live DOCUMENT row of a scope other than the default one, which happens when the plan
cache builds its plan for a command, can find that the
code this node ships conflicts with the stored row. Two conflicts mark the row failing instead of failing the command:
the row's state class no longer extends the default scope's current version of the same id and contract, as the
stored row of a default scope document or the registered code gives it (`STATE_TYPE_NOT_EXTENDED`), and a type name in the row's recorded name binding no longer resolves
(`NAME_BINDING_UNRESOLVED`). A default scope row that conflicts fails startup with `WorkflowDefinitionException` for the state
rule or `WorkflowUnknownTypeNameException` for an unresolved binding, each naming the scope `default` and the
definition id. A RETIRED row is not registered and is never evaluated, and a contract overlap between a scope row and
default scope code is not a conflict. A CANDIDATE row is not evaluated either: an unresolved binding is refused
when it is published, with `WorkflowUnknownTypeNameException`, and the row stays a candidate.
- **State rule.** The plan is built without the comparison with the default scope's state class and flagged, so the
  instances pinned to the row keep resuming, receiving signals and completing tasks.
- **Binding rule.** Nothing is compiled, and no class is loaded by a name that no longer resolves; the row has no plan on
  this node, so its instances are not resumed here until the class is back.
- **No record-time refusal.** A new start that selects the row, by an activation record or by an explicit version, is recorded
  like any other start: the stored mark and this node's evaluation refuse nothing in the caller's transaction. The executor fails
  the recorded `START` (see Evaluation on the executor). Other scopes, and the default scope's versions, are not affected.
- **Evaluation on the executor.** The executor decides for itself, from the plan it builds for a command, whether the
  scope row the command runs is failing. A name binding that no class resolves builds no plan: the command is deferred as
  not runnable (no attempt is spent) and the row is marked `NAME_BINDING_UNRESOLVED` once the deferral has committed. A
  state class that no longer extends the default scope's builds the plan and flags it: a `START` of the flagged version
  fails its command and its instance with the reason code `DEFINITION_FAILING` (the stored reason names the rule and never
  a state class or document content; the instance's failed history entry is written, and the
  `WorkflowDefinitionFailingException` is logged at warning level), while every other command of the version, signals,
  completions, timers, retries and migrations out of it, runs unchanged, so running instances keep resuming. A migration
  into a flagged version is rejected like one into a marked version. A flag is kept with the plan in the plan cache and is
  evaluated again, in place, when the default scope's state type name that the operation reads differs from the name it
  was evaluated against. The comparison for a plan the cache builds reads the default scope's current row (the version its
  activation record names) when that row is a document, and the registered code definition otherwise; the in-place
  re-evaluation follows the registered code only, so a changed default document is seen when the plan is built again.
  A version of the default scope is never flagged or marked. A start recorded before any node has evaluated
  the version fails the same way when the executor runs it.
- **Mark writes.** When the executor's outcome differs from the mark the row stored when the plan was built, it writes or
  clears the mark with `recordFailure` in a statement of its own, after the command's segment or deferral has ended and
  never inside its transaction, and detached: the executor frees the command's slot and asks for the next pass before the
  write ends, so a slow or hung write holds no slot. The write is best effort: a failure is logged at warning level with
  the key only and dropped, the command's outcome is unchanged, a RETIRED row is not marked, nothing retries it, and the next command that finds the outcome different
  writes again; once a write succeeds, later commands write nothing. The node that evaluated last wins. The stored mark
  feeds listings only.
- **Default scope unaffected.** A failing scope row never blocks a default scope version of the same id, at startup or
  when a default version is published later.
- **Evaluation per node.** Startup evaluates no scope row. Each node's executor judges a command by the plan it builds
  against the code that node ships, not by the stored mark, and writes the result with `recordFailure`, which clears
  the mark when no conflict remains. A row nobody runs a command of keeps the mark it has. The stored mark feeds the definition
  listing (`WorkflowDefinitionRecord.failure()`). During a rolling deploy, nodes that ship different code may rewrite the
  mark in turn, so the listing shows the most recent node's evaluation while each executor keeps judging by its own.
- **Start target scope.** A start that names a target scope that is neither its own scope nor `default` fails with
  `WorkflowDefinitionMissingException` before any failing mark is read, so a client learns nothing about another scope's rows.
- **Containment is limited.** Only these two rules are contained. Any other failure to build a scope row (a different
  hash, a missing loader, an unreadable source) makes the row not runnable on this node, and its commands are deferred.

`WorkflowDefinitionLifecycle.reinstate` brings a RETIRED or DEPRECATED version back to `AVAILABLE`. A RETIRED version is accepted
only when the executable source is available (a code definition from the code deployed with the node, captured before startup
removed retired runtimes; a document through its loader and the stored text, whose compiled hash must equal the stored hash).
It checks the source first and then commits `RETIRED -> AVAILABLE` in one store transaction; nothing is registered, and a
failed commit leaves nothing behind. A DEPRECATED version needs no source check, and the store commits
`DEPRECATED -> AVAILABLE` at once. Other nodes read the new status with their next read of the row. Failures are typed:
`CODE_UNAVAILABLE`, `DRIFT`, `LOADER_MISSING` or `INVALID_TRANSITION`. When the commit outcome cannot be read back, the
lifecycle reads the row again: an available row with the plan hash it read means the commit won. `retire` and `reinstate`
rely on every node running a release that takes the shared id lock when admitting a pin, so finish a rolling upgrade
before using them.

Changing the compiled shape of a code definition without changing its version is a rolling-deploy hazard: the first
new-code node that finds no live instance pinned to that version rewrites the stored hash (a WARN names both hashes), and
from then on every old-code node refuses new starts, migrations and failed-instance retries of that version with
`WorkflowDefinitionLifecycleException` reason `DRIFT` until it restarts on the new code. A node that restarts on old
code, including a rollback, then fails startup with `CODE_UNAVAILABLE` or drift for a row its code does not carry.
Prefer a new version over editing a deployed version; to remove a version, deprecate it and
retire it once no live instance is pinned, before removing its code.

`WorkflowDefinitionLifecycle.deprecate` commits AVAILABLE to DEPRECATED in the store through the per-id queue; deprecating a
version that is already deprecated succeeds. A deprecated version is skipped by current resolution, and an
explicit-version start whose version is deprecated fails with `WorkflowDefinitionLifecycleException` reason `DEPRECATED`
before any instance row is written, and a migration whose target is deprecated is rejected by the executor with the
instance unchanged. Signals, timers and resumption of instances already pinned to the version, and migration away from it,
are unaffected. Both checks read the row, so a deprecation committed on any node is seen by the next start and the next
migration on every node; the plan cache keeps no copy of the status.

`WorkflowDefinitionLifecycle.retire` retires a CANDIDATE version, or a DEPRECATED one as soon as no PENDING, RUNNING, WAITING or COMPENSATING instance, archived or not, is pinned to it,
however recently it was deprecated; the
store checks status and the pin count in one transaction, so a refusal is typed (`PINNED_INSTANCES`,
or `INVALID_TRANSITION` for any other status). A retired row is refused by pin admission as a lifecycle failure. The node
holds no runtime to remove: a plan that the plan cache still holds is not used for a retired row, because a start
reads the row. Managed operations are serialized per definition id on the node.
`publish` builds the plan before it completes the claim, so a document that a plan rule or validator refuses, or whose state type does not extend the default scope's, fails when it is published, and the claim is released.

Every public transaction-aware engine entrypoint (`start`, the `signal` overloads, `cancel`, `retry`, `migrate`, the
task and timer callbacks) requires a `SqlConnection` with a pending transaction begun through Vert.x and returns a
failed Future with `IllegalArgumentException`, before any SQL, for a pool, a bare or completed connection, a raw `BEGIN`
or a wrapper; await the operation before committing or rolling back. New starts are admitted against the
definition row in the caller's transaction (see the PostgreSQL reference); the re-pin of a migration is admitted the same
way in the executor's segment, where a refusal rejects the command.

Every node sharing live document rows must install the corresponding loader, normally
`WorkflowDefinitionModule`. Loading and compilation use the Vert.x worker executor when startup runs
on a Vert.x context. Compose validators already ran in VALIDATE and do not rerun for these restored
registrations or runtime publication.

### Changes Made By Other Nodes

A node keeps no copy of the definition table, so there is nothing to bring in step with it. A start reads the stored
versions and the activation records in its selection read, so an activation, deprecation or retirement committed on any
node is seen by the next start on every node (see Activation Records And The Current Version for the isolation caveat). A
command reads its plan through the plan cache (see Plan Cache), which builds a document version from its row the first time
this node runs it; a version that was published, deprecated or retired elsewhere needs no step on this node. The convergence
bound is a read, not a cadence. The cache keeps a built plan until it evicts it, and a plan is determined by the row's plan
hash, which never changes for a version that has instances; a code definition is the deployed code and changes only with a
deployment.

### WorkflowContextBinder

Package-private binder-row seam that binds effective durable context for **instance-owned,
single-path drives**: `signal`, `cancel`, `retry`, `migrate`, `taskCompleted`, and `taskReassigned`. It implements the bind
gate and the base-wins/instance-fill composition described in `dev.vertique:vertique-workflow-core`
§ Durable Context on the Instance:

```java
<T> Future<T> withBound(
        WorkflowInstance instance,
        @Nullable DurableMetadata explicitCarrier,   // explicit signal carrier only; null = capture ambient
        Supplier<Future<T>> drive);
```

- **Bind gate.** When `explicitCarrier` is `null` and `instance.metadata()` is `null`, no bind
  happens at all — `drive` runs exactly as it did before the instance carrier existed.
- **Base-wins/instance-fill.** The base is `explicitCarrier` when present, otherwise the ambient
  context captured via `DurableContextPropagator.capture(WORKFLOW)`. When the instance carries
  non-null metadata, it is merged into that base with `MergePolicy.CALLER_WINS`: the base wins for
  any namespace present on both sides; the instance only fills namespaces the base is missing.
- **`noop()` null-object.** `WorkflowContextBinder.noop()` returns a binder whose `withBound` always
  runs `drive` unbound, with no capture/merge/bind — the equivalent of the gate always being closed.
  Call sites that used to null-check a `WorkflowContextBinder` field before this seam existed
  substitute this instance instead, keeping the field non-`@Nullable`.

**Executor drives.** The executor binds the context an operation was recorded with, through two further package-private
entry points, and never an identity:

```java
<T> Future<T> withRecordedBase(
        WorkflowInstance instance,
        long commandId,
        @Nullable DurableMetadata explicitCarrier,
        @Nullable DurableMetadata recordedContext,
        Supplier<Future<T>> drive);
```

- **Precedence.** The explicit carrier wins when present, otherwise the operation's recorded context is the base, otherwise
  there is no base. The instance carrier fills what the base lacks with `MergePolicy.CALLER_WINS`. The ambient context is never a
  base. When the explicit carrier, the recorded context and the instance carrier are all absent, nothing is bound.
- **No identity.** The identity-snapshot namespace is stripped from every source before it is merged (explicit carrier, recorded
  context, instance carrier, and for a branch drive the branch token's carrier or the merged override). The executor never creates or
  reconstructs a `SecurityContext` and binds no deferred-execution origin. The registered inbound initializers run as for any
  durable bind; with no identity-snapshot namespace in the bound document the identity initializer has nothing to rebuild an
  identity from. This amends the rule that a drive carries identity through the durable context for executor drives only: requester
  drives (`cancel`, `retry`, task reassignment and the like, through `withBound`) are unchanged.
- **Fail closed.** If a `SecurityContext` or a deferred-execution origin is already bound on the segment's context, the entry
  point binds nothing, does not run the operation and fails with an `IllegalStateException` that names the instance and the
  command and no context value. The segment classes it as a transient failure: it rolls back and retries with a backoff, never
  fails the instance and is reported stuck if it recurs, because the fault is the executor's environment.
- **Branch drives.** A branch-addressed signal applied by the executor binds through
  `withExecutorDocument` (called by `BranchTransitionEngine.driveExecutorBranchTransitions`): the explicit carrier merged with the
  instance fill, else the branch token's own carrier, never the recorded context, with the same strip and refusal.

**Must not be used for branch-owned drives.** `WorkflowContextBinder` binds instance-owned drives
only. Fork continuations, branch-targeted signals, and branch timers are branch-owned and bind
through `BranchTransitionEngine.driveBranchTransitions`'s own carrier seam instead — this is the
bind-once routing rule: a drive binds through exactly one of the two seams, never both.

### BranchTransitionEngine.driveBranchTransitions

The branch-owned counterpart to `WorkflowContextBinder`. Its public entry point takes an additional
`@Nullable DurableMetadata effectiveBaseOverride` parameter:

```java
Future<BranchToken> driveBranchTransitions(
        BranchToken token,
        String parentStateJson,
        @Nullable String parentSubjectVersion,
        RuntimeWorkflow rw,
        SqlClient tx,
        @Nullable DurableMetadata effectiveBaseOverride);
```

By default the drive binds `token.metadata()` — the branch token's own persisted carrier. When
`effectiveBaseOverride` is non-null, the drive binds that pre-merged document instead of
`token.metadata()`. One caller passes a non-null override: the branch-targeted explicit-carrier signal path (an explicit signal
carrier that must win over the branch token for that one transition). Every other call site passes
`null`, preserving the original token-metadata bind unchanged.

History entries appended during a bound drive may additionally record `commandCorrelationId` — see
`WorkflowMigratedHistoryPayload` and the sibling command-history payloads for the field-level shape.

### Execution Probe

`WorkflowExecutionProbe` (public, in `vertique-workflow-core`) waits until recorded work has run. The engine implements it as
`DefaultWorkflowExecutionProbe` over the read members of `WorkflowCommandRepository`: it polls the count of runnable
commands until it is zero or the timeout elapses, then reads the report. It pauses 25 ms after the first read and longer after each
following one, up to 250 ms; a timeout beyond ten minutes is shortened to ten minutes; each list of the report holds at most 1000 commands. Its `stuck` list is read with the configured `workflow.executor.stuckAfterSeconds` (300 s for a probe built without a configuration): every dead-lettered command and every unsettled command first available longer ago than that. A factory-built engine exposes
it as `WorkflowEngineHandle.executionProbe()`; a Dagger graph provides it from `WorkflowEngineModule`. The adapter module must
bind `WorkflowCommandRepository<SqlClient>`.

### Plan Cache

`WorkflowRegistry.load(key)` answers a code definition, which the registry holds, at once. Any other key goes to the engine's plan cache, a size-bounded object cache named `workflow-plans` that
`WorkflowEngineModule` provides and binds as the registry's document plan source. The executor warms the plan of every
command it peeks on a live instance through `load`, so a node runs a document version it was not started with.

- **One read and one build per key.** Concurrent requests for a key the cache does not hold share one load. The load reads
  the record and the document text, each on a pooled connection that is released when the read ends, then compiles the
  document and builds the plan on a worker thread that holds no connection, and checks the rebuilt plan hash against the
  stored one. Every registration validator runs in the build. Nothing is registered: the plan belongs to the cache, and
  `find` and `resolve` answer a document key only while its plan is resident, never loading. A packaged document is a
  document like any other: it is not registered and not listed by `codeDefinitions()`, and the cache builds it from its row. A request completes on the
  caller's own Vert.x context, so a continuation runs on its executor instance's event loop.
- **What is remembered.** A `WorkflowDefinitionException` is remembered under the key and fails every later request: the
  document does not compile, a recorded type name no class resolves, no loader supports its format, a plan rule refuses it,
  or the rebuilt hash differs from the stored one. An absent row, a row that is not `AVAILABLE` or `DEPRECATED`, a failure
  to read the row, a failure of a validator an application contributes that is not a definition refusal, a load that takes
  longer than 30 seconds (it fails with a `TimeoutException`) and every `Error` are never remembered, so the next request
  reads again.
- **Failing flag.** An entry of a scope other than the default one carries the evaluation of its plan (see Failing scope
  rows): the flag, the default scope's state type name it was evaluated against and the mark the row stored when the plan was
  built. A name binding that no class resolves is remembered as a failure like any other that does not build.
- **Size and expiry.** At most `workflow.definitions.cache.maximumEntries` plans (default 500) stay resident, and with a positive `workflow.definitions.cache.ttlSeconds` a plan is dropped that long after it was built (default `0`: never); remembered failures count as entries. A key left out keeps its default. The block is read from the optional application configuration and `ConfigParser` (without them the defaults apply); the cache is a `CaffeineObjectCache` built from it directly. The cache reports each miss, each successful and each failed
  load and each size eviction to the `Set<CacheObserver>` under the name `workflow-plans`, and nothing for a hit.
- **Startup builds nothing into it by default.** Startup builds the code definitions, which the registry holds, and checks the packaged documents, but it keeps
  no plan of a document, whatever its scope. With
  `workflow.definitions.buildDefaultScopeDocumentsAtStartup` set to `true`, startup builds every live default scope document
  the registry does not hold into this cache, so one that does not build fails startup; the setting does not touch documents of
  other scopes, which are built on first use. A plan that this cache holds is not a registration, and a start or a command on it loads it through `load`.
  A document that does not build is logged once at warn level, with its key only, when the cache first remembers the failure.
- **Held for the segment.** A plan can be evicted at any moment, and a lookup inside a segment's transaction must never
  build. The executor therefore holds the plans it warmed from the peek until its segment ends (or until it decides not to
  claim the command); `find` and `resolve` answer a held plan whatever the cache did. Holds are counted, so two segments
  of one version release independently. A segment whose claim is released because the pin changed since the warm-up is
  peeked again and warms again.
- **Legacy rows.** A row written before the signal names and the start schema were stored has both columns null. After the
  first successful on-demand build of such a row, and only when a `WorkflowPlanSchemaProducer` is bound, the engine fills
  them with the store's guarded fill: one statement of its own on a pooled connection after the build, outside any segment
  and any caller's transaction, writing only while the names are still null and the stored plan hash is still the built
  one. A failed fill is logged at warn level with the key only and never fails the load; the plan stays resident.
- **Pool use.** No transaction takes a second pooled connection for definition data: the loads happen before the claim, and
  the load itself holds a connection only for each short read, so a cold version runs on a pool of two connections.

**Budget.** A resident plan is kept by reference, so `maxEntries` bounds the count of plans, not bytes. The largest plan of the order-fulfillment example, the fan-out definition with 15 nodes, retains about 2.4 KB of heap once built (the
retained size of 2,000 builds after a full collection, shared classes and the type-name index excluded); the 9-node order definition
retains about 1.9 KB. The default of 500 plans therefore budgets about 1.2 MB for plans of that size. Measure your own largest document
the same way before raising the limit: a plan grows with its node count and with the callbacks and schemas it holds, and a plan that is
evicted while a segment holds it stays on the heap until the segment ends.

### Executor

Recorded commands run in the executor: the internal `WorkflowExecutorVerticle`, deployed like the outbox relay. A Dagger-built
application deploys it with the rest of the `SERVICES` phase through the deployer and undeploys it in reverse order at
shutdown; the engine owns no start or stop of its own. A graph that runs no deployer deploys the `workflow-executor` entry of
its `Set<VerticleDeployment>` alone, never the whole phase, which would also start the graph's other `SERVICES` verticles. A
factory-built engine's `executor().start()` deploys the same supplier and options with `vertx.deployVerticle`, and
`executor().stop()` undeploys it; `stop` ends claiming and completes when every in-flight segment has committed or been rolled
back by its own deadline, which is normally within the lease and is extended by a statement stuck in the database. An
application that never starts its executor leaves every start `PENDING`.

**Wake-up.** An instance polls every `pollIntervalMs` and is also woken when a command becomes pending or deferred.
`CommandWakeups` (`engine.spi`) is the seam: each instance calls `register(context, sink)` when it starts and closes the
registration when it stops, and the source calls the sink on that instance's own context with the command's available time
in epoch milliseconds (a time in the past means peek now). A wake-up only asks for a pass; it never claims, and repeated
wake-ups coalesce. On each wake and poll, the instance asks `WorkflowCommandRepository.findEarliestAvailabilityDelayMs()` for
one database-relative delay and keeps at most one monotonic timer for the earliest pending or deferred availability. This
scheduling read is separate from `findNotYetAvailable`, which remains a bounded diagnostic list for `IdleReport`; the
executor never compares a PostgreSQL timestamp with the JVM wall clock. The default source and default delay read provide no
wake-up hint, so an engine without those capabilities polls alone. The PostgreSQL module binds the source
(`@BindsOptionalOf CommandWakeups` here) and sets `commandWakeups` on a Dagger graph; a factory-built engine passes one to
`WorkflowEngineParts.Builder.commandWakeups`.

Each verticle instance runs on the event loop only (no worker threading, no blocking call). It claims commands as the instance
id `<node id>-<n>`, where the node id is the host name plus a random suffix and `n` counts the verticle instances of the
deployment. One pass is one one-shot timer: it peeks the commands that could be claimed (the head of their instance's queue,
available, not held back), warms their plans through `WorkflowRegistry.load` outside any transaction (see Plan Cache), claims up to `concurrency` heads (one short
transaction each: try the execution lock, lock the row, check again that it is claimable, spend an attempt, set a token and a
lease) and runs a segment for each claim on a fresh duplicate of the instance's own verticle context, never the context of
whoever deployed it or recorded the command. A segment is one transaction through the engine's `WorkflowTransactionRunner`:
it tries the execution lock (a busy lock is retried until two margins before the lease ends, and then the claim is released
with its attempt restored), fences the claim (still claimed with this token and still at the head of its queue), runs the
command's work, appends the `COMMAND_RECEIVED` and `COMMAND_COMPLETED` history entries and completes the command with its
token cleared; a completion that updates no row fails the segment, so state, outbox intents and history roll back together.
The margin is a tenth of the lease and at least one second. Instances coordinate only through PostgreSQL.

The executor runs `START`, `SIGNAL`, `COMPLETION` and `TIMER` commands. A `TIMER` command comes due at its `fire_at`, which wakes the executor through the earliest availability; its segment runs the timer node, timeout, due date or reminder it fires (see Timers as commands). A command whose instance has ended settles at the claim: a timer is `STALE`, a signal or completion `REJECTED`.

The segment carries a deadline one margin before the lease ends, measured from a monotonic reading taken before the claim
was sent, and passed to the runner as the transaction deadline of `vertique-db`. A segment whose work has not completed by
then rolls back and its claim lapses at the end of its lease, so another executor claims the command after that (the attempt
stays spent, exactly as after a crash); work that completed before the deadline commits even when the commit ends after it. A
peek, claim or segment start that throws, returns no future or fails never ends the loop: the failure is logged at
warn level and the next pass is scheduled. A peeked
command whose plan could not be read for a reason that says nothing about the plan, such as a failed row read, is skipped and
logged at debug level; nothing is written and it is peeked again on the next pass.

**Commands this node cannot run.** For a command on a live instance the executor loads, outside any transaction, the plan of the definition key the
instance is pinned to through `WorkflowRegistry.load` (a migration also loads its target), and checks that, when the pin
names a plan hash, the plan has that hash. A node cannot run a command when no available row supplies the plan (an absent or
retired row, or a code version this node does not deploy), when the plan failed to build deterministically (a document that
does not compile, a recorded type name no class resolves, a rebuilt plan hash that differs from the stored one), or when the
hash differs from the pin. A transient failure to read the row is none of these: the command is skipped for this pass and
nothing is written. A command the node cannot run is deferred instead of claimed: one short transaction tries the execution lock, locks
the row, checks again that it is claimable and that the instance is still pinned as peeked, then sets it `DEFERRED`, raises
`deferral_count` by one and moves `available_at` forward by 5 s doubled once per earlier deferral, at most 5 min (fixed, not
configured). Attempts, token and lease are untouched, so a deferral never spends an attempt, and a node that can run the
plan claims the command as soon as it is available. The first deferral of a command appends one `COMMAND_DEFERRED` history
entry (kind and sequence, never a payload); each deferral reports `CommandDeferred` to the observers. A busy lock or a
failed check writes nothing. A deferred command still blocks the later commands of its instance, and a command nobody can run
is reported by the probe as stuck once it is older than `workflow.executor.stuckAfterSeconds`, measured from when it was
recorded. A command whose instance has ended is never deferred and no plan is looked up for it: it goes to the claim.

**Failed segments.** The executor reads a failure as the transaction runner returns it, after both of its mapping stages, and
disposes of it in this order:

1. The deadline overrun (`TransactionDeadlineExceededException`, as the failure or its cause) is the crash-like outcome above:
   no disposition, the attempt stays spent, logged at debug level only.
2. A **transient failure** (a retryable `WorkflowPersistenceException`: connection, timeout, deadlock, lock timeout; a
   `WorkflowConflictException` itself, an optimistic conflict; the database-layer equivalents) reschedules the command in one
   transaction that locks only the command row: `PENDING`, the attempt restored, `transient_count` + 1, `available_at` =
   now + the backoff (`transientBackoffBaseMs` doubled per failure, capped at `transientBackoffMaxMs`), token cleared and
   `last_error` set. It is retried until the lease ends, and reported to observers as `TransientRetry`.
3. A step-limit overrun (`StepLimitExceededException`: the segment executed more than `maxStepsPerSegment` nodes without a wait;
   the transition driver counts every node on the segment's transaction handle) and any other failure roll back the segment
   (a `SIGNAL` is the exception: its deterministic failure and its own overrun are contained by its savepoint and reject only
   that command, and an overrun inside a parked signal's savepoint re-queues it, see "Recorded signals")
   and fail the command: one transaction of its own, `PgWorkflowCommandRepository.failCommand`, tries the execution lock
   without waiting, locks the command row, writes only while the command is still `CLAIMED` with this token, settles it `FAILED`
   and, for a `START`, a `RETRY` and a `TIMER` (and for any command whose overrun this is), fails the instance with a `FAILED` history entry
   (a `START` has no state; the instance keeps the wait it failed in, so a retry returns to it, and a failed `TIMER` also closes its timer row as `FAILED`).
   A `SIGNAL`, `COMPLETION` or `MIGRATION` whose recorded target cannot be read fails before its savepoint and is rejected instead (`COMMAND_REJECTED`, a
   completion's task reopened, the instance unchanged); only its step-limit overrun fails the instance. A failed instance also emits a `WORKFLOW_FAILED` event (`errorType` the reason code, `errorMessage` the sanitized text), emitted through the engine's event recorders in the same transaction like the event of a fail node; a dead-lettered `START` emits it the same way from its claim. A busy lock is retried every 100 ms until one
   margin before the lease ends, then the claim is released with its attempt restored (the failure recurs on the next claim). A
   settled command has no token, so a late disposition writes nothing.

No executor connection ever waits on an execution lock. The claim itself checks, in this order: an ended instance settles the
command as rejected (stale for a branch-addressed signal) with a history entry (no attempt spent, never dead-lettered, the instance
unchanged); a signal's reason says "instance ended"; otherwise a command
that has spent `maxAttempts` attempts is dead-lettered with its attempts unchanged (a `START` also fails its instance); otherwise
the claim spends an attempt. A peek routes a command whose instance has ended to the claim without making any plan resident.
The command's `last_error` is built by `CommandErrors` (`<REASON_CODE>: <message>`, at most 4000 characters, framework-composed
text that names only the kind, the cap or the counts); exception text and payloads go to the log only.

**Observers.** The executor reports `CommandSettled` (outcome `COMPLETE`, `REJECTED`, `STALE`, `PARKED` or `FAILED`), `CommandDeferred`, `TransientRetry`, `LeaseExpired` and `QueueSnapshot` events, and a recording reports `ActorAbsent` (see "Who asked for a command"), to the
`Set<WorkflowExecutorObserver>` it is built with (empty unless contributed; see `vertique-workflow-core`). Each observer runs in its
own `try`, and a throwing observer is logged at warn level and affects nothing. A `QueueSnapshot` with the queue counts is read at most once per second
per executor instance and only while an observer is registered. The PostgreSQL wake-up source also reports the notification
queue usage as `QueueSnapshot.notificationQueueUsage` (the other fields of that snapshot are zero); the executor's own snapshots
carry usage `0`.

The deployment has no supervision and no liveness check: a crashed process leaves its claims to lease recovery, and process
health comes from the platform's database liveness check and the runtime's restart policy.

### WorkflowExceptionMapper

Stage-2 (workflow-boundary) exception mapper applied in the Pg runner's outer `recover` after the DB
layer's stage-1 mapper (`PgWorkflowExceptionMapper`) has run. Translates `DataAccessException` subtypes
into workflow-semantic exceptions rooted in the core hierarchy (e.g. `WorkflowConflictException` →
`ConflictException` for 409, `WorkflowPersistenceException` → `WorkflowTechnicalException` for 500);
already-semantic workflow exceptions and non-DB throwables pass through unchanged:

| Input type | Output |
|---|---|
| any already-semantic workflow exception | Passthrough unchanged |
| `OptimisticLockingFailureException` | `WorkflowConflictException` (cause-preserving) |
| `PessimisticLockingFailureException` | `WorkflowPersistenceException` (`retryable=true`) |
| `TransientDataAccessException` (deadlock, timeout, connection failure) | `WorkflowPersistenceException` (`retryable=true`) |
| Any other `DataAccessException` | `WorkflowPersistenceException` (`retryable=false`) |
| Anything else | Passthrough unchanged |

Message sanitization: the produced exception message includes only the failure kind, operation name,
cause simple class name, and SQL state (when available). The upstream `DataAccessException.getMessage()`
text is intentionally excluded to prevent SQL fragments or column names from reaching the workflow API
surface.

### WorkflowReminderComposeValidator

Per-plan composition guard that refuses a workflow plan declaring task reminders
(`HumanTaskNode.reminders() != null`) when no `WORKFLOW_EVENT` `WorkflowSideEffectRecorder` is
installed. It is a `WorkflowDefinitionValidator` that `WorkflowEngineModule` contributes into the
registry's validator set, so the registry runs it once for every plan it builds, at startup and for a
definition loaded or published later. A code plan that fails the check aborts application boot with an
`IllegalStateException` naming each `(definitionId vN stepId)` offender; a document that fails it is
refused at publication with the same text and never becomes resolvable. It is no longer a
`ComposeValidator`, and `RecorderRouter` no longer takes it as a constructor dependency. Its public
constructor takes the recorder set only.

---

## Extension Points

### Database Adapter SPIs (`dev.vertique.workflow.engine.spi`)

A database adapter implements these eight interfaces to provide persistence for the engine. All are
parameterized over `<TX>` (typically `SqlClient`):

| SPI | Responsibility |
|---|---|
| `WorkflowInstanceRepository<TX>` | Instance insert, optimistic update, migration update (which re-pins the definition scope, version and plan hash), and reads (by id without lock, with tx, with `FOR UPDATE`, by start scope, definition id and business key, filtered+paginated), and `findDefinitionContract(key, tx)`, which reads the contract name stored for a definition through the caller's transaction (the default returns empty, so a start replay on a node without the pinned plan refuses instead of guessing) |
| `WorkflowHistoryRepository<TX>` | History append, next-sequence generation, two list variants (recent, all; both leave out the `DECISION` and `STATE_BASELINE` entries, which the history service serves, so they do not count toward the recent limit nor carry a baseline's state into `query` and the migration context), and `appendDecision(DecisionEntry, tx)`, which appends an entry of the decision log (`DECISION` or `STATE_BASELINE`) with its command, step, outcome, definition, plan hash, state versions, executor and patch, assigns its sequence, and stores `entry_version = 2`; the default stores only the payload through `append`. `listRange(id, after, upTo, tx)` returns the enriched entries with sequence in `(after, upTo]`, ascending, without `STATE_BASELINE`, each as a `CommittedHistoryEntry` (the entry and the `kind` of the command it names, or `null`); it serves the history listeners after a commit, and its default fails with `UnsupportedOperationException`, so an adapter that does not override it loses (and logs) every notification while a listener is bound. An entry type the node does not know is skipped |
| `WorkflowDedupRepository<TX>` | Start lookup and race-safe claim/resolve by start scope for start, race-safe claim/resolve for signal (scoped and unscoped), task completion/reassignment, and service dispatch; `findTaskCompletion(taskId, key, tx)` reads, without writing, the fingerprint of a committed completion (the lock-free duplicate lookup) |
| `TaskReservations<TX>` | `markCompleting(taskId, commandId, tx)` moves an `OPEN` task to `COMPLETING` and stores the completion command as its owner; `reopen(taskId, commandId, tx)` moves a `COMPLETING` task owned by that command back to `OPEN` and clears the owner. Both are compare-and-set and answer `false` when the task is not in the expected state. Engine-internal wiring, not application API; `TaskStore` is unchanged |
| `BranchTokenRepository<TX>` | Fork/join branch token CRUD and status queries |
| `JoinStateRepository<TX>` | Fork/join state insert, read variants, open-for-workflow list, and the optimistic `decide` CAS |
| `WorkflowTransactionRunner<TX>` | Transaction boundary, optional isolation level, and the two-stage workflow exception mapping. The client it hands the work is a `SqlConnection` whose `transaction()` reports the underlying pending transaction; the deadline-guarded connection of `vertique-db` meets that. Its internal overload `inTransaction(level, deadline, work)` bounds the transaction (the executor's segment deadline) and returns `TransactionDeadlineExceededException` unchanged; the default fails with `UnsupportedOperationException` without running the work |
| `WorkflowCommandRepository<TX>` | Reads of the recorded command log for the execution probe: the number of runnable commands, and the deferred, parked, not-yet-available and stuck commands, of one instance or of every instance. Reads borrow a pooled connection and judge eligibility on the database clock. `findEarliestAvailabilityDelayMs()` is the executor's scalar scheduling hint, separate from the bounded diagnostic lists. `lockInstance(id, tx)` takes the instance's execution lock for the caller's transaction and waits up to the configured lock timeout; `tryLockInstance(id, tx)` takes it only if free and never waits; `nextQueueSequence(id, tx)` allocates the next position of the instance's command queue and requires the lock. The executor's members: `recordStart` inserts a `START` command in the caller's transaction; `peek(limit)` reads the claimable heads without locks; `defer(head)` defers a head the node cannot run in one self-contained transaction and returns the new deferral count, or `0` when it wrote nothing (busy lock, no longer claimable, already deferred into the future, or the instance pinned otherwise than the head observed); `claim(head, claimedBy, leaseSeconds, maxAttempts)` is one self-contained transaction that tries the lock, locks the row, checks `claimable` again and settles an ended instance's command, dead-letters a command that spent `maxAttempts`, or claims (the three-argument overload never dead-letters); `retryTransient` and `failCommand` are the dispositions of a failed segment (the first locks only the command row, the second tries the execution lock in a transaction of its own); `findStuck` and `countQueue` read the stuck report and the queue snapshot; `fence`, `complete` and `reject` run in the segment's transaction, fenced on the command being `CLAIMED` with the claim's token (`complete` and `reject` return the number of rows updated, and `0` means the claim no longer stands); `release` returns a claim whose segment did not run to `PENDING` with its attempt restored, in one statement that takes no lock. The signal members: `declaredSignalNames(pin, forkStepId, branchId, tx)` reads the names the pinned definition row declares for the addressed wait (empty when the row stores no declaration); `raiseOpenSignals(id, limit, tx)` creates the instance's queue row when missing and raises its open-signal count with one conditional update (`false` when the limit is reached); `lowerOpenSignals` undoes a raise; `recordSignal(...)` inserts the `SIGNAL` command with its name and address as target and its explicit carrier; `recordCompletion(instance, taskId, dedupKey, payloadJson, targetJson, tx)` inserts the `COMPLETION` command that owns a task's reservation; `recordTimer(spec, tx)` inserts a timer row and the `TIMER` command that fires it (the fire time comes from the database clock, a timeout stores the wait it belongs to as its target, and the timer row keeps the recording drive's durable context without an identity snapshot) and returns the timer id, the command id and the fire time; `reject(id, token, lastError, tx)`, `stale(...)` and `park(...)` settle or park the claimed command (`park` restores the attempt the claim spent); `parkedSignalsAt`, `parkedSignals`, `readParked`, `settleParked` and `requeueParked` read and settle parked commands in the segment's transaction without a claim token (`requeueParked` is the guarded `PARKED` to `PENDING` write that keeps position, id, attempts and counters); `deadlineBlocked` answers the deadline guard (a timeout that has fired no longer blocks, even while its own segment is open); `wasParked` tells whether a command was parked or dead-lettered before. The recovery members: `recordRetry(instance, tx)` and `recordMigration(instance, targetVersion, targetScope, tx)` insert the `RETRY` and `MIGRATION` commands in the caller's transaction (a migration stores its target version and scope as its target); `hasDeadLetters` tells whether the instance holds a `DEAD` command; `readStart` reads the `START` command with its stored payload; `supersedeDeadStart` settles a `DEAD` `START` as `SUPERSEDED` with history; `rearmTimers(instance, tx)` offers again, with one new command each and the original fire time, the timers whose last command failed or was settled stale because the instance had failed; `liveTasks(instance, tx)` lists the tasks that are still `OPEN` or `COMPLETING` and belong to no branch; `unsettledTargets(instance, kind, tx)` lists the targets of the `PENDING`, `DEFERRED` and `CLAIMED` commands of a kind, which `retry` and `migrate` read to record once; `reofferDeadLetters` returns every `DEAD` command to `PENDING` with `attempts` zero and its position kept, and raises the open-signal count once per re-offered `SIGNAL` without the limit check; `settleEnded` settles the `PARKED` and `DEAD` commands of an instance that has ended in one write, `REJECTED` (`CANCELLED` for a cancelled instance) with history, cancelling the task a dead-lettered completion reserves and lowering the open-signal count for the parked signals only. The cancel members: `cancelAll(instance, tx)` cancels every unsettled command of the instance in one statement under the execution lock, with a `COMMAND_CANCELLED` entry each, frees the slot of each open signal and returns the cancelled commands (`SettledCommand`: id, kind, task and timer) so the engine can cancel their timers and tasks; `cancel(id, token, lastError, tx)` settles a claimed command whose instance was cancelled, fenced like `complete`. A `CommandHead` of a `MIGRATION` carries the target version and scope, which the executor uses to make the target plan resident or to defer Every write that takes a `SIGNAL` out of its open states lowers the open-signal count in the same statement. The recording members (`recordStart`, `recordSignal`, `recordCompletion`, `recordRetry`, `recordMigration`) take the `CommandActor` the command is logged with, and `cancelAll` takes the canceller for the `COMMAND_CANCELLED` entries; the previous arities record `CommandActor.none()`. `recordTimer` and `rearmTimers` log `SYSTEM`. `fence` returns the actor on the `SegmentCommand`. `attributeHistory` and `attributeSegmentHistory` log the actor on the history entries a transaction wrote and are no-ops for a repository that keeps no actor columns. `attributeSegmentHistory(instance, fromSequence, fallback, command, pinned, tx)` also records on the segment's entries the command the segment executes (where an entry names none) and the definition the instance was pinned to when the segment began (where an entry records none; a migration gives it only to the entries before its repin); an entry that already records a value keeps it, and the default delegates to the arity without them, which ignores both, so an adapter that keeps command and definition columns must override the longer overload. `consumeLegacyBaseline(instance, tx)` clears the mark of an instance from before the decision log and answers whether it was set (the default answers `false`) |

The executor's INTERNAL `WorkflowSegments` interface (`dev.vertique.workflow.engine.executor`) has a default method
`announcing(instance, tx, work)`. An adapter that claims and settles commands in its own transactions calls it once it holds the
instance's execution lock, around the fence, the command's segment and the settlement, so that the history written in that
transaction is announced when it commits; the default only runs the work. The PostgreSQL adapter does so and also runs its
`defer`, dead-letter and failure-disposition transactions inside the history publisher.

An adapter that reads definition rows on an operation's transaction handle also implements the engine-internal
`TransactionalDefinitionReads<TX>` (`findDocument(key, tx)` answers a `StoredDocument` of the row's record and text without a
connection of its own; `selectionRead(startScope, definitionId, tx)` answers a `StartSelection`, the stored versions of the id in the start scope
and the default scope and the activation records of both, in one lock-free statement on the caller's handle, which a first start selects its
version from); the PostgreSQL store does. The engine declares the reads as an optional binding, and a store that does not implement them leaves
every start to select from the node's registry. The public `WorkflowDefinitionRegistryStore` is unchanged.

Register the SPI bindings in the adapter's Dagger module, then `include = {WorkflowEngineModule.class}`:

```java
@Module(includes = {WorkflowEngineModule.class, DbPostgresqlModule.class, ...})
public abstract class WorkflowPostgresqlModule {

    @Binds @Singleton
    abstract WorkflowInstanceRepository<SqlClient> instances(PgWorkflowInstanceRepository impl);

    @Binds @Singleton
    abstract WorkflowTransactionRunner<SqlClient> txRunner(PgWorkflowTransactionRunner impl);

    // ... other SPI @Binds ...
}
```

### Side-Effect Recorders (`@WorkflowRecorders`)

Applications contribute `WorkflowSideEffectRecorder<SqlClient>` implementations through Dagger
multibinding. The `@WorkflowRecorders` multibinding is declared by `WorkflowEngineModule`:

```java
@Provides @IntoSet @WorkflowRecorders
static WorkflowSideEffectRecorder<SqlClient> serviceRecorder(WorkflowServiceRecorder impl) {
    return impl;
}
```

`IntentKind.WORKFLOW_EVENT` is declared optional by default (`DEFAULT_OPTIONAL_INTENT_KINDS`), so
applications without a workflow-events module run without it — unless a plan declares reminders, in
which case `WorkflowReminderComposeValidator` refuses the plan (aborting startup for a code plan).

---

## Dependencies

- **cache-core** and **cache-caffeine** — the `CacheObserver` event vocabulary and the size-bounded, single-flight object cache behind the plan cache. Neither needs `cache.enabled`.
- **zjsonpatch** (`io.github.vishwakarma:zjsonpatch` 0.6.4, Apache-2.0) and its compile dependency **commons-collections4** 4.4 (Apache-2.0) — the RFC 6902 diff of the decision log. Versions are managed in the root `pom.xml`; both are listed in `THIRD-PARTY-NOTICES.md`.
- **workflow-core** — workflow DSL types, `WorkflowRegistry`, `WorkflowOperations` and transactional
  interfaces, `TaskStore<TX>` / `TimerStore<TX>` SPIs, side-effect SPI, exception hierarchy including
  `WorkflowPersistenceException`.
- **db-core** — `TransactionCompletion` (the commit signal that triggers history announcement), `IsolationLevel` (used in `WorkflowTransactionRunner`), `DataAccessException` hierarchy
  consumed by `WorkflowExceptionMapper`, and paged-query types.
- **deploy** — `VerticleDeployment` and `LifecyclePhase` for the `workflow-executor` deployment.
- **context** — `DurableContextPropagator` injected into `ForkJoinCoordinator` for branch durable-context capture.
- **vertx-json-schema** and **json-schema-validation** — compile and run the JSON Schemas that task snapshots store for decision payloads, through the framework's pattern-input guard (record-time completion validation).
- **vertx-sql-client** — `io.vertx.sqlclient.SqlClient` used as the concrete `<TX>` type throughout
  all SPI signatures and the engine implementation.
