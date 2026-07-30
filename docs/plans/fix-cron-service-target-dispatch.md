# Fix #41 — `SINGLE_INSTANCE` `@CronJob` never dispatches

## 1. Context & goal

Every annotated `@CronJob` resolves to a `CronTargetReference.ServiceTarget` with
`handlerAddress = null` (`CronJobRegistrar.java:323-324`). `CronScheduler.buildExecution`
copies that null into `JobExecution.handler` (`CronScheduler.java:519-542`), and
`job_executions.handler` is `NOT NULL` (`V1__create_job_tables.sql:9`).

- **`SINGLE_INSTANCE`** (always tracked): `tryInsert` violates NOT NULL, `onFailure`
  releases the in-flight guard — **the job never dispatches, ever**.
- **`EVERY_INSTANCE` + tracked**: `save` fails, degrades to untracked with a warning;
  execution history silently lost.

**Blast radius: total.** All four production `@CronJob`s in the repo are `SINGLE_INSTANCE`,
so every one is dead: `outbox-stale-lease-recovery`, `outbox-cleanup` (both
`OutboxMaintenanceServiceImpl`), `workflow-branch-recovery`
(`WorkflowBranchRecoveryServiceImpl`), `workflow-timer-recovery`
(`WorkflowTimerRecoveryServiceImpl`). ADR-0060 migrated the last three off working
`vertx.setPeriodic` loops onto `@CronJob`, silently disabling them.

**Why now:** three cluster-maintenance loops are not running in any deployment, and stale
outbox leases / orphaned workflow timers accumulate unbounded.
**Do-less alternative:** relax the column (§3, rejected on evidence).

**Goal:** `service:`-targeted cron jobs dispatch and persist a truthful execution record,
proven by tests that fail on today's code.

## 2. Pre-flight findings (verified against `origin/main` today)

1. **`NOT NULL` is load-bearing, not incidental.** `DelayedJobPoller.java:645` dispatches via
   `eventBusClient.send(execution.handler(), body)`; its own comment calls it "the persisted,
   validated non-blank address". Relaxing the column to fix cron would weaken a constraint the
   delayed-job subsystem depends on. **Decisive against Option A.** The issue's
   `job_schedules.handler`-is-nullable precedent does not transfer — a *schedule* is a
   template, an *execution* is a thing that happened at a concrete address.
2. **The dispatcher already resolves correctly** (`CronJobDispatcher.java:319-330`). Only the
   *persistence* path missed it. The fix moves existing resolution earlier, it does not invent it.
3. **Two dispatch entry points, not one** *(corrected — an earlier draft of this plan claimed a
   single funnel and was wrong)*. Scheduled timers (`scheduleNext`) and misfire recovery
   (`start()` passes `this::fire`) both reach `fire(job, scheduledAt)`, **but the `QUEUE_ONE`
   re-dispatch in `markCompleted` (`CronScheduler.java:478-505`) bypasses `fire` entirely** and
   calls `buildExecution` + `dispatcher.dispatch` directly, reusing the slot. Both entry points
   therefore need the resolution gate independently.
4. **`handlerAddress == null` for `ServiceTarget` is a *tested contract*** —
   `CronJobRegistrarTest.java:186` asserts it, and module.md:165 states late resolution is
   deliberate ("so the dispatched address always reflects the live service registry"). The fix
   therefore must **not** resolve at registration time; it resolves per fire. Contract preserved.
5. **Adjacent defect — slot/guard leak on a synchronous dispatch throw.**
   `acquireSlotAndRun` increments the slot counter *before* `dispatchAction.run()` with no
   `try/finally` (`CronConcurrencyManager.java:125`), so a synchronous throw from `dispatch(...)`
   means the completion callback never runs and both the slot and the in-flight guard leak.
   The only recovery is the execution-timeout timer, and **its severity depends on which Dagger
   module is installed** — verified, and *not* what my first pass assumed:
   - `CronPersistenceModule.java:108` passes `config.executionTimeoutMs()`, default **120 000 ms**
     (`JobCoordinatorConfig.java:71`) ⇒ the leak self-heals after ~2 min. On the `*/30` cadence
     three of the four dead jobs use, that is still ~4 consecutive ticks eaten per occurrence.
   - `CronModule.java:76` uses the 6-arg convenience constructor ⇒ `executionTimeoutMs = 0`
     ⇒ **no timer at all, the leak is permanent** for in-memory cron.
   Bounded or not, it is silent — the same failure shape as #41 itself.
6. **Field-observable symptom.** `tryInsert`'s SQL is `ON CONFLICT … DO NOTHING RETURNING id`;
   a NOT NULL violation is a constraint error, not a conflict, so the future *fails* rather
   than returning empty. Any affected deployment is logging
   `WARN  SINGLE_INSTANCE insert failed for '<job-id>': …` on every tick — a cheap way to
   confirm the diagnosis against real logs before/after the fix.
7. **No live-fire cron test exists anywhere.** `CronSchedulerTest` uses only `EventBusTarget`
   + mock repositories; `PgJobRepositoryIT` always passes a non-null handler;
   `OutboxMaintenanceSchedulePersistenceIT` never calls `start()` and passes a null
   `EventBusClient`. That gap is why this shipped.
8. **module.md is factually wrong about this field** (line 203): "`handlerAddress` … Resolved
   event bus address (derived from `target` at startup)". It is `null` for every
   `ServiceTarget`. Resolution-failure behavior is undocumented entirely.
9. **Config-only `service:` targets** (`CronJobRegistrar.java:388`) have the same null and get
   **no** resolvability validation — only `parse()` syntax checking. Zero deployed configs use
   them (no `cron.jobs.*` in any config file in the repo).
10. **Two-repo change.** Code + `module.md` live in the `sources/vertique` submodule
   (`vertiquehq/vertique`); the ADR lives in this governance repo (`vertiquehq/vertique-dev`).
   The submodule pin (`bc9f63f`) is **behind** its `origin/main` (`9b0a6c6`); `git diff` shows
   **no** changes under `vertique-job/` or `vertique-services/` between them, so these findings
   hold. Branch from `origin/main`, not the pin.
11. ~~Next free ADR number is **0200**~~ → **0201**. A parallel session claimed 0200 for the
    lifecycle-orchestrator record between planning and execution; see Amendments. This is exactly
    the marker race the plan warned about.
