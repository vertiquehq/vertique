# Fix issue #353 — flaky JWKS dispatch tests in `vertique-rest-auth-jwt`

## 1. Context & goal

`JwtAuthFactoryTest.shouldNotCompleteSynchronouslyOnEventLoopForClasspathLocation` and its
filesystem sibling flake on Linux CI (issue
[#353](https://github.com/vertiquehq/vertique-dev/issues/353) — observed on `main` and on unrelated
PR branches). Both delegate to `assertDoesNotCompleteOnCallingContext`, which calls
`JwtAuthFactory.fromJwksAsync(...)` inside `vertx.runOnContext(...)` and reads `future.isComplete()`
on the very next statement, asserting the worker has *not* finished yet.

That is a scheduling race, not a contract (`Future.isComplete()` itself is thread-safe): on a fast
runner with the JWKS document in page cache, `executeBlocking` can dispatch, read and resolve the
promise before the calling event-loop task advances one statement. Nothing in the production
contract forbids that.

**Goal:** pin the documented contract — "`fromJwksAsync` reads *every* location kind on a worker
thread" (packaged `module.md`) — with assertions that cannot invert under CI load, while keeping the
regression these tests exist to catch: a re-introduced "local locations are cheap, read them inline"
fast path. That is not hypothetical; it is exactly the bug commit `516202b` fixed, and it was
branch-specific (classpath and filesystem bypassed `executeBlocking` while `http(s)` dispatched).

**Why now:** the flake is live on `main` and aborts the reactor when it hits. The do-less
alternatives — `@RepeatedTest`/retry, or deleting the tests — either hide the race or drop the
regression guard. Production code is correct and is not changed by this work.

## 2. Pre-flight findings

Verified against the code and the Vert.x 5.1.6 API, not assumed:

1. **One dispatch site.** `JwtAuthFactory.fromJwksAsync(Vertx, String, JwtValidationConfig, boolean)`
   validates arguments on the calling thread, captures the caller's TCCL via `callerClassLoader()`,
   then wraps the whole read-and-build pipeline in a single `vertx.executeBlocking(...)`.
   `readLocation` branches on location kind **inside** the worker (`classpath:` →
   `classpathLoader.getResourceAsStream`, `http(s):` → JDK `HttpClient`, otherwise →
   `vertx.fileSystem().readFileBlocking`).
2. **`Vertx.executeBlocking` draws from the fixed-size worker pool** sized by
   `VertxOptions.workerPoolSize` (default 20), and the result is delivered on the original context.
   No inline fast path is documented.
3. **Vert.x's internal blocking pool is a separate executor** from the worker pool
   (`vert.x-internal-blocking-*` vs `vert.x-worker-thread-*`). Saturating the worker pool therefore
   does not starve Vert.x internals — this is what makes a `workerPoolSize(1)` test safe rather than
   a deadlock hazard.
4. **`Context.isOnWorkerThread()` is documented `true` inside an `executeBlocking` callable**, while
   the surrounding context stays an event-loop context. A documented predicate, so the proof needs
   no thread-name string matching.
5. **`Future.onComplete` runs the handler inline** when the future is already complete and the
   caller is on the future's own context thread (`ContextBase.emit` → `executor().inThread()`).
   Any "had the handler run yet?" assertion is therefore racy in exactly the same window — that
   family of fix is refuted, not merely disliked.
6. **`getOrCreateContext()` creates a fresh context per call from a non-Vert.x thread**, so a gate
   task submitted from the JUnit thread and the reads submitted from a `runOnContext` task sit in
   *different* ordered queues. Serialization between them comes from pool size, not queue order —
   the test must say so, or a later reader will "simplify" the gate into the same context and
   silently change what is proved.
7. **Vert.x sets the context classloader as TCCL on tasks dispatched to a context**
   (`VertxOptions.DEFAULT_DISABLE_TCCL == false`). Whether a TCCL set *inside* a task is restored by
   Vert.x is undocumented, so a test that installs one restores it itself in a `finally`.
8. **The documented `classpath:` → thread-context-classloader contract is untested.** `module.md`
   states it; `grep` finds zero `ClassLoader` references in the module's test sources. This is the
   subtle thing a refactor breaks silently (capture must happen on the *calling* thread).
9. **No other test in the tree has this defect shape.** Every other `assertFalse(f.isComplete())`
   gates a future the test itself controls (`DefaultResponseSerializerTest`, `JobLogFlusherTest`,
   `SnapshotDegradationGateTest`, …); only this helper races a real worker.

## 3. Design — and the debate that produced it

The chosen mechanism is a **saturated worker pool**: a test-owned
`Vertx.vertx(new VertxOptions().setWorkerPoolSize(1))` whose sole worker thread is held by a gate
task. While that gate is held, a *dispatched* read cannot complete, and an *inline* read completes
immediately. `assertFalse(future.isComplete())` then rests on a framework guarantee (fixed pool
size) instead of scheduler luck, on the success path, uniformly for both local location kinds.

**Debate outcome** (first opinion: `vertique-toolkit:vertique-codex-architect`, `gpt-5.6-sol`):

- *Adopted from the consultation:* the saturated-pool design. It replaced my own leaning (a recording
  classloader for the classpath branch plus a stack-trace proof for the filesystem branch), which
  would have shipped two unrelated proof mechanisms for one contract, one of them
  failure-path-only and coupled to frame shape. Conceded.
- *Rejected, with reason:* a stack-trace proof as primary (indirect, failure-path only); a
  FIFO/slow-file fixture (OS-specific, turns failures into hangs); deleting the per-location
  coverage (the historical regression was branch-specific); an ordering-based assertion (refuted at
  source — finding 5).
- *Rejected against the consultation:* its verdict that a production `Consumer<Thread>` read-observer
  seam should be added later — no present consumer; the gate meets the requirement. Recorded as a
  deferred item with a re-entry trigger (§8).
- *Where the consultation was overruled:* it filed the recording-classloader test as "Defer". It
  earns its place on its own requirement (finding 8 — a documented, untested contract), and as a
  byproduct it records the literal carrier thread for the classpath branch, closing the one hole in
  the gate design (a perverse implementation could read inline and then submit a meaningless worker
  task). Included as S2.
- *Two defects in the consultation's sketch, corrected here:* assertions inside a Vert.x context
  handler must be wrapped in `testContext.verify(...)` or an `AssertionError` is swallowed and the
  test hangs to timeout instead of failing; and the gate's own await must be shorter than the
  `VertxTestContext` timeout so a real failure surfaces as the gate's assertion, not an ambiguous
  framework timeout.

## 4. Slice plan

### S0 — persist the plan *(risk tier: routine)*

Copy this plan to `sources/vertique/docs/plans/issue-353-jwks-worker-dispatch-proof.md` — the
**framework repo's own** plan tree, because the branch lives there and the plan file is the branch's
handoff contract; a plan committed to the governance repo could not travel with it. Filename is the
initiative slug with the branch's `fix/` prefix stripped, per `workflow.md`.
Commit: `docs: add implementation plan for issue #353 JWKS dispatch proof`.

### S1 — replace the flaky scheduling tests with the saturated-pool proof *(risk tier: critical)*

Delete `assertDoesNotCompleteOnCallingContext` and both callers. Add one test, plus an `@AfterEach`
teardown for the owned `Vertx`.

**Red-test spec — `shouldQueueLocalJwksReadsBehindTheWorkerPool`:**

- *Given* a test-owned `Vertx` with `workerPoolSize(1)`, a JWKS fixture copied to `@TempDir`, and a
  gate task submitted from the JUnit thread via `executeBlocking` that counts down `workerOccupied`
  and then awaits `releaseWorker`, with the test having awaited `workerOccupied` (≤ 5 s, asserted).
- *When* a `runOnContext` task calls `fromJwksAsync(vertx, "classpath:test-jwks.json")` and
  `fromJwksAsync(vertx, <tempdir jwks path>)`.
- *Then*, inside `testContext.verify(...)`, both returned futures are **incomplete** — each with a
  message naming its location kind, so a regression identifies the branch it re-inlined; and after
  `releaseWorker.countDown()` (in a `finally`, outside the `verify`), `Future.join` of both futures
  succeeds and both `JWTAuth` results are non-null.
- Both latches are fields; `@AfterEach` counts down `releaseWorker` and closes the owned `Vertx`
  (null-guarded, awaited on the JUnit thread), so a mid-test assertion failure cannot leave a held
  worker or a leaked `Vertx`.
- A comment records *why* the gate is submitted from a different context and that `workerPoolSize(1)`
  — not ordered-queue serialization — is the mechanism (finding 6).

**Proof that the test can fail (mandatory, not optional):** locally restore the pre-`516202b` shape
(read inline for non-`http` locations, return `Future.succeededFuture(...)`), confirm the test fails
**before** gate release for *each* location kind, then revert the mutation. Record the observed
failure output in the PR description.

Commit: `test(rest-auth-jwt): pin JWKS worker dispatch with a saturated worker pool`.

### S2 — cover the documented caller-classloader capture *(risk tier: routine)*

**Red-test spec — `shouldResolveClasspathLocationThroughTheCallerContextClassLoader`:**

- *Given* a recording `ClassLoader` (delegating to the test class's loader) that, on
  `getResourceAsStream("test-jwks.json")`, records `Thread.currentThread()` and
  `Context.isOnWorkerThread()` before delegating; installed as the TCCL inside a `runOnContext`
  task and restored in an immediate `finally` in that same task.
- *When* `fromJwksAsync(vertx, "classpath:test-jwks.json")` is called from that task (injected
  `Vertx`, default pool — this test does not need the saturated instance).
- *Then* the future succeeds with a non-null `JWTAuth`; the recording loader **was** consulted
  (proving the capture happened on the calling thread, per finding 7 — a capture moved inside the
  worker would see the context loader instead); the recorded `Context.isOnWorkerThread()` is `true`;
  and the recorded thread is not the calling event-loop thread (`assertNotSame`).

**Proof that the test can fail:** move `callerClassLoader()` inside the `executeBlocking` lambda
locally → the loader is not consulted → test fails. Revert.

Commit: `test(rest-auth-jwt): cover the documented caller-classloader capture for classpath JWKS`.

### S3 — close out *(risk tier: routine)*

Remove the persisted plan file, file the follow-up issue from §8, open the PR.
Commit: `docs: remove implementation plan for issue #353`.

## 5. Artifact manifest

**Modified**
- `sources/vertique/vertique-rest/vertique-rest-auth-jwt/src/test/java/dev/vertique/rest/auth/jwt/JwtAuthFactoryTest.java`
  (the only code file touched)

**New (transient)**
- `sources/vertique/docs/plans/issue-353-jwks-worker-dispatch-proof.md` — added in S0, deleted in S3

**Deleted**
- `JwtAuthFactoryTest.assertDoesNotCompleteOnCallingContext` and its two callers (superseded by S1's
  test; `copyJwksToTempDir` is retained and reused)

**Documentation decision**
- `vertique-rest-auth-jwt: no documentation impact — test-only change; the documented worker-thread
  dispatch and `classpath:`/TCCL contracts in the packaged `module.md` are unchanged (S2 covers an
  existing documented claim rather than adding one).`
- Maintainer reference `docs/modules/vertique-rest-auth-jwt.md` (governance repo): **no change.** Its
  Testing table maps concerns to *test classes*, and the row
  `JWTAuth construction and location handling | JwtAuthFactoryTest` still holds — the class survives,
  both new tests fall under location handling, and no removed symbol is cited anywhere in the docs
  trees. (A change there would also require a separate governance-repo PR, so it must be a
  deliberate decision, not a by-product.)

## 6. ADRs to write

None. This repairs test proof; it introduces no API, SPI, configuration, or architectural decision.
The reusable part — "prove off-loop dispatch by saturating a `workerPoolSize(1)` worker pool rather
than by observing completion timing" — belongs in `.claude/rules/testing.md`, which lives in the
**governance repo** and therefore needs its own docs-only PR; routed to an issue in §8 rather than
silently widening this change.

## 7. Risks & edge cases

| Risk | Handling |
|---|---|
| Held gate blocks `Vertx.close()` and hangs teardown | `@AfterEach` counts down `releaseWorker` *before* closing; latches are fields, not locals |
| Saturated pool starves Vert.x internals | Refuted by finding 3 — the internal blocking pool is a separate executor |
| `AssertionError` inside a context handler is swallowed → hang instead of failure | Every in-context assertion sits inside `testContext.verify(...)` (`testing.md`) |
| Gate await timeout collides with the `VertxTestContext` timeout | Gate await ≤ 5 s, well inside the vertx-junit5 default, so the gate's own assertion wins |
| Two `Vertx` instances alive in one test class | S1's is test-owned and closed in `@AfterEach`; S2 uses the injected instance and owns nothing |
| Future Vert.x release stops bounding `executeBlocking` by `workerPoolSize`, or merges the pools | Would break the test loudly, not silently; the comment in the test names the assumption |

## 8. Out-of-scope & deferral routing

- **`.claude/rules/testing.md` — add the saturated-worker-pool idiom** to the "Async / Network IT
  Determinism" section → GitHub issue in `vertiquehq/vertique-dev` (governance repo, docs-only PR).
- **Production `Consumer<Thread>` read-observer seam** → deferred, no present consumer. Re-entry
  trigger: the read pipeline stops being a single synchronous call inside `executeBlocking`, or a
  mutation shows the gate stays green while a plausible implementation does local I/O on the event
  loop. Recorded in the same issue.
- **The `http(s)` branch** is untouched — it was never inline and is not part of this repair.

## 9. Verification

1. Per slice: `./mvnw -ntp -pl vertique-rest/vertique-rest-auth-jwt -am test -Dtest=JwtAuthFactoryTest`
   (verify the report shows the expected test count actually ran — `Surefire -Dtest` can report a
   false green).
2. Mutation proof per slice as specified in S1/S2 — the acceptance gate, not an optional extra.
3. Stability loop (the flake is the whole point):
   `for i in $(seq 50); do ./mvnw -ntp -pl vertique-rest/vertique-rest-auth-jwt -am test -Dtest=JwtAuthFactoryTest || break; done`
4. `./mvnw -ntp spotless:apply` before each commit that touches Java.
5. Full-project `./mvnw -ntp clean verify` via `vertique-toolkit:vertique-build-validator`.
6. Review: `/security-review` is not applicable (test-only, no trust boundary); one
   `vertique-toolkit:vertique-codex-reviewer` pass plus the Codex loop per `workflow.md`.
7. Acceptance walkthrough against issue #353: (a) neither remaining test asserts on completion
   *timing*; (b) both local location kinds still assert dispatch independently; (c) the pre-`516202b`
   regression still fails the suite; (d) 50 consecutive green local runs; (e) CI green on the PR.

## 10. Git flow

Framework repo `vertiquehq/vertique` (the code lives in the `sources/vertique` submodule).
Worktree `sources/vertique/.claude/worktrees/issue-353-jwks-dispatch-proof`, branch
`fix/issue-353-jwks-worker-dispatch-proof`, branched from `main`. PR targets `vertique`'s `main`;
issue #353 lives in `vertique-dev`, so it is closed manually after merge (cross-repo keywords do not
auto-close). No `*IT.java` is touched, so the draft-PR rule does not apply.
