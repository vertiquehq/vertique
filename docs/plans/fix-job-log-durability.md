# Issue #36 — Remove job checkpoints, make job logs durable

## 1. Context & goal

`JobRepository.saveCheckpoint`, `loadCheckpoint` and `saveLogs` are implemented in
`PgJobRepository` and proven by an integration test — and **no code path in the framework
ever calls them**. Checkpoints and job logs are execution-scoped memory, lost on restart,
behind Javadoc that promises durability (issue #36).

**Goal:** delete the checkpoint feature outright, and make job logs actually durable so a
dashboard can tail a running job with bounded delay.

**Why now / why not defer.** `prd/product/job-dashboard.md` specifies
`GET /api/jobs/:executionId/logs` and SSE streaming "on each heartbeat/progress flush" — it
is blocked on logs existing in the database. And the checkpoint API cannot be *fixed* in
place: `checkpoint()` returns `void`, so durability is not expressible in the signature.
`PRD-JOB-001` (committed `cbd575a`) specifies the proper replacement; this PR is its stated
hard prerequisite.

**Do-less alternative considered:** flush logs only at completion. Rejected — a crashed job
never reaches completion, and the dashboard requirement is explicitly *live* tailing.

---

## 2. Pre-flight findings (verified 2026-07-29)

| Finding | Consequence |
|---|---|
| `DefaultJobContext.checkpointMap()` is package-private in `dev.vertique.job`; both dispatchers are in `…job.cron` / `…job.delayed` | The flush point was never built — this is not a dropped call |
| `PgJobRepository.toJsonObject` has **6 other callers** (`updateProgress`, `completeExecution`, `markAndScheduleRetry`, `toInsertTuple`×3) | Keep the method; delete only the `saveCheckpoint` call site |
| Every `db/migration/**` in both checkouts has exactly one `V1`; `git tag` is empty; version is `0.1.0-SNAPSHOT`; `vertique-job-postgresql/module.md:100` states the policy verbatim | **Fold the drop into `V1` in place. Do not add `V2`.** |
| Only 3 concrete `JobRepository` impls exist: `PgJobRepository`, `NotifyingJobRepository`, `NoOpJobRepository` (enterprise). Everything else is `mock(JobRepository.class)` | Mocks need no edit; the enterprise example does |
| Spotless runs `removeUnusedImports` and CI runs `spotless:check` | Orphaned `Instant`/`Map` imports in `DefaultJobContext` **fail CI**, not warn |
| `governance/source-map*.{tsv,yml}` are pinned to a **baseline commit**, not HEAD | They need **no** change |
| `governance/public-content-allowlist.txt:1329` pins `Checkpoint.java`, and `scan-public-checkout` rejects **missing** paths | The line must be removed |

### Design facts that shaped the log flush

- `DefaultJobContext` (hence its logger) crosses the event bus **by reference**
  (`LocalMessageCodec.transform` returns the object). The handler mutates the same instance
  the scheduler holds.
- **The timer and the handler run on different contexts.** Timer: the scheduler/poller's
  non-duplicated deployment context. Handler: a duplicated event-bus context, and handler
  service verticles may be deployed `ThreadingModel.WORKER` (real worker threads).
- **7 execution-ending sites**: cron (completion consumer, timeout timer, `shutdown()`);
  delayed (completion consumer, timeout→retry, timeout→dead-letter, `stop(Promise)`).
- `JobCompletionHandler` is the **wrong seam** — the delayed timeout paths deliberately
  bypass it (a comment and two tests assert this), and cron never uses it.
- `job_logs.execution_id` is `NOT NULL REFERENCES job_executions(id)`. Untracked cron fires
  have `execution == null` and **no row** — flushing for them is an FK violation.
- `progressFlushIntervalMs` (default 10 000, `JobCoordinatorConfig`) **disables the timer at
  0**. Cron additionally guards on `execution != null && repository != null`.
- `DefaultJobContext.jobLogger` is typed as the concrete `DefaultJobLogger`, so
  package-private claim/ack needs no widening of the public `JobLogger` interface.

### Three corrections to the first-draft design

Found by the `vertique-codex-architect` consultation (`codex_session_id:
019fae9a-09c6-7e02-a927-2675330e687a`); all three are adopted.

1. **A high-water cursor over the existing `CopyOnWriteArrayList` fixes neither the
   unbounded-growth leak nor the O(N²) append cost** — a cursor never removes anything.
   The earlier claim that draining "bounds the buffer" was wrong. Only actually removing
   drained entries does that. → `ArrayDeque` under a monitor.
2. **`CopyOnWriteArrayList.subList` throws `ConcurrentModificationException`** when the
   backing array changes (`COWSubList.checkForComodification`, verified in JDK 21). A
   sublist-based drain blows up whenever a handler appends mid-drain.
3. **`.recover()` does not save a shutdown from a future that never settles.** A wedged
   pool would hang undeploy. → `Future.timeout(…)` (present in Vert.x 5.1.2) before
   `.recover(warn)`.

---

## 3. Key decisions

**Log delivery is at-least-once on known failure, not at-most-once.** A failed batch is
pushed back to the front of the queue and retried ahead of newer entries. Rejected the
simpler "advance the cursor and lose the batch" because silently dropping log lines on a
transient blip recreates the exact operational surprise #36 exists to fix.

**Single-flight per execution.** At most one `saveLogs` in flight per execution; a flush
arriving during an active write coalesces rather than running in parallel. Paid for by the
verified 7-ending-site × 2-timer concurrency.

**No schema change.** `job_logs` gets no `sequence` column and no unique key. Duplicates
remain possible only after an *ambiguous* commit (batch applied, ack lost). Deferred with a
re-entry trigger (§8) rather than paid for now.

**Shutdown flush is a cutoff snapshot, not a final flush** — the handler may still be
running. Documented as such.

---

## 4. Slice plan

Each slice is red tests → green impl → commit. Order is prescriptive.

### S0 — persist the plan · `routine`
Commit this file to `docs/plans/<branch>.md` in the `vertique` checkout.
`docs: add implementation plan for issue #36`

### S1 — remove checkpoints · `routine`
**Red:** none (pure deletion). Proof is the compile plus the deleted tests.
**Green:** delete `Checkpoint.java`; the two `JobContext` methods + `DefaultJobContext`
state, impls and `checkpointMap()`; the two `JobRepository` methods; `PgJobRepository`'s two
SQL constants + two methods (keep `toJsonObject`); `JobExecutionMapper.checkpointFromRow`;
`NotifyingJobRepository`'s two delegations; the `job_checkpoints` DDL in `V1`. Delete
`DefaultJobContextTest`'s `@Nested Checkpoints` class and `PgJobRepositoryIT`'s two
checkpoint tests. Drop `job_checkpoints` from `SagaTestBase`'s `TRUNCATE`. Run
`spotless:apply` — orphaned imports fail CI.
`refactor(job): remove the never-wired checkpoint API`

### S2 — durable log buffer · `critical`
**Red:**
- `DefaultJobLoggerTest.claimReturnsBufferedEntriesInOrder` — given 3 entries, when claimed, then all 3 in write order.
- `…ackDiscardsClaimedBatch` — given a claimed batch, when acked, then a second claim returns empty. **This is the test that fails against any accidental cursor-only implementation.**
- `…nackRetainsBatchAheadOfNewerEntries` — given a claimed batch nacked and a newer entry appended, when re-claimed, then the nacked entries come first.
- `…claimIsSingleFlight` — given an unacked claim, when claimed again, then empty.
- `…entriesReturnsSnapshotNotLiveView` — given `entries()` captured, when a new entry is appended, then the captured list size is unchanged.

**Green:** replace `CopyOnWriteArrayList` with `ArrayDeque<LogEntry>` under a monitor;
package-private `claim()` / `ack()` / `nack(batch)`; a `flushing` single-flight flag;
`entries()` becomes a real defensive copy (its own javadoc already permits "view **or**
copy"). Fix the `JobLogger` javadoc — it currently calls this "a per-execution audit trail"
and says entries "can be flushed… after the job completes".
`feat(job): add claim/ack drain to the buffered job logger`

### S3 — the flusher · `critical`
**Red:**
- `JobLogFlusherTest.flushSendsClaimedEntries` / `flushAcksOnSuccess` / `flushNacksOnFailure` / `flushIsNoOpWhenNothingBuffered` / `flushIsNoOpWithoutRepository`.

**Green:** `JobLogFlusher` in `vertique-job-core`, one instance per execution, holding
`(JobRepository, UUID, DefaultJobLogger)` with a public `Future<Void> flush()`. A null
repository or null execution makes it a **genuine no-op** — required by the `job_logs` FK
for untracked cron fires. Not an SPI, no Dagger binding, no config knob.
`feat(job): add JobLogFlusher`

### S4 — wire the delayed scheduler · `critical`
**Red** (pattern: the mock *is* the completion signal — no sleeps; `executionTimeoutMs=0`,
`progressFlushIntervalMs=100`, handler never replies so only the tick can flush):
- `DelayedJobPollerTest.flushesLogsOnProgressTick` — `when(repository.saveLogs(...)).thenAnswer(…ctx.completeNow())` behind an `AtomicBoolean` latch.
- `…doesNotResendAlreadyFlushedEntriesOnCompletion` — flush once, then reply; assert the completion flush carries only the new entries. This pins the ack semantics end-to-end.
- `…flushesOnTimeoutBeforeDeadLetter`.

**Green:** construct the flusher in `dispatch(...)` **outside** the
`progressFlushIntervalMs > 0` guard (inside it, a `0` interval would mean logs never
persist at all); call it from the timer and from all three ending sites; add the flusher to
`ExecutionResources`; compose the flush into `stop(Promise)` with
`.timeout(…).recover(warn)`.
`feat(job-delayed): flush job logs on the progress tick and at completion`

### S5 — wire the cron scheduler · `critical`
**Red:** `CronSchedulerTest.flushesLogsOnProgressTick` and `…flushesOnAbandonTimeout`,
using the existing `stubRepoCapturingCompletion` + `SINGLE_INSTANCE` + `tryInsert` pattern.
Plus `…untrackedFireDoesNotFlush` — the FK guard.

**Green:** same wiring; widen `ExecutionResources`; `shutdown()` returns `Future<Void>`;
`CronScheduler.stop()` composes it with `.timeout(…).recover(warn)`.
`feat(job-cron): flush job logs on the progress tick and at completion`

### S6 — ADR + docs · `routine`
ADR (§5) plus the doc surface in §6.
`docs(job): record log-flush semantics and update module docs`

---

## 5. ADRs to write (S6)

1. **`docs/adr/NNNN-job-log-flush-delivery-semantics.md`** — at-least-once on known failure,
   single-flight, retain-and-retry-at-front, no dedup key, shutdown as cutoff snapshot.
   Records the rejected at-most-once option and why.
2. **`docs/adr/NNNN-remove-job-checkpoint-api.md`** — short; the removal rationale and a
   pointer to `PRD-JOB-001` for the replacement.

> Check `main` for the next free ADR number immediately before writing — parallel worktrees
> race the marker and a renumber at merge is expected.

---

## 6. Artifact manifest

### Repo A — `sources/vertique` (PR to `vertiquehq/vertique`)

**Deleted:** `vertique-job-core/…/job/Checkpoint.java`

**Modified — checkpoint removal:** `job/JobContext.java`, `job/DefaultJobContext.java`,
`job/JobRepository.java`, `job-postgresql/…/PgJobRepository.java`,
`…/JobExecutionMapper.java`, `…/NotifyingJobRepository.java`,
`…/db/migration/job/V1__create_job_tables.sql`,
`job-core/src/test/…/DefaultJobContextTest.java`,
`job-postgresql/src/test/…/PgJobRepositoryIT.java`,
`examples/vertique-example-workflow-order-fulfillment/src/test/…/SagaTestBase.java`

**New — log flush:** `job-core/…/job/JobLogFlusher.java`,
`job-core/src/test/…/JobLogFlusherTest.java`

**Modified — log flush:** `job/JobLogger.java`, `job/DefaultJobLogger.java`,
`job-cron/…/CronJobDispatcher.java`, `job-cron/…/CronScheduler.java`,
`job-delayed/…/DelayedJobPoller.java`, `job-core/src/test/…/DefaultJobLoggerTest.java`,
`job-cron/src/test/…/CronSchedulerTest.java`,
`job-delayed/src/test/…/DelayedJobPollerTest.java`

**Docs (same-slice contract):**
- `vertique-job-core/src/main/resources/META-INF/vertique/module.md` — L113 API row, L125
  heading, L133–134, the L203–206 "execution-scoped memory" invariant (now true of neither),
  L207–209, L230 repository method table
- `vertique-job-postgresql/src/main/resources/META-INF/vertique/module.md` — L13 summary +
  frontmatter description, L21, L61, L98, the whole L152–163 `job_checkpoints` section, L234
- `vertique-job-cron/…/module.md`, `vertique-job-delayed/…/module.md` — log-flush behavior
- `docs/plans/<branch>.md` — added S0, **removed in the final docs commit**

### Repo B — `sources/vertique-enterprise` (separate PR)
`examples/vertique-example-camel-payments/…/di/NoOpJobRepository.java` — drop the import and
the two overrides.

### Repo C — `vertique-dev` (meta)
`governance/public-content-allowlist.txt` (drop the `Checkpoint.java` line),
`docs/vertique-job-core.md` (L29, L38, L109, L112–114, L168),
`docs/vertique-job-postgresql.md` (L28, L133), submodule pin bumps.

`sources/site-source` regenerates from `module.md` via `stage-docs.mjs` — no hand edit.

---

## 7. Sequencing across the three repos

`vertique-enterprise` resolves `vertique.version = 0.1.0-SNAPSHOT` from the local `~/.m2`,
so the enterprise build goes red the moment the framework methods disappear and a local
`install` runs.

1. Repo A PR → CI green → merge. **Draft PR** — it touches `*IT.java`.
2. Repo B PR immediately after (small, mechanical).
3. Repo C commit last: governance + docs + both submodule pins.

Build each worktree as a full reactor with `-am`; a cross-worktree `install` is
contamination.

---

## 8. Risks & out of scope

| Risk | Mitigation |
|---|---|
| `saveLogs` batch atomicity is **unverified** — if Postgres commits a partial prefix, retain-and-retry duplicates rows | Add an IT injecting a mid-batch failure. If a prefix commits, transactional `saveLogs` becomes mandatory before retry-on-failure is safe. **Falsification handle — run this early in S3.** |
| Retain-and-retry is not a hard memory bound during a long outage | Accepted. Deferred: explicit entry/byte cap. Do **not** claim the leak is "fixed" |
| Delayed retries reuse one `execution_id`, so attempts interleave in `job_logs` | Accepted; ordering is `logged_at, id`. Deferred: attempt discriminator |
| ADR number collision with parallel worktrees | Re-check `main` at merge; expect a renumber |

**Deferred, each with a re-entry trigger** — route to GitHub issues at step 9:
`sequence` column + `UNIQUE(execution_id, sequence)` (trigger: observed duplicates harming
the dashboard, or a positive prefix-commit test); explicit buffer cap (trigger: measured
heap growth); attempt discriminator (trigger: a dashboard requirement); transactional
`saveLogs` (trigger: positive prefix-commit test); a separate `logFlushIntervalMs` knob
(trigger: the shared 10 s interval proves too coarse for the dashboard).

**Out of scope:** the checkpoint replacement (`PRD-JOB-001`), any dashboard read surface,
`job_executions` retention.

---

## 9. Verification

- Per slice: `./mvnw -ntp -pl <module> -am test`
- S3 falsification handle: the mid-batch-failure IT, run before S4/S5 wiring depends on it
- Full: `./mvnw -ntp clean verify` on the **whole** reactor, both checkouts
- Mechanical completeness: `grep -rn "saveCheckpoint\|loadCheckpoint\|lastCheckpoint\|checkpointMap\|job_checkpoints\|dev.vertique.job.Checkpoint" --include="*.java" --include="*.sql" --include="*.md" sources/` returns only the dated amendment notes in `prd/`
- `./mvnw -ntp spotless:check` — the orphaned-import gate
- `scripts/verify-module-docs.sh`
- Acceptance walkthrough: a job that logs during execution has rows in `job_logs` **while
  still running**, with `logged_at` at true log time; a killed job retains everything
  flushed up to the last tick; no duplicate rows across tick + completion.

**Estimate:** 1.5–2 days through the full pipeline (5 code slices, simplify, security +
Codex review loop, docs), assuming the batch-atomicity test comes back clean.