12. **Both maintainer docs already document this as an open defect** and each proposes the two
    candidate fixes: `docs/vertique-job-cron.md` §"`handlerAddress` is `null` for every service
    target…" and `docs/vertique-job-postgresql.md` §"`job_executions.handler` is `NOT NULL`, and
    one caller can write `null` into it" ("*Either the column becomes nullable … or the cron
    side must write the canonical [address]*"). Both go stale the moment the fix lands, so both
    are in the manifest. The cron doc also already flags the module.md staleness of finding 8.

## 3. Design decision

**Resolve the target address once per fire — immediately after in-flight admission succeeds,
before any slot is taken or any row is written.**

Placement matters and an earlier draft got it wrong. Resolving at the top of `fire()` — *before*
`tryAcquireInFlight` — would return early on a resolver failure and therefore skip
`concurrency.handleOverlap` (`CronScheduler.java:443-444`), so a `QUEUE_ONE` job whose previous
execution is still running would **silently lose that tick** instead of queueing it. Overlap
admission must be decided first; resolution is a gate only on fires that actually proceed.

In each of `fireSingleInstance` and `fireEveryInstance`, immediately after
`tryAcquireInFlight` returns `true`:

```java
String effectiveAddress = resolveEffectiveAddress(job);  // null ⇒ unresolvable, already logged
if (effectiveAddress == null) {
    concurrency.removeInFlight(job.id());   // release what we took; no slot, no DB write yet
    return;
}
```

And in the `QUEUE_ONE` re-dispatch inside `markCompleted` (which bypasses `fire` — finding 3),
re-resolve for the queued fire; on failure release **both** guards, because
`CronConcurrencyManager.markCompleted` deliberately *retains* the in-flight entry when a pending
fire exists:

```java
concurrency.removeInFlight(job.id());
concurrency.releaseSlot();
```

Then thread `effectiveAddress` through `buildExecution` and `dispatcher.dispatch(...)`, and
**delete** the resolution block in `CronJobDispatcher`. One resolution per fire ⇒ the persisted
`handler` is by construction the address that was dispatched to.

`resolveEffectiveAddress` applies the same null/blank rejection to **both** target variants —
a resolver throw, a blank resolved address, and a blank stored `EventBusTarget` address are one
failure mode. That is what makes the §4 invariant hold for every definition, not just
registrar-produced ones, and makes a NOT NULL violation from the cron path structurally
impossible.

Every signature touched is `private` or package-private. **No public API change, no migration,
no change to `CronJobDefinition`.**

### Rejected
- **Option A — nullable column.** Contradicted by finding 1. It also stores neither the stable
  target nor the resolved address on the execution row, so once a schedule changes, history
  cannot be reconstructed by joining back to it. (It does *not* break an existing dashboard —
  nothing in `vertique-management` reads the column; the argument is operator SQL and
  historical truth, not a live consumer.) And it needs a migration before the fix takes effect
  at all.
- **Hybrid (B + relax the column).** The constraint is the thing that caught this. Keeping it
  keeps the invariant enforceable.
- **Resolve at registration into `handlerAddress`.** Breaks the tested contract (finding 4) and
  defeats deliberate late resolution.

## 4. Contract Appendix (frozen)

All package-private / private — no public surface changes.

```java
// CronScheduler.java — new private helper
/**
 * Resolves the effective event bus address for one fire of {@code job}.
 *
 * <p>For a {@link CronTargetReference.ServiceTarget} the stable target id is resolved through
 * {@link ServiceTargetResolver}; for an {@link CronTargetReference.EventBusTarget} the stored
 * {@code handlerAddress} is used. Both variants are then subject to the same non-blank check,
 * so no caller can produce an execution record with a null or blank {@code handler}.
 *
 * <p>Returns {@code null} — after logging at ERROR with the job id and target — when the
 * resolver throws or the effective address is null/blank, so the caller can abandon the fire
 * and release whatever guard it already holds.
 *
 * <p>Resolving per fire rather than immediately before the event-bus send is sound only
 * because the built-in {@code DefaultServiceTargetResolver} snapshots its indexes with
 * {@code Map.copyOf} at construction. "Per fire" therefore means <em>fixed at fire
 * admission</em>. A mutable or reloadable {@code ServiceTargetResolver} implementation would
 * invalidate that assumption; see ADR-0201.
 */
// NOTE: {@code}, not {@link} — DefaultServiceTargetResolver is package-private in
// dev.vertique.services (DefaultServiceTargetResolver.java:31), so a link would not resolve
// from this package. Do not widen its visibility for a javadoc reference.
private String resolveEffectiveAddress(CronJobDefinition job);

// CronScheduler.java — fire entry points keep their signatures; each resolves internally
// *after* its own tryAcquireInFlight succeeds (see §3).
private void fire(CronJobDefinition job, Instant scheduledAt);              // unchanged
private void fireSingleInstance(CronJobDefinition job, Instant scheduledAt); // unchanged
private void fireEveryInstance(CronJobDefinition job, Instant scheduledAt);  // unchanged

// CronScheduler.java — the one threaded signature
private JobExecution buildExecution(CronJobDefinition job, Instant scheduledAt, JobState state,
                                    String effectiveAddress);

// CronJobDispatcher.java — address supplied by caller; resolver dependency removed
void dispatch(CronJobDefinition job, Instant scheduledAt, JobExecution execution,
              String effectiveAddress, CompletionCallback completionCallback);
CronJobDispatcher(Vertx vertx, EventBusClient eventBusClient, JobRepository repository,
                  List<JobInterceptor> interceptors, long executionTimeoutMs,
                  long progressFlushIntervalMs, String nodeId,
                  DispatchEnvelopeBuilder envelopeBuilder);   // ServiceTargetResolver param removed
```

**Invariants**

1. `JobExecution.handler` is non-blank for **every** cron execution the scheduler persists —
   enforced at the single choke point above for both target variants, so the invariant does not
   depend on `CronJobDefinition` or `CronTargetReference` validating their own address fields
   (neither does: `CronJobDefinition.java:51`, `CronTargetReference.java:81`).
2. The address recorded on the execution equals the address dispatched to for that fire.
3. Overlap admission is decided **before** resolution, so a resolver failure can never convert a
   `QUEUE_ONE` overlap into a dropped tick.
4. A failed resolution releases exactly the guards its caller holds: the in-flight entry in the
   two `fire*` paths (no slot taken yet), and **both** the in-flight entry and the slot in the
   `markCompleted` queued path, where `CronConcurrencyManager.markCompleted` intentionally
   retains in-flight when a pending fire exists.

