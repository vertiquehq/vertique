# Fix #35 — health probe hangs on a null failure message

**Issue:** [vertiquehq/vertique-dev#35](https://github.com/vertiquehq/vertique-dev/issues/35)
**Branch:** `fix/health-check-null-message` (code repo `vertiquehq/vertique`, from `origin/main` @ `4331c5f`)
**Worktree:** `.claude/worktrees/issue-35-health-null-message`
**Pipeline:** standard (3 modules, public API change, 2 repos)
**Rev 2** — rebuilt after a review found the governance paths had drifted and the S2 test suite did
not actually prove the S2 changes. Both blockers are addressed; see §11.

---

## 1. Context & goal

`HealthCheckResult.down(String)` builds `Map.of("error", error)`, and `Map.of` rejects null
values. Three production call sites feed it `Throwable.getMessage()` unguarded, which is null
for any exception constructed without a message. On the async branch the resulting NPE fails
the per-check future, which poisons `HealthCheckHandler`'s aggregation, and **the HTTP
response is never written** — an orchestrator sees a hung readiness/liveness probe instead of
a DOWN status. That is worse than the failure being reported, because it changes how the
platform reacts: no restart, no traffic removal, just a stuck probe.

**Goal:** a health probe always answers. A check that fails for any reason — with or without a
message — produces a DOWN entry and a complete 503 response, and never starves its siblings.

**Why now:** a live availability defect in the readiness path of every application that installs
`vertique-db-postgresql`, already documented in three shipped doc surfaces as a trap consumers
must work around.

**The boring default being rejected:** "coalesce at the two call sites, leave the factory strict"
— the position recorded in `docs/modules/vertique-management.md:101`. Rejected because the
packaged SPI invites third-party checks to call these factories, so the obligation is replicated
at every adapter with nothing proving it was met; because the shipped workaround
(`String.valueOf(e.getMessage())`) writes the literal string `"null"` into the probe body; and
because it leaves the hang mechanism itself untouched. See §5.

---

## 2. Pre-flight findings (re-verified against `main`, 2026-08-06)

| # | Finding | Evidence |
|---|---|---|
| F1 | `Future.all` is **fail-fast** — it fails on the first constituent failure. `Future.join` waits for all to settle. | vertx-core 5.1.2 javadoc |
| F2 | `Future#result()` returns **null** for a failed future (documented). For a *pending* future the javadoc is silent; `isComplete()`/`succeeded()` is the documented way to disambiguate. The design relies only on `succeeded()`, not on the pending-null inference. | vertx-core 5.1.2 javadoc |
| F3 | `otherwise(fn)` fails the returned future with **fn's own exception**; the original cause is lost. This is how the NPE becomes a failed future. | vertx-core 5.1.2 javadoc |
| F4 | The timeout path is **not** a trigger: `HealthCheckHandlerTest.slowCheckExceedingTimeoutIsDown` passes today with a never-completing check, so Vert.x's `TimeoutException` carries a non-null message. Confirmed by source inspection only — treat a fresh green run as the falsification handle. | existing test |
| F5 | On Java 21 a *dereference* NPE has a non-null message (JEP 358). Real triggers are `new IllegalStateException()`, `Objects.requireNonNull(x)` with no message arg, and library exceptions built with a null message. **Every fixture must construct its throwable deliberately** — a "natural" dereference would pass against unfixed code. | JEP 358 |
| F6 | `vertique-core`'s packaged SPI doc instructs check authors: *"Return a completed future carrying a `DOWN` result rather than a failed future."* `DatabaseHealthCheck.otherwise` obeys published guidance and must **not** be deleted. | `vertique-core/.../module.md:533-537` |
| F7 | The issue's suggested `getSimpleName()` fallback is wrong — it returns `""` for anonymous classes, so `new RuntimeException(){}` with a null message yields `{"error": ""}`. Use `getName()`. Repo precedent is mixed (`getSimpleName()` in log/metric contexts; `getName()` for error-type fields at `JobCompletionHandler.java:64`, `CronJobDispatcher.java:369`). | grep + JLS |
| F8 | `HealthCheckResultTest` lives in `vertique-management` though it tests a `vertique-core` type — and the maintainer doc already names the trigger for moving it: *"move the test when the SPI grows enough to justify its own test package in `vertique-core`."* Adding `down(Throwable)` is that trigger. It has **7** `@Test` methods, not 6. | `docs/modules/vertique-management.md:125-130`; file |
| F9 | `check.name()` is invoked **inside** both the `map` and `otherwise` lambdas, so a throwing `name()` defeats the recovery and re-enters the hang. This is the only surviving hang path once `down(String)` is total. | `HealthCheckHandler.java:99-100` |
| F10 | `site-source`'s copy is **generated** by `scripts/stage-docs.mjs` from a pinned SHA, never run by CI. Out of scope. | `stage-docs.mjs:5-43` |
| **F11** | **Governance paths moved.** ADRs are at `docs/adr/product/` (not `adr/product/`); maintainer docs at `docs/modules/` (not `docs/`). Highest ADR is **0205**, marker says **0206**. | `git show main:docs/adr/product/README.md:218` |
| F12 | `ServiceSupervisorHealthCheck` is a fourth `down(...)` caller but uses `down(Map)` — safe, untouched. | `ServiceSupervisorHealthCheck.java:64` |
| **F13** | **Once `down(String)` is total, the handler's existing `.otherwise` recovers every per-check future**, so no future ever fails and `Future.join`/`succeeded()` would be unreachable code. The aggregation must be restructured (§4 S2), not merely guarded — otherwise the new machinery is untested by construction. | derived from `HealthCheckHandler.java:97-105` |

**Issue text corrected by F6/F7:** the class-name fallback belongs in a new `down(Throwable)`
overload (a null *message* and a null *failure object* are different states), and the fallback
is `getName()`.

---

## 3. Contract Appendix (frozen)

### `dev.vertique.core.health.HealthCheckResult` — public API

```java
/**
 * Returns an unhealthy result with an error message.
 *
 * <p>A {@code null} error means "no diagnostic message available" and yields a result with
 * empty data, equivalent to {@link #down()}. This keeps the factory total for the common
 * {@code down(throwable.getMessage())} idiom, whose argument is null for any exception
 * constructed without a message.
 *
 * @param error the error description; may be {@code null}
 * @return a DOWN result carrying {@code error} in data, or with empty data when it is null
 */
public static HealthCheckResult down(String error)

/**
 * Returns an unhealthy result describing the given failure.
 *
 * <p>The {@code error} entry is the throwable's {@linkplain Throwable#getMessage() message}
 * when non-null, and its {@linkplain Class#getName() fully qualified class name} otherwise —
 * including when {@code getMessage()} itself throws an {@link Exception}, since a health check
 * must be able to describe any failure it is handed. A blank message is passed through
 * verbatim; the cause chain is not walked.
 *
 * @param cause the failure to describe; must not be {@code null}
 * @return a DOWN result describing {@code cause}
 * @throws NullPointerException if {@code cause} is {@code null}
 */
public static HealthCheckResult down(Throwable cause)
```

The hostile-`getMessage()` guard lives **here**, not in `HealthCheckHandler` (amendment A1). Every
caller of the documented `down(cause)` idiom — the handler, `DatabaseHealthCheck`, and any
third-party check — gets the same guarantee, and the DOWN-rendering policy is stated once.

**Deliberate asymmetry** (stated so it is not re-litigated): `down(String)` is *total* on null,
`down(Throwable)` is *strict* on null. An absent message is a real state; an absent failure
object is a caller bug.

**Unchanged:** `up()`, `up(Map)`, `down()`, `down(Map)`, and the canonical constructor's
`Map.copyOf`, which stays strict on null *entries*. `down(null)` remains a compile error (three
applicable overloads, none most specific); adding the overload is source- and binary-compatible
because no valid existing call site could resolve to it.

### `dev.vertique.management.HealthCheckHandler` — internal shape

```java
private record CheckExecution(String name, Future<HealthCheckResult> result) {}
```

Note the type parameter: executions carry the **raw** check result, not pre-rendered JSON. This
is the structural change that makes `join` and the success guard load-bearing (F13).

**Handler invariant (new, javadoc'd):** `handle` writes exactly one response, and `checks` carries
exactly one entry per check in the set, for every check that returns or throws normally.

**Non-fatal failure policy (frozen):** every guard added by this change — in the handler and in
`HealthCheckResult.down(Throwable)` — catches `Exception`, never `Throwable`. An `Error`
propagates rather than being laundered into a DOWN status; a probe must not report "unhealthy"
for an `OutOfMemoryError` and carry on. Consequently the invariant above holds for `Exception`,
and two hazards remain caveated: an `Error` thrown from contributor code, and a contributor that
**blocks** the event loop indefinitely in `check()`, `name()`, or `Throwable#getMessage()` (§8 D3).

### Class Inventory

| Type | Module | Kind | Visibility | Change |
|---|---|---|---|---|
| `HealthCheckResult` | vertique-core | record | **API** | `down(String)` made total; `down(Throwable)` added |
| `HealthCheck` | vertique-core | interface | **SPI** | javadoc only (stale "error message" wording) |
| `HealthCheckHandler` | vertique-management | class | Internal | aggregation restructured |
| `HealthCheckHandler.CheckExecution` | vertique-management | record | Internal (private) | **new** |
| `DatabaseHealthCheck` | vertique-db-postgresql | class | API | `.otherwise(HealthCheckResult::down)` + javadoc |
| `HealthCheckResultTest` | vertique-core | test | Test | **moved** from vertique-management (all 7 cases) |

---

## 4. Slice plan

Execution order is prescriptive. Every slice compiles and is green on its own.

### S0 — persist the plan · `routine`
Create the worktree from `origin/main`, commit this plan to
`docs/plans/fix-health-check-null-message.md` (precedent: `docs/plans/feat-param-shape-parity.md`).
`docs: add implementation plan for health check null-message fix`

### S1 — `HealthCheckResult` becomes total, gains `down(Throwable)` · **`critical`**
Public API/SPI-shaping.

**Red tests** — new `vertique-core/src/test/java/dev/vertique/core/health/HealthCheckResultTest.java`.
First migrate **all 7** existing cases verbatim (`upNoData`, `upWithData`, `downNoData`,
`downWithError`, `downWithData`, `nullDataNormalized`, `dataDefensivelyCopied`), then add:

| Test | Given / When / Then |
|---|---|
| `downWithNullErrorHasEmptyData` | given nothing / when `down((String) null)` / then DOWN, `data()` empty, no `error` key, **no exception** |
| `downWithThrowableUsesMessage` | given `new RuntimeException("boom")` / when `down(cause)` / then `error` == `"boom"` |
| `downWithThrowableFallsBackToClassName` | given `new IllegalStateException()` (no message) / when `down(cause)` / then `error` == `"java.lang.IllegalStateException"` |
| `downWithAnonymousThrowableFallbackIsNotBlank` | given `new RuntimeException(){}` with null message / when `down(cause)` / then `error` is non-blank (pins F7 — fails under `getSimpleName()`) |
| `downWithBlankMessagePassesThrough` | given `new RuntimeException("   ")` / when `down(cause)` / then `error` == `"   "` verbatim |
| `downWithNullThrowableRejected` | given null / when `down((Throwable) null)` / then `NullPointerException` |

**Green impl:** `down(String)` → `error == null ? Map.of() : Map.of("error", error)`.
`down(Throwable)` → `requireNonNull(cause, "cause")`, delegate to
`down(msg != null ? msg : cause.getClass().getName())`.

**Same-slice docs:** `vertique-core/.../META-INF/vertique/module.md` ~533-537 — add
`down(Throwable)` to the factory enumeration, state the null-message contract, keep the
completed-DOWN guidance. Also fix `HealthCheck.java`'s stale `check()` javadoc ("with the error
message" → the class-name fallback).

**Delete:** `vertique-management/src/test/java/dev/vertique/management/HealthCheckResultTest.java`.

`fix(core): make HealthCheckResult.down total and add down(Throwable)`

### S2 — `HealthCheckHandler` cannot hang or starve siblings · **`critical`**
Concurrency-sensitive aggregation on a probe path. **Restructure, not a guard bolt-on** (F13).

Add `@Timeout(value = 20, unit = SECONDS)` at class level first, so a hang **fails instead of
hanging**. The suite is split honestly by what each test proves:

**Genuinely red against post-S1 code** (this is what forces the restructure):

| Test | Given / When / Then |
|---|---|
| `throwingNameCheckDoesNotStarveSiblings` | given a check whose `name()` throws **and** a sibling completing after a short delay / when GET /health / then a response arrives, **two** entries, sibling is **UP**, the throwing check is DOWN under a fallback name. Post-S1 this hangs (F9: `name()` throws in both lambdas → future fails → `all` fails → null result → NPE → no response). Pins name-once **and** `join` **and** the success guard at one seam. |

**Green characterization tests** — written *before* the restructure and required to stay green
through it (they pass post-S1; their job is to prove the refactor preserves behavior):

| Test | Given / When / Then |
|---|---|
| `failedFutureWithNullMessageIsDown` | given a check returning `Future.failedFuture(new IllegalStateException())` / when GET /health / then 503, `error` == `"java.lang.IllegalStateException"` |
| `throwingCheckWithNullMessageIsDown` | given a check whose `check()` throws `new IllegalStateException()` / when GET /health / then 503, that entry DOWN |
| `unserializableCheckDataIsReportedDown` | given an UP check whose data map holds a value `JsonObject.mapFrom` rejects / when GET /health / then 503, response received, that check DOWN |

**Regression guards on the new structure** (red if `join` is ever reverted to `all`, or a guard
dropped — they cannot be red before the restructure because no per-check future fails there):

| Test | Given / When / Then |
|---|---|
| `siblingSurvivesAFailedCheck` | given a check whose future fails immediately **and** a sibling completing after a short delay / when GET /health / then both entries present and the sibling is **UP** (under `all` the sibling is still pending, renders DOWN, and this fails) |
| `hostileGetMessageIsReportedDown` | given a check failing with a throwable whose `getMessage()` throws a `RuntimeException` / when GET /health / then 503, that entry DOWN with `error` == the throwable's class name |

**Green impl** — the restructure:
- resolve `check.name()` **once**, in a `try`, falling back to `check.getClass().getName()`;
- invoke `check.check()` in a `try`, converting a synchronous throw to `Future.failedFuture(e)`;
- hold `CheckExecution(String name, Future<HealthCheckResult> result)` — **no `.otherwise`**, so
  failures stay raw and reach the aggregation;
- `Future.join(...)` over the raw futures;
- in the callback, render each execution through a private `render(CheckExecution)` that cannot
  throw: `succeeded()` → `toJson(name, result)` inside a `try` (a serialization failure falls
  through to the DOWN rendering); otherwise → DOWN JSON built from
  `HealthCheckResult.down(cause)`, itself wrapped in a `try` that falls back to
  `cause.getClass().getName()` if a hostile `getMessage()` throws;
- all guards catch `Exception`, never `Throwable` (§3 policy).

**Same-slice docs:** `vertique-management/.../META-INF/vertique/module.md` — the aggregation
bullet (~64), the completed-DOWN paragraph (~126), the factory table (~169), the
`String.valueOf(e.getMessage())` example (~150), and the now-obsolete "`down(String)` rejects a
null message" gotcha (~184).

`fix(management): never hang the health probe on a failed check`

### S3 — `DatabaseHealthCheck` uses the new overload · `routine`
Mechanical; contract frozen by S1.

**Red test** — `DatabaseHealthCheckTest.downOnFailureWithNullMessage`: given
`pool.query(…).execute()` returning `Future.failedFuture(new RuntimeException())` (no message) /
when `check()` / then the future **succeeds** carrying DOWN with `error` ==
`"java.lang.RuntimeException"`. Asserting a *succeeded* future is what pins F6 — the check must
not degrade to a failed future.

**Green impl:** `.otherwise(HealthCheckResult::down)`.

**Same-slice docs:** `vertique-db-postgresql/.../META-INF/vertique/module.md` ~243 ("DOWN with the
failure message otherwise" → state the class-name fallback) and `DatabaseHealthCheck.check()`'s
stale javadoc (~46).

`fix(db): report DOWN when a pool failure carries no message`

### S4 — ADR · `routine`
Governance repo, docs-only → direct to `main`. May land before the code PR merges: an ADR records
a decision, not shipped behavior.
- `docs/adr/product/NNNN-total-health-check-result-factories.md` (§5) + register row + marker bump.
- **Allocate the number at commit time**, not now — several worktrees are in flight and the marker
  has already moved 0201 → 0206 during this planning session. `0206` is the current expectation.

`docs(adr): record total health-check result factories`

### S5 — governance PR: maintainer docs + gitlink · `routine`
**After the code PR merges**, in one governance PR, so the maintainer docs never describe behavior
absent from the pinned source:
- bump the `sources/vertique` gitlink to merged `main`;
- `docs/modules/vertique-management.md` — rewrite the reversed-position section (101-118); update
  the core-owned-types section (125-130), whose stated trigger for moving the test this change
  meets; update the test-navigation row (161).
- `docs/modules/vertique-db-postgresql.md` — remove the "throws when the failure has no message"
  defect section citing #35 (153-161); refresh the test table row (~200).

`chore: advance the vertique submodule to merged main` · `docs: retire the health probe null-message defect notes`

---

## 5. ADR to write

**"Health check result factories are total on a missing message"** (S4). Load-bearing because it
*reverses an explicitly recorded maintainer position*. Must state:
- the decision — both factories, plus the completed-DOWN division of labour;
- **why the earlier position lost**: it converted a total-function hazard into a caller obligation
  replicated at every adapter, with no test anywhere proving the obligation was met, and its own
  documented workaround wrote `"null"` into the probe body;
- the `down(String)`-total / `down(Throwable)`-strict asymmetry, and why it is not arbitrary;
- the catch-`Exception`-not-`Throwable` policy and the invariant it does and does not buy;
- `Future.join` and the raw-failure restructure in **consequences**, not in the decision;
- the §8 deferrals with their re-entry triggers.

---

## 6. Artifact manifest

Code repo paths are relative to `sources/vertique/`; governance paths to the `vertique-dev` root.

**New**
- `docs/plans/fix-health-check-null-message.md` *(code repo; removed in the final docs commit)*
- `vertique-core/src/test/java/dev/vertique/core/health/HealthCheckResultTest.java`
- `docs/adr/product/NNNN-total-health-check-result-factories.md` *(governance)*

**Modified — code repo**
- `vertique-core/src/main/java/dev/vertique/core/health/HealthCheckResult.java`
- `vertique-core/src/main/java/dev/vertique/core/health/HealthCheck.java` *(javadoc)*
- `vertique-core/src/main/resources/META-INF/vertique/module.md`
- `vertique-management/src/main/java/dev/vertique/management/HealthCheckHandler.java`
- `vertique-management/src/test/java/dev/vertique/management/HealthCheckHandlerTest.java`
- `vertique-management/src/main/resources/META-INF/vertique/module.md`
- `vertique-db/vertique-db-postgresql/src/main/java/dev/vertique/db/postgresql/DatabaseHealthCheck.java`
- `vertique-db/vertique-db-postgresql/src/test/java/dev/vertique/db/postgresql/DatabaseHealthCheckTest.java`
- `vertique-db/vertique-db-postgresql/src/main/resources/META-INF/vertique/module.md`

**Modified — governance repo**
- `docs/adr/product/README.md` *(register row + next-number marker)*
- `docs/modules/vertique-management.md`
- `docs/modules/vertique-db-postgresql.md`
- `sources/vertique` gitlink

**Deleted**
- `vertique-management/src/test/java/dev/vertique/management/HealthCheckResultTest.java` *(moved to vertique-core)*

**Module documentation decisions** (BOM-managed consumables touched)
- `vertique-core` — packaged `module.md` **modified**: factory set and null contract change.
- `vertique-management` — packaged `module.md` **modified**: consumer gotcha, example, aggregation text.
- `vertique-db-postgresql` — packaged `module.md` **modified**: documented failure behavior gains the fallback.
- `vertique-services` — *no documentation impact* — `ServiceSupervisorHealthCheck` uses `down(Map)`, untouched (F12).

**Out of scope:** `sources/site-source/**` — generated from a pinned SHA (F10).

---

## 7. Risks & edge cases

- **Tests that cannot fail.** Only `throwingNameCheckDoesNotStarveSiblings` is red for S2; the rest
  are characterization tests or new-structure guards, and §4 labels which is which rather than
  claiming red status they do not have. Prove each red test by reverting its fix and confirming the
  specific failure (a hang caught by `@Timeout`, not a silent pass).
- **Sibling-delay determinism.** The two sibling tests need the sibling still pending when the
  failing check settles. Complete it from a short `vertx.setTimer`; the *fixed* direction is not
  timing-sensitive (`join` always waits), so a slow scheduler cannot produce a false green.
- **Response-shape change:** a message-less failure now emits `{"error":"java.lang.Foo"}` where it
  previously emitted nothing at all (hang). New observable output on a public endpoint — decided
  here, not at implementation time.
- **`Error` still propagates** by policy (§3); the "always answers" invariant is scoped to
  `Exception`. Stated in the ADR so it is not mistaken for an oversight.
- **ADR number race** — allocate at the S4 commit, not now (F11).
- **Governance/source skew** — S5 keeps maintainer docs and the gitlink in one PR after the code
  merges, so governance never documents behavior its pinned source lacks.
- **Maven serialization** — build one worktree at a time; concurrent reactors over a shared `~/.m2`
  have produced jars missing classes here before.
- **Management port exposure** — probe bodies already disclose raw exception messages; class names
  add no new class of disclosure, but the port must stay cluster-internal as documented.

---

## 8. Out-of-scope & deferral routing

Each gets a GitHub issue on `vertiquehq/vertique-dev` in the step-9 commit (no PRD is in play):

| # | Item | Why deferred |
|---|---|---|
| D1 | `HealthCheckResult`'s canonical constructor accepts a **null `status`** | Separate invariant defect, distinct execution path, own tests |
| D2 | `Map.copyOf` does not preserve `LinkedHashMap` order — affects `ServiceSupervisorHealthCheck`'s per-service data ordering | Cosmetic, no correctness impact |
| D3 | Isolating a contributor that **blocks** in `check()` / `name()` / `getMessage()`, or throws an `Error` | Would change the SPI's Vert.x context semantics; a design change, not a bug fix. Re-entry: an observed production hang traced to a blocking contributor |
| D4 | `ManagementConfig` accepts a non-positive `healthCheckTimeoutSeconds`, failing only at verticle deploy; **no test on either side** | Startup-validation defect on a different execution path from the probe hang, with its own test surface (`ManagementVerticleTest`). Documented at `docs/modules/vertique-management.md:90-98` |

**Deferred without an issue** (non-defects; recorded as ADR consequences with re-entry triggers):
blank-message normalization; probe-body truncation (would belong at the management rendering
boundary across all diagnostic data, not on one factory); cause-chain fallback.

---

## 9. Verification

**Per slice**
```bash
./mvnw -ntp -pl vertique-core -am test -Dtest=HealthCheckResultTest
./mvnw -ntp -pl vertique-management -am test -Dtest=HealthCheckHandlerTest
./mvnw -ntp -pl vertique-db/vertique-db-postgresql -am test -Dtest=DatabaseHealthCheckTest
```
Confirm from the Surefire report that the expected tests actually ran — a `-Dtest` filter matching
nothing still reports BUILD SUCCESS.

**Stress loop** for the async handler tests before treating them as deterministic:
```bash
for i in $(seq 50); do ./mvnw -ntp -pl vertique-management -am test -Dtest=HealthCheckHandlerTest || break; done
```

**Mechanical completeness** — no unguarded `getMessage()` into a health factory remains:
```bash
grep -rn "HealthCheckResult.down(.*getMessage())" --include="*.java" . | grep -v /target/
```
must print nothing. And the moved test leaves no duplicate:
```bash
find . -name HealthCheckResultTest.java -not -path "*/target/*"
```
must print exactly one path, under `vertique-core`.

**Full build** — `./mvnw -ntp clean verify` on the whole project, via `build-validator`.

**Acceptance, criterion by criterion**
1. A check failing with a message-less throwable → 503 with a complete body
   (`failedFutureWithNullMessageIsDown`) — the issue's headline symptom.
2. Same on the synchronous-throw branch (`throwingCheckWithNullMessageIsDown`).
3. The readiness check shipped by `vertique-db-postgresql` reports DOWN as a *succeeded* future
   (`downOnFailureWithNullMessage`).
4. A broken check never starves its siblings (`throwingNameCheckDoesNotStarveSiblings`,
   `siblingSurvivesAFailedCheck`).
5. No fixture with a non-null message can mask the path (F5).
6. `slowCheckExceedingTimeoutIsDown` still passes (F4's falsification handle).

**Then:** `/simplify` → `build-validator` → `/security-review` + `codex-reviewer` in parallel →
Codex review loop → docs sweep against `git diff main...HEAD` → **draft** PR (the probe is async;
draft until CI is green).

---

## 10. Independent first opinion & debate outcome

Consulted `vertique-codex-architect` (gpt-5.6-sol), two rounds, session
`019fae9e-81ed-7210-8891-6ccb4c8f7ac7`.

**Adopted (changed the design):** resolve `name()` once with a fallback (F9) — my draft called the
possibly-broken `name()` again inside the recovery; `Future.join` over `all`+guard, because
guarding `all` reports still-pending siblings as DOWN; `getName()` over my proposed
`getSimpleName()` (F7); move `HealthCheckResultTest` to the module owning the type (F8);
`Map.copyOf` stays strict; `down(Throwable)` strict on a null cause; blank messages pass through;
no truncation.

**Rejected, with reasons:** Codex argued for *no public `down(Throwable)`* (two private helpers) —
conceded in round 2 once F6 showed normalization is needed in two modules plus every third-party
check the SPI invites. Codex argued for *deleting* `DatabaseHealthCheck.otherwise` — rejected on
F6: the reference implementation would violate the framework's own published guidance and its test
would weaken from "proves a pool failure reports DOWN" to "proves the future fails"; conceded in
round 2. Codex argued *no ADR* — overruled; this reverses a recorded maintainer position, which is
what an ADR is for; conceded in round 2. Codex's claim that the repo has no `getSimpleName()`
precedent was wrong (20+ production hits); `getName()` still wins, on the anonymous-class argument
alone.

**Divergence from the final consultation advice:** the architect recommended deferring the
hostile-`getMessage()` hazard entirely. The `try/catch` is taken instead, so the invariant is true
for any throwable that returns or throws an `Exception`; only `Error` and indefinite blocking
remain caveated (D3).

---

## 11. Amendments

**A1 — 2026-08-06 — trigger: Simplify-stage altitude review. Contract Appendix change; user approved.**
The hostile-`getMessage()` guard moves from `HealthCheckHandler.downJson` into
`HealthCheckResult.down(Throwable)`.
*Why:* the guard sat one layer above the documented idiom. `DatabaseHealthCheck` uses
`.otherwise(HealthCheckResult::down)` — exactly what the packaged SPI tells third-party authors to
write — and got no protection: a failure whose `getMessage()` throws makes the mapper throw, and
per Vert.x the future then fails with the *mapper's* exception, so the original failure's identity
is lost and the probe reports a diagnostic describing the wrong throwable. Same defect class as
issue #35, one layer removed. Moving the guard makes the DOWN-rendering policy live in one place,
removes the handler's duplicated fallback, and extends the guarantee to every caller.
*Also:* the plan's §7 prediction that only one S2 test would be red was wrong — four were
(the two null-message tests fail on a dropped diagnostic, and `hostileGetMessageIsReportedDown`
hangs). Recorded as a verified-fact correction; no sign-off needed.

---

## 12. Review response (rev 1 → rev 2)

| Finding | Resolution |
|---|---|
| **Blocker** — S2 tests did not prove S2's changes; `join`/`succeeded()` were unreachable once `down(String)` went total | Recorded as F13 and fixed structurally: executions now hold **raw** `Future<HealthCheckResult>` with no per-check `.otherwise`, so failures reach the aggregation and every layer is exercised. The suite is relabelled honestly into one genuinely-red test, three characterization tests, and two new-structure guards |
| **Blocker** — governance paths and ADR number drifted | Re-verified against `main`: `docs/adr/product/`, `docs/modules/`, marker 0206 (F11). Corrected throughout; the number is now allocated at commit time rather than frozen |
| **Major** — S4 published maintainer docs before the code could merge | Split: ADR alone may land early (S4); maintainer docs + gitlink land together in a governance PR after the code PR merges (S5) |
| **Major** — "six existing test cases" | Corrected to **7** and enumerated by name (F8) |
| **Major** — incomplete doc coverage | Added `HealthCheck.java` javadoc, `DatabaseHealthCheck.check()` javadoc, management `module.md` lines 64/126/169, `docs/modules/vertique-management.md:125-130` and `:161`, `docs/modules/vertique-db-postgresql.md:~200` |
| **Major** — hostile `getMessage()` catch category unspecified | Frozen in §3: catch `Exception`, never `Throwable`; `Error` propagates. Test `hostileGetMessageIsReportedDown` added; the invariant is scoped accordingly |
| **Minor** — F2 pending-future claim overstated | Reworded: the pending case is undocumented, and the design relies only on `succeeded()` |
| **Minor** — no stress-loop command | Added to §9 |
| **Proof gap** — no linter PASS recorded | `plan-linter` ran on rev 1: one FAIL (S5 missing a risk tier), fixed. Re-run on rev 2 before `ExitPlanMode`, and again on the persisted file before slice 1 |