## 5. Slice plan

Execution order is prescriptive. **S1 and S2 are the implementation slices** — each is one
red→green unit landing as two commits (failing tests, then the code that turns them green, with
its canonical `module.md` update in the same commit). **L0, L3, L4 are lifecycle/closeout steps**
that carry no proof obligations of their own.

Full sequence:

> L0 (worktree + plan) → S1 → S2 → `/simplify` + `build-validator` + security/Codex review loop
> → **L3 (remove the source plan file)** → merge the source PR → **L4 (temporary governance
> worktree: gitlink + maintainer docs via governance PR)** → close #41, remove worktrees.

The plan file is removed **before** the source PR merges — it is a commit on the source branch,
so doing it afterwards would need a second source PR.

### L0 — worktree + persist the plan · `routine` *(lifecycle)*
Unit = GitHub issue #41 (a bug), so one worktree for the issue. The code lives in the
submodule, so the worktree is created **in `sources/vertique`**, branched from its
`origin/main` (not the stale pin):

```
git -C sources/vertique worktree add \
  ../../.claude/worktrees/issue-41-cron-dispatch -b fix/cron-service-target-dispatch origin/main
```

Governance-repo work splits by whether it is docs-only:
- **S1's ADR commit** (ADR-0201 + `adr/product/README.md`) is docs-only → fast-forward to
  governance `main` per `git-workflow.md` § Exception: docs-only changes.
- **L4's commit** advances the `sources/vertique` gitlink alongside the maintainer docs, so it
  is *not* docs-only → it needs a governance PR from a temporary worktree created at L4 time; no
  long-lived second worktree is required for a single commit.

Commit the plan file. `docs: add implementation plan for cron service-target dispatch fix`

*Verification:* `git -C .claude/worktrees/issue-41-cron-dispatch log --oneline -1` shows the plan
commit and `git status` is clean.

### S1 — service-target resolution · `critical`

**Red.** `CronSchedulerTest` — add a `@Nested` "service-target execution handler" group, plus one
constraint guard in `PgJobRepositoryIT`. Each row states whether it is red today or an
explicitly-labelled green-today guard.

| Test | Given / When / Then |
|---|---|
| `singleInstanceServiceTargetPersistsResolvedHandler` | Given a `SINGLE_INSTANCE` job with `ServiceTarget("svc.op")`, `handlerAddress=null`, and a resolver mapping it to `"ns/svc/op"`; when it fires; then the `JobExecution` captured at `tryInsert` has `handler() == "ns/svc/op"`. *(fails today: null)* |
| `everyInstanceServiceTargetPersistsResolvedHandler` | Same for tracked `EVERY_INSTANCE` via `save`. *(fails today: null)* |
| `queuedFireServiceTargetPersistsResolvedHandler` | `QUEUE_ONE` requeue path through `markCompleted`; the queued execution's `handler()` is the resolved address. *(fails today: null)* |
| `unresolvableServiceTargetSkipsFireWithoutTouchingRepository` | Given a resolver throwing `IllegalArgumentException`; when it fires; then `tryInsert`/`save` are never called and no dispatch occurs. *(fails today: the throw happens inside dispatch, after the insert)* |
| `unresolvableServiceTargetLeavesJobFirableOnNextTick` | Same, but a *second* fire after the resolver starts succeeding dispatches normally — proves no guard/slot leak. *(fails today: the first fire's throw strands the guard, and the test harness runs with `executionTimeoutMs = 0`, so nothing releases it)* |
| `serviceTargetDispatchLandsAtTheResolvedAddress` | Given the same job and a real Vert.x consumer registered at `"ns/svc/op"`; when it fires; then that consumer receives the envelope — i.e. the address recorded on the execution is the address actually sent to, asserted against the *same* string. *(fails today: tryInsert rejects the null handler, so the consumer is never reached)* |
| `unresolvableTargetDuringOverlapStillQueuesTheTick` | Given a `QUEUE_ONE` job whose previous execution is in flight, and a resolver that fails on this tick then recovers; when the overlapping tick arrives and the running execution completes; then the queued fire runs at the recovered address. Pins invariant 3. **Green today, must stay green** — today the resolver is never touched in `fire`, so overlap already wins; the guard exists to reject the resolve-before-admission placement this plan considered and dropped. |
| `eventBusTargetStillDispatchesToItsAddress` | **Green today, must stay green** — regression guard that `EventBusTarget` jobs still dispatch to their stored `handlerAddress`. |
| `saveRejectsExecutionWithNullHandler` *(in `PgJobRepositoryIT`)* | Given a `JobExecution` with `handler == null`; when `save` and `tryInsert` are called; then both futures fail. **Green today, must stay green** — it pins `job_executions.handler NOT NULL` as an asserted contract rather than an accident of DDL, and is the schema-side counterpart that makes the producer-side invariant above meaningful. A future attempt to drop the constraint has to argue with a red test. |

The last three are explicitly labelled green-today guards; every other test above is red on
today's code.

Commit: `test(job-cron): add failing tests for service-target execution handler`
(the `PgJobRepositoryIT` guard rides along in the same red-test commit)

**Green.** Implement §3 + §4 exactly, including the `resolveEffectiveAddress` javadoc that
records the fixed-at-fire-admission assumption. **Prove the tests can fail**: revert the
`buildExecution` line locally and confirm red before committing (per project memory on
tests-that-cannot-fail).

Same commit — **canonical module documentation, per CLAUDE.md's same-slice contract**:
`vertique-job-cron`'s `module.md` corrects the false `handlerAddress` row (finding 8), documents
that an unresolvable `service:` target skips that fire with an ERROR and retries on the next
tick, and notes that a tracked execution records the resolved address in
`job_executions.handler`.

Commit: `fix(job-cron): resolve service targets before persisting cron executions`

**Governance companion — ADR-0201 belongs to this slice** (§7). It records the decision this
slice implements, so it is authored here rather than deferred to a lifecycle step. Because it
lives in the other repository it lands as its own commit there, together with its
`adr/product/README.md` index row and next-number bump. **Re-check the number against `main`
immediately before merge** — parallel worktrees race the marker. L4 is the mechanical
publication step for maintainer docs, not the deciding one. Commit (governance repo):
`docs(adr): record cron service-target resolution decision`.

*Verification for this slice:* `./mvnw -ntp -pl vertique-job/vertique-job-cron -am test` green,
and `./mvnw -ntp -pl vertique-job/vertique-job-postgresql -am verify -Dit.test=PgJobRepositoryIT`
green.

### S2 — synchronous-dispatch-throw containment · `critical`
Finding 5.

**Red.**

| Test | Given / When / Then |
|---|---|
| `synchronousDispatchFailureReleasesSlotAndInFlight` | Given a spied `EventBusClient` whose `send` always throws and a 1-second cron; when two ticks elapse; then `send` is attempted **twice** — proving the first throw released both the in-flight guard and the slot. Wired with `executionTimeoutMs = 0` (matching `CronModule`) so the assertion isolates the new containment, not the timeout timer. *(fails today: attempted once, job stranded)* |
| `synchronousRepositoryFailureDuringContainmentStillReleases` | Given the same, plus a `JobRepository` whose `completeExecution` **throws synchronously**; when the job fires twice; then `send` is still attempted twice. `JobRepository` is a custom-adapter SPI (`JobRepository.java:13`), so failure persistence must not be able to defeat the release. *(fails today, and would still fail if the callback were not in a `finally`)* |

Commit: `test(job-cron): add failing tests for synchronous dispatch-throw leak`

**Green.** In `CronJobDispatcher.dispatch`, wrap **only the post-registration block** — the
`SchedulerMdcScope` / interceptor `onDispatch` / `eventBusClient.send` tail (currently
`CronJobDispatcher.java:319-330`) — in `try/catch (Exception)`. The boundary is deliberate:
envelope construction happens at `CronJobDispatcher.java:196`, *before* the per-execution
resources and therefore before the latch this containment relies on exists, so a throw there
has nothing to clean up through this path. **Envelope-builder exception safety stays deferred**
(§10) — this slice does not silently widen into it.

Fixed ordering, with the callback in a `finally` so nothing downstream can strand the guards:

1. **Latch** on `activeConsumers.remove(consumer)` — the same one-shot latch the timeout handler
   uses (`CronJobDispatcher.java:228`), so completion fires exactly once and cannot double-release
   against a later timeout.
2. **Local cleanup** — cancel the timeout and progress timers, unregister both consumers, drop
   the `activeExecutions` entry.
3. **Guarded failure persistence** — mark the execution `FAILED` when tracked, inside its own
   `try/catch` so a synchronous repository throw is logged, not propagated.
4. **`finally` → `completionCallback.onCompleted(job)`** — the existing single release path.

Commit: `fix(job-cron): release cron guards when dispatch throws synchronously`

*Module documentation:* `vertique-job-cron` — no additional `module.md` change; the
resolution-failure behavior documented in S1 already covers what an application observes. This
slice changes internal containment only.

### L3 — remove the persisted plan · `routine` *(closeout, on the source branch)*
Delete `docs/plans/fix-cron-service-target-dispatch.md` as the source branch's final docs commit
— its content lives on in git history and `main` stays free of stale plans.

**This must happen before the source PR merges.** It is a commit *on the source branch*, so once
that PR is merged it could only be done by opening a second source PR.

`docs: remove implementation plan for cron service-target dispatch fix`

*Verification:* `git diff main...HEAD --name-only | grep docs/plans/` returns nothing.

### L4 — merge, then publish governance docs · `routine` *(closeout)*
The canonical `module.md` landed in S1's green commit and ADR-0201 was authored in S1 (§7). This
step merges the source work and then publishes the **governance-repo maintainer docs plus the
submodule gitlink advance**. All three docs currently describe this bug as an open defect
(finding 12); leaving them would ship documentation that is materially wrong about shipped
behavior.

**Cross-repository landing order — do not invert.** The maintainer docs describe behavior as
shipped, so they must not merge before the behavior exists:

1. Merge the source PR in `vertiquehq/vertique` (draft → CI green → ready → merge). The plan
   file is already gone from the branch (L3).
2. Create a **temporary governance worktree** — the primary governance worktree stays on `main`
   and is never branched in place (`git-workflow.md`):
   ```
   git worktree add .claude/worktrees/issue-41-governance -b chore/cron-dispatch-pin-and-docs main
   ```
3. In that worktree, advance the `sources/vertique` gitlink to the merged commit **and** apply
   the maintainer-doc edits below, as one commit.
4. Open a **governance PR** for it. The gitlink makes this commit non-docs-only, so the
   direct-to-`main` exception does not apply — see §6.
5. After it merges, remove both worktrees (`/clean_gone`) and close issue #41 — the unit is done
   (`git-workflow.md` § Worktree lifecycle).

ADR-0201 is exempt from that ordering — it records a *decision*, not shipped behavior, it is
docs-only, and it lands with S1.

*Governance repo — maintainer references:*
- `docs/vertique-job-cron.md`: rewrite §"`handlerAddress` is `null` for every service target,
  and one consumer cannot take that" to describe the shipped resolution seam; update the
  dispatch-resolution claim at ~line 89 (resolution now happens in the scheduler's `fire*`
  paths after in-flight admission, not in the dispatcher, and the queued `QUEUE_ONE` path
  resolves separately); drop the "null `handlerAddress` → `NOT NULL handler` path is untested"
  known-gap and add the new coverage; drop the now-fixed first bullet under
  §"Packaged-Document Discrepancies".
- `docs/vertique-job-postgresql.md`: rewrite §"`job_executions.handler` is `NOT NULL`, and one
  caller can write `null` into it" — it poses exactly this fix as one of two options; record
  which was taken and why. Add the new `PgJobRepositoryIT` case to its test-navigation table.
- `docs/vertique-inbox-outbox-postgresql.md`: rewrite §"Cluster-singleton cron cannot currently
  persist its execution row" — the insert failure is fixed, so the maintenance jobs now run.
  **Keep** its "unproven end to end" caveat, narrowed: no test in this repo drives a real
  `CronScheduler` against a real database, and that gap is tracked (§10). Do not overclaim.

Commit (governance repo, on a branch → PR): `chore: advance vertique pin and refresh job and
outbox maintainer docs after the cron dispatch fix`. `chore` rather than `docs` because the
commit moves the submodule pointer as well as prose.

*(`adr/product/README.md`'s index row and next-number marker land with the ADR itself in S1,
not here — an ADR and its index entry should never be separated.)*

*Verification:* `scripts/verify-module-docs.sh` passes (BOM/document/index parity, packaged-link
containment, no legacy paths).

## 6. Artifact manifest

**Submodule `sources/vertique` (PR → `vertiquehq/vertique`)**

*Modified*
- `vertique-job/vertique-job-cron/src/main/java/dev/vertique/job/cron/CronScheduler.java`
- `vertique-job/vertique-job-cron/src/main/java/dev/vertique/job/cron/CronJobDispatcher.java`
- `vertique-job/vertique-job-cron/src/test/java/dev/vertique/job/cron/CronSchedulerTest.java`
- `vertique-job/vertique-job-cron/src/main/resources/META-INF/vertique/module.md`
- `vertique-job/vertique-job-postgresql/src/test/java/dev/vertique/job/postgresql/PgJobRepositoryIT.java`

*New*
- `docs/plans/fix-cron-service-target-dispatch.md` *(matches the branch name; removed in L3)*

*Deleted* — none.

No `pom.xml` changes and no new module dependencies: dropping the live-fire IT (§11) removed
the only one the earlier draft needed.

**Governance repo `vertique-dev`**

*New*: `adr/product/0201-resolve-cron-service-targets-before-persisting-execution.md`
*Modified*: `adr/product/README.md`, `docs/vertique-job-cron.md`,
`docs/vertique-job-postgresql.md`, `docs/vertique-inbox-outbox-postgresql.md`,
`sources/vertique` *(gitlink — advanced to the merged source commit in L4)*

**The gitlink is why L4 needs a PR.** A submodule pointer is not a prose file, so the L4 commit
is **not** docs-only and does not qualify for `git-workflow.md`'s direct-to-`main` exception.
S1's governance commit (ADR-0201 + its `adr/product/README.md` index row) *is* docs-only and may
fast-forward to `main`; L4's commit carries the gitlink and goes through a governance PR.

**Module documentation decisions**
- `vertique-job-cron` — canonical `module.md` **modified in S1's green commit** (application-facing
  behavior + a factual correction), satisfying CLAUDE.md's same-slice contract; maintainer
  `docs/vertique-job-cron.md` **modified in L4** (resolution topology moves dispatcher →
  scheduler-after-admission; the open-defect and known-gap sections become stale on fix).
- `vertique-job-postgresql` — maintainer `docs/vertique-job-postgresql.md` **modified** (its
  open-defect section poses this exact fix as one of two options; test navigation gains the new
  case). Canonical `module.md`: *no documentation impact — test-only change; no
  application-facing API, config, wiring, behavior, or schema change.*
- `vertique-inbox-outbox-postgresql` — maintainer `docs/vertique-inbox-outbox-postgresql.md`
  **modified** (its §"Cluster-singleton cron cannot currently persist its execution row" is
  fixed by this change). Canonical `module.md`: *no documentation impact — no source change in
  this module; the maintenance jobs' declared behavior is unchanged, only newly reachable.*
- `vertique-job-core`, `vertique-services`, `vertique-workflow-*` — *no documentation impact —
  not modified and no doc in them describes this path.*

## 7. ADRs to write

| ADR | Title | Written by | Decision recorded |
|---|---|---|---|
| `adr/product/0201-resolve-cron-service-targets-before-persisting-execution.md` | Resolve cron service targets before persisting the execution record | **S1** | Why `job_executions.handler` stays `NOT NULL` (the delayed-job poller dispatches to it — `DelayedJobPoller.java:645`) rather than being relaxed to match `job_schedules.handler`; why resolution stays per-fire rather than moving to registration (`CronJobRegistrarTest.java:186` pins `handlerAddress == null`, and late resolution is deliberate so the address tracks the live registry); and the skip-and-log-not-throw policy for an unresolvable target, replacing today's throw-and-stall. The ADR must **not** claim this protects an existing dashboard — nothing in `vertique-management` reads the column (§11). |

No second ADR is warranted: S2's containment fix implements the failure policy this ADR already
states, and the schema-side pin folded into S1 is a test-strategy call, not an architectural one.

**As built: ADR-0201**, committed to governance `main` as `b423ca1`. The plan reserved 0200; a
parallel session took it for the lifecycle-orchestrator record before this branch got there. The
re-check-before-merge instruction did its job. Still re-check at merge time.

## 8. Risks & edge cases

- **Double-release in S2.** Mitigated by reusing the existing `activeConsumers.remove(consumer)`
  latch rather than inventing a second one.
- **Test timing.** No timing-dependent *integration* test is added — the deferred live-fire IT
  was the flake risk worth avoiding (§11). Several new **unit** tests are timer-driven
  (`serviceTargetDispatchLandsAtTheResolvedAddress`, both S2 tests), which matches existing
  practice: `CronSchedulerTest` already arms real timers in 18 tests. Stated plainly rather than
  claimed away. `PgJobRepositoryIT` is touched, so the change is **draft-PR until CI is green**
  (`workflow.md` § PR Workflow for IT-Touching Changes).
- **Residual proof gap, stated honestly.** Nothing in this PR exercises real `CronScheduler` +
  real Postgres in one process. The chain is proven in two deterministic halves (cron never
  emits null / the schema rejects null). That is a deliberate trade of composition coverage for
  CI stability, routed in §11 with a re-entry trigger.
- **Behavior change, deliberate and consumer-visible:** an unresolvable `service:` target now
  skips that fire and logs an error, instead of throwing and stalling the job until the
  execution timeout expires (or forever under `CronModule`). Strictly better; recorded in
  ADR-0201.
- **Two-repo commit split** — the ADR cannot land in the same PR as the code. The code PR
  references it by number.
- **Mutable-resolver risk — `risk-accepted`.** `ServiceTargetResolver` is a *public interface*;
  only the built-in `DefaultServiceTargetResolver` is verifiably immutable (`Map.copyOf`,
  `DefaultServiceTargetResolver.java:47`). A third-party mutable or reloadable implementation
  could change an address between fire admission and the event-bus send, in which case this
  design dispatches to the earlier address. Persisted-equals-dispatched still holds; only
  freshness would not. Accepted, and stated plainly in ADR-0201 and the javadoc: **"per fire"
  means the address is fixed at fire admission.** Re-entry trigger: the framework shipping or
  sanctioning a reloadable resolver.

## 9. Verification

- S1 / S2: `./mvnw -ntp -pl vertique-job/vertique-job-cron -am test`
- S1 (schema guard): `./mvnw -ntp -pl vertique-job/vertique-job-postgresql -am verify -Dit.test=PgJobRepositoryIT`
- Full: `./mvnw -ntp clean verify` via `build-validator`
- **Module-documentation gate:** `scripts/verify-module-docs.sh` — BOM/document/index parity,
  packaged-link containment, no legacy module-doc paths. Run before handoff (L4).
- **Coverage:** read the aggregate report at
  `vertique-coverage-report/target/site/jacoco-aggregate/` and confirm the project stays at or
  above the 80% target. Note the baseline for `vertique-job-cron` is **~78.5%, already below
  target** — the S1/S2 tests are expected to raise it, and this must be checked rather than
  assumed. If it still lands under 80%, that is a finding to report, not to absorb.
- Acceptance walkthrough vs issue #41: (a) SINGLE_INSTANCE now inserts and dispatches — S1
  `singleInstanceServiceTargetPersistsResolvedHandler` + `serviceTargetDispatchLandsAtTheResolvedAddress`;
  (b) tracked `EVERY_INSTANCE` persists — S1 `everyInstanceServiceTargetPersistsResolvedHandler`;
  (c) `job_executions.handler` is never null from cron — S1, both producer side
  (`unresolvableServiceTargetSkipsFireWithoutTouchingRepository`) and schema side
  (`saveRejectsExecutionWithNullHandler`);
  (d) no regression for `EventBusTarget` — S1 `eventBusTargetStillDispatchesToItsAddress`;
  (e) overlap semantics preserved — S1 `unresolvableTargetDuringOverlapStillQueuesTheTick`.
- **Surefire `-Dtest` guard** (project memory): use commas, never `+`, when selecting multiple
  tests, and confirm the run count is non-zero rather than trusting `BUILD SUCCESS`.
- Mechanical completeness: `grep -rn "serviceTargetResolver" vertique-job/vertique-job-cron/src/main`
  returns hits only in `CronScheduler`, none in `CronJobDispatcher`.

## 10. Out-of-scope & deferral routing

| Item | Route | Why safe to defer / residual risk |
|---|---|---|
| **`MisfirePolicy.FIRE_ALL` only ever runs one missed fire.** `CronMisfireRecovery.java:154-156` loops synchronously calling `fire`, but `fireSingleInstance` releases the in-flight guard only in the async `tryInsert` callback, so iterations 2..N hit "already in-flight" and return. Surfaced by the architecture review; **independently verified**. | GitHub issue | In the `fire()` path this PR touches, so the Adjacent Defects Rule was weighed. Deferred as *disproportionate*: the fix is an async-chaining redesign of misfire sequencing, a different correctness axis from target resolution. Safe because `FIRE_ALL` is opt-in and **no job in the repo uses it** (all four use the `SINGLE_INSTANCE` default `FIRE_NOW`). **Residual risk:** an application that opts into `FIRE_ALL` silently gets one catch-up fire instead of N — under-execution, not data loss. *Flagged for the user to promote if wanted.* |
| Registration-time resolvability validation for config-only `service:` targets (`supportedTargetIds()` exists; the registrar already injects an unused resolver, and `DelayedJobService.java:299-305` sets the sibling precedent at enqueue time) | GitHub issue | Changes startup-failure semantics — a consumer-visible compatibility change with zero deployed users (no `cron.jobs.*` in any config in the repo). Re-entry trigger: adopting "every enabled configured cron target must resolve at startup", or a real incident. |
| General dispatcher exception-safety — a synchronous throw from the envelope builder, codec, or consumer registration still leaks slot + guard + consumers + a `PROCESSING` row (S2 covers the tail of `dispatch`, not a full terminal-state rollback design) | GitHub issue | S2 removes the only trigger reachable today and contains the tail. A complete design needs terminal-state + resource rollback, more than #41 warrants. |
| Full live-fire cross-module IT (real `CronScheduler` + real `PgJobRepository` + testcontainer) | GitHub issue | Re-entry trigger: a second cron/persistence composition defect, or `fire()` gaining a test-visible trigger that removes the wall-clock dependency. See §11. |
| `PgJobRepository.java:533` builds `"eventbus:" + handler` for a schedule row with both `target` and `handler` null | GitHub issue | Unreachable for rows written by current code; data-integrity hardening only. |
| `adr/product/README.md` index missing entries for 0192/0193/0195 | GitHub issue | Index hygiene; S1's ADR commit adds the 0200 row and bumps the marker but does not backfill the older gaps. |
| Executing the outbox/workflow maintenance backlog accumulated while the jobs were dead | GitHub issue (operational) | Not a code defect; needs an operator decision per environment. |

## 11. Independent architecture review

Consulted `vertique-codex-architect` (gpt-5.6-sol, high reasoning) with the draft design and an
explicit instruction to attack it. `codex_session_id: 019fae48-f6f9-7652-8830-0b2b8df857b4`.

**Converged.** Verdict: Option B, no migration, not the hybrid.

*Adopted — changed the plan:*

1. **Dropped the live-fire IT** (the earlier live-fire slice) in favour of a deterministic
   `PgJobRepositoryIT` null-handler-rejected case. The argument that won: driving a real
   `CronScheduler` requires a `*/1` cron and a wall-clock await, which is exactly the flake
   class `testing.md` treats as mandatory-to-avoid, and CI is the gate for IT changes here.
   Pinning the constraint from the schema side gets the composition covered at both ends with
   zero timing risk — and removes the new module dependency entirely.
2. **Corrected a wrong pre-flight fact of mine.** I had written that the execution timeout is
   disabled by default, making the slot leak permanent. Verified: `CronPersistenceModule`
   passes a 120 s default, so for DB-backed cron it self-heals; only `CronModule` (in-memory)
   passes 0. Finding 5 now states both. This weakens the S2 severity claim, not the fix.
3. **Javadoc the snapshot assumption** on the new choke point (S1) — fire-time resolution is
   sound only because *the built-in* `DefaultServiceTargetResolver` copies its indexes at
   construction. Scoped deliberately: `ServiceTargetResolver` is a public interface and its
   contract does **not** require immutability, so "every implementation is immutable" would be
   an unsupported claim. Carried as `risk-accepted` in §8 instead.
4. **New adjacent defect surfaced and independently verified:** `MisfirePolicy.FIRE_ALL` runs
   only one missed fire. Routed in §10 with an explicit residual-risk statement.

*Rejected, with reason:*

- **Codex's claim that no schema-side test is needed** ("unit test + existing `PgJobRepositoryIT`
  compose"). This bug class *is* a composition bug — two subsystems each correct alone, wrong
  when joined — so proving by composition is precisely what failed here. Hence the schema-side guard exists, in
  the cheap deterministic form rather than the expensive timing-dependent one.
- **Codex's dashboard-fidelity argument for keeping `handler` populated.** I grepped
  `vertique-management`: nothing reads `job_executions.handler`. The historical-truth argument
  still stands on ad-hoc operator SQL and on `DelayedJobPoller`'s hard dependency, so the
  verdict is unchanged — but the ADR must not claim it protects an existing dashboard.

*Agreed independently (no change):* Option A rejected on the delayed-job dependency; the hybrid
rejected as removing a defense rather than adding one; registration-time validation deferred;
no startup window exists where timers can fire before the registry is built (verified through
`DispatchModule` construction order and `CronLifecycleVerticle.start()`).

*Unresolved:* none. Codex offered a falsification handle — B is wrong if a target address can
change between fire time and send time — which cannot occur with the built-in resolver, and
which §8 carries as an accepted risk with a re-entry trigger.

### Second review pass (plan red-team)

An adversarial review of this plan returned **approve-with-required-changes, 0 blockers**, and
found one design flaw plus three process defects. All four are applied above:

1. **Resolution placement was wrong** — resolving before `tryAcquireInFlight` would skip
   `handleOverlap` and silently drop a `QUEUE_ONE` tick on a transient resolver failure.
   Moved to *after* in-flight admission (§3), with `unresolvableTargetDuringOverlapStillQueuesTheTick`
   pinning it. It also **refuted my finding 3** — the `QUEUE_ONE` re-dispatch bypasses `fire`
   entirely — which finding 3 now states correctly.
2. **S2's containment did not guarantee release** — failure persistence went through a custom
   `JobRepository` SPI that can throw synchronously. Order is now frozen as latch → local
   cleanup → guarded persistence → callback in `finally`, with a repository-throws test.
3. **Slice structure** — red and green are now one implementation slice with two commit
   checkpoints (S1, S2), and plan/docs/cleanup steps are relabelled L0/L3/L4 as lifecycle steps
   carrying no proof obligations.
4. **Canonical `module.md` moved into S1's green commit**, per CLAUDE.md's same-slice
   documentation contract; only the governance-repo maintainer docs remain in L4.

Two suggestions also applied: the non-blank invariant is now enforced for **both** target
variants at the common choke point (§3, invariant 1), and §9 gained the
`scripts/verify-module-docs.sh` gate plus an explicit coverage check — with the honest note
that `vertique-job-cron`'s baseline is ~78.5%, already under the 80% target.

### Third review pass (plan red-team, round 2)

Returned **approve-with-required-changes, 0 blockers** — all four substantive findings from
round 2 confirmed resolved, with five mechanical corrections. All are applied:

1. **The standalone schema slice violated the red→green gate** (its only test was green from
   the start) and, by extension, ADR-0201 was owned by a *lifecycle step* rather than a slice.
   `saveRejectsExecutionWithNullHandler` is now folded into S1's red-test commit as an
   explicitly-labelled green constraint guard, the standalone slice is gone, and **ADR-0201
   now belongs to S1** (§7). L4 is mechanical publication only.
2. **`unresolvableTargetDuringOverlapStillQueuesTheTick` was mislabelled red.** It passes today
   — current code never touches the resolver inside `fire`, so overlap already wins. Relabelled
   a green regression guard; it still earns its place by rejecting the resolve-before-admission
   design.
3. **S2's catch boundary was ambiguous.** Now stated explicitly: the post-registration
   MDC/interceptor/send block only. Envelope construction (`CronJobDispatcher.java:196`) happens
   before the latch exists and stays deferred (§10) — no silent scope widening.
4. **The frozen javadoc used an unresolvable `{@link}`.** `DefaultServiceTargetResolver` is
   package-private (`DefaultServiceTargetResolver.java:31`); switched to `{@code}`, with a note
   not to widen its visibility for a doc link.
5. **Cross-repository landing order made explicit** in L4 — source PR merges, submodule pin
   advances, *then* maintainer docs publish, so governance docs never describe unshipped
   behavior as shipped. ADR-0201 is exempt: it records a decision, not behavior.

One suggestion **not** adopted: replacing S2's two-tick timing with a deterministic trigger.
`fire()` is private and there is no existing test seam to drive it directly; adding one purely
for a test would widen the change's surface. The test stays consistent with existing practice in
this class — `CronSchedulerTest` calls `scheduler.start()` 18 times and uses a 1-second
(`* * * * * *`) expression in 16 of them, and those are stable in CI. Flagged rather than
silently ignored.

### Fourth and fifth review passes (plan red-team, rounds 3–4)

Round 3 returned 11 PASS / 1 FAIL and round 4 returned 12/12 with one lifecycle defect. Both
findings were mechanical and are applied:

1. **The governance manifest omitted the submodule gitlink.** `sources/vertique` is now listed
   under governance *Modified* (§6), and because a gitlink is not a prose file, **L4 goes through
   a governance PR** rather than the docs-only direct-to-`main` exception. S1's ADR commit is
   genuinely docs-only and still fast-forwards.
2. **The closeout order was not executable.** The plan file lives on the *source* branch, so
   removing it after the source PR merged would have required a second source PR. L3 (plan
   removal) and L4 (merge + governance publication) are now in that order, and §5 states the full
   sequence explicitly.
3. **The governance branch needed a home.** L4 now creates a temporary governance worktree
   (`.claude/worktrees/issue-41-governance`) rather than branching the primary worktree in place,
   per `git-workflow.md`.

## Amendments

Deviations from the approved plan discovered during execution. Each is a correction of a
verified fact or an as-built note, so none required re-approval (`planning.md` § Mid-flight
amendments).

| Date | Trigger | Change |
|---|---|---|
| 2026-07-29 | Execution — ADR marker race | Plan reserved **ADR-0200**; a parallel session claimed it for the lifecycle-orchestrator record between approval and execution. Renumbered to **ADR-0201** (governance `b423ca1`). All plan references updated. The plan's own "re-check against `main` immediately before merge" instruction is what caught it. |
| 2026-07-29 | Execution — S1 red-test run | `serviceTargetDispatchLandsAtTheResolvedAddress` **reclassified red → green-today guard**. The plan predicted it would fail because `tryInsert` rejects a null handler — but `CronSchedulerTest` uses a *mock* repository, which accepts one, so the dispatcher's send-time resolution still lands the message. It only goes red against real DDL. Test unchanged; its value is pinning recorded-address == dispatched-address once the fix lands. |
| 2026-07-29 | Execution — S1 red-test run | `unresolvableTargetDuringOverlapStillQueuesTheTick` **redesigned**. The plan specified a resolver failing on its "2nd invocation", but invocation ordinals shift under the very change being made: today the resolver is called only inside `dispatch`, and after the fix it is also called at fire admission. Rewritten to key on a failure *window* (an `AtomicBoolean` flipped by the holding consumer) instead of a count. Classification unchanged — green today and after the fix; it guards against the rejected resolve-before-admission ordering. |

**Finding recorded, no plan change:** the red-test run independently confirmed that a resolver
throw inside the queued `QUEUE_ONE` re-dispatch strands the guard permanently for
`EVERY_INSTANCE` too — the same leak class as `SINGLE_INSTANCE`. Already covered: S1 resolves in
`markCompleted` and releases both guards on failure, and S2 wraps the dispatch tail.

### Scope split into two PRs

At the user's direction, the issue #41 fix ships on its own rather than waiting for S2:

- **PR 1 — S1 only.** The `#41` chain: resolution before persistence, plus the canonical
  `module.md` update. This is a complete, independently valuable fix; S2 is a distinct defect that
  merely shares the execution path.
- **PR 2 — S2.** Synchronous-dispatch-throw containment, from this same worktree.

Consequence for **L3**: the plan file is **not** removed in PR 1. It is the persisted spec for S2,
which is still in flight, so removing it here would delete an in-flight contract. L3 moves to the
final PR of the initiative. L4 (gitlink + maintainer docs) likewise waits until both PRs have
merged, so the governance docs describe a single settled state rather than an intermediate one.

### Simplify + security review outcomes (S1)

**Simplify stage** ran four blind lenses plus an independent verifier. Eight candidates confirmed
safe and applied (`4635c9e`). The one that mattered: `resolveEffectiveAddress` was reading
`job.handlerAddress()` — a copy the registrar *derives* from the target — instead of
`EventBusTarget.address()`. The reuse and altitude lenses reached that independently. Trusting a
derived copy is the same defect class as #41 itself, so the new invariant had been resting on the
very source it exists to replace.

Two findings were about this plan's own work rather than the code's:
- The preceding commit had been maintaining **dead javadoc** — `CronJobDispatcher`'s constructor
  carries two consecutive doc blocks and only the second binds.
- `unresolvableTargetDuringOverlapStillQueuesTheTick` **did not guard what it claimed**. Its
  `hitCount >= 2` assertion passed under both the correct and the rejected ordering. It now asserts
  the resolver is never consulted while the failure window is armed, which is the actual
  discriminator.

Declined: the altitude lens argues the scheduler now hand-repeats an unnamed fire-admission protocol
at three sites, and that `dispatch` receives the address twice (bare, and inside `execution`) with
nothing enforcing they agree. Real, but a structural refactor rather than a cleanup — recorded, not
done.

**Security review**: 0 critical, 0 high, 1 medium, 2 low. All three were in code this slice
introduced, and all three were fixed rather than deferred:

| Severity | Finding | Resolution |
|---|---|---|
| MEDIUM | A permanently unresolvable target logged an ERROR **plus stack trace every tick** — ~85-170 MB/day/node for one config typo, and enough to bury real security events. Introduced by this fix: previously the stranded guard silenced subsequent ticks (a dead job, but a quiet one). | Log once per job at ERROR, then DEBUG until it resolves. Pinned by `permanentlyUnresolvableTargetLogsErrorOnce`. |
| LOW | Config-supplied job ids and `eventbus:` addresses reached log records verbatim under `%msg%n` layouts — CRLF gives a log-forging primitive (CWE-117). | Sanitize at the log sink via `DeferredExecutionOrigin.of(...)`, reused rather than reimplemented. Parse-boundary rejection deferred to an issue: it changes startup behavior, which was not pre-decided. |
| LOW | The "never throws" contract was stated unconditionally but `catch (Exception)` lets an `Error` escape and strand the guard. | Contract corrected to match the module's deliberate rule that an `Error` must propagate, with the cost of that choice named explicitly. Not silently widened to `catch (Throwable)`. |

Clean verdicts worth recording: a crafted stable target id cannot synthesize an address (the
resolver is an allowlist lookup that fails closed); a config target cannot collide with the
`job.cancel.*` / `job.completions.*` channels (UUID-suffixed per fire); nothing was removed with
the dispatcher's resolver — the deleted block had no validation, and the new gate adds three
checks; and cron rows can never reach the delayed-job dispatch sink (the claim query requires
`state = 'ENQUEUED'`, which cron never writes).

**Deferrals routed:** issue #77 (parse-boundary validation), #78 (`MisfirePolicy.FIRE_ALL` runs one
fire, not all), #79 (no live-fire cron/Postgres composition test).

### S2 scope broadened (review finding, verified)

The approved S2 text scoped containment to a synchronous throw from
`CronJobDispatcher.dispatch`. Independent review pointed out — and I verified — that the same leak
is reachable from the **repository call sites**, which S2 as written would have left open:

| Site | Guards held when it throws | Consequence |
|---|---|---|
| `repository.tryInsert(execution)` in `fireSingleInstance` | in-flight only (no slot yet) | job never fires again |
| `repository.save(execution)` in `fireEveryInstance` | in-flight only | job never fires again |
| `repository.save(queuedExecution)` in `markCompleted` | in-flight **and** slot | job dead, one of ten global slots burned |
| `dispatcher.dispatch(...)` inside `acquireSlotAndRun` | in-flight and slot | as above |

`JobRepository` is an application-implementable SPI (`JobRepository.java:13`), so a custom adapter
may throw synchronously instead of returning a failed future — the `onFailure` handlers already
present only catch the latter. `buildExecution` is inside the same window.

S2 therefore covers two places, not one: the scheduler's synchronous window around
build-plus-repository-call, and the dispatcher's post-registration tail. Recorded as a correction of
a verified fact, so no re-approval; the slice's intent is unchanged.
