<!--
SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
SPDX-License-Identifier: EUPL-1.2
-->

# Plan: Flaky-IT determinism pass (#186, #194, #200) — fix/flaky-it-determinism

## 1. Context & goal

Release-blocker item 1 of the triage batch: three tests flake only under full-suite load
(each observed once in the old monorepo, 2026-07). CI trust gates every later item in the
batch, so this lands first.

- Why now: every subsequent release-blocker PR runs `clean verify` + the Codex loop; a
  flaky suite poisons all of that verification.
- Boring default / do-less alternative: close the issues as not-reproducible. Rejected:
  exploration found concrete, documented-hazard-family defects in two of the three classes
  (testing.md violations) that are cheap to remove, plus a diagnosability gap that would
  leave any recurrence a mystery.

Repo: `sources/vertique` (vertiquehq/vertique). Landing: worktree
`.claude/worktrees/flaky-it-determinism`, branch `fix/flaky-it-determinism` from local
`main`, local merge back to `main` after the pipeline converges (user-selected flow; no
remote PR — the new repo has no PR flow yet).

## 2. Pre-flight findings (verified against code 2026-07-27)

**All three issues' reported mechanisms are refuted by the current code:**

- **#186 `ActionOnlyRouteAuthIT`** (rest-security): route registration is fully
  synchronous *before* `listen(0)` (contributors L177–179, terminal handler L180,
  router composition L182–187, then bind L193; port read after bind L197). No
  registration window exists; the class already matches testing.md's canonical hardened
  shape (shared client in `@BeforeAll` L117, awaited `@AfterAll` close L207–212). A
  uniform 404 can only mean the response came from a server that is not this test's —
  cause not identifiable in-class. Structural twin
  `ActionOnlyRouteClaimsValidatorIT` (rest-auth-jwt) is identical and has never flaked.
- **#194 `FailureHandlerRouteIdentityCharacterizationIT`** (rest-jaxrs): the reported
  "assertion races the failure handler" is impossible — the observation map write
  (L97–99) strictly happens-before the response write (L100–103), and the client's
  `resp.body()` cannot complete before that. An NPE at L173 proves only that the
  *expected failure-handler path did not execute* — a foreign response is plausible, not
  proven. Real defects found instead: per-test `HttpClient` created inside the compose
  (L156; violates testing.md L162); a narrow bind-vs-callback leak window (`server` is
  assigned inside the compose before any request is issued, so request failures do NOT
  leak — only a failure between bind and the assignment callback does; same shape inline
  in `middleware404_catchAll` L238–249); non-atomic `getOrDefault`+`put` on the shared
  map (L96–99, L137–138); unguarded `Observation` derefs (L173, L183, L193) that turn
  "no observation" into an NPE instead of a diagnosable assertion failure; dead
  `fallbackTag` parameter (L92).
- **#200 `RestRequestCompletionEmitterTest`** (rest-core; a surefire unit test doing real
  HTTP): "request can hit a stale server before route registration" is unsupported —
  routers are fully built before `listen(0)` and each request uses the port returned by
  its own bind (`startServer` L204–209). Real defects: 19 fixed
  `vertx.setTimer(50/80, …)` sleeps standing in for completion signals; cross-event-loop
  reads of plain `ArrayList` captures (18 sites; the class itself uses
  `CopyOnWriteArrayList` correctly at exactly one site, L953, with the rationale
  comment); `noListenersCompleteSilently` (L443–454) never drains the response body,
  leaving the shared client's pooled connection with an unread response;
  `capturesSecurityAndCorrelationContext` (L513–516) bypasses `startServer` and assigns
  `server` in an `onSuccess`, leaking the server if the test fails before assignment.
  Sibling `RestRequestCompletionExactlyOnceIT` allocates a per-test client
  (L186–189) — same testing.md L162 violation, same module.
- **Environment facts:** no parallel JUnit execution anywhere (no
  `junit-platform.properties`, no surefire `parallel`/`forkCount` overrides), so classes
  run sequentially per module fork. No `ServerSocket(0)` anywhere. Awaitility is not a
  dependency; no poll-until helper exists in the tree; no marker-route pattern exists.
  Git history for the original flake observations is not recoverable (open-core snapshot,
  32 commits); the old fix survives only as the `RestRequestCompletionEmitterTest`
  javadoc (L78–81) and testing.md rules.
- **One constructible foreign-response mechanism survives** (found in the Codex debate,
  source-verified against Vert.x 5.1.2: shared-server merging on `listen(0)` refuted —
  `shared=false` for port 0; `DEFAULT_REUSE_PORT` false; pooled connections cannot
  retarget): the server binds the IPv4 wildcard `0.0.0.0:P` while the client connects to
  `"localhost"`, which the environment may resolve to `::1` — a foreign process owning
  `[::1]:P` then answers, address families coexisting without SO_REUSEPORT. Unproven as
  the historical cause, but concrete and cheaply removable: bind and connect explicitly
  to `127.0.0.1` in the touched classes.
- **Barrier mechanism verified by me** (`RequestContextLifecycle.java`): the lifecycle
  registers exactly one `ctx.addEndHandler` (L105); Vert.x Web 5 fires end handlers in
  reverse registration order (javadoc L24), so the lifecycle's — registered first —
  fires last, and `Handle.afterClose(Runnable)` (L198) tasks run in phase 2 after every
  other end handler's synchronous work (L282–287). Registration after completion throws
  (L44), so the barrier must be registered *during* the request.
- **testing.md governs** (meta-repo `.claude/rules/testing.md`): share long-lived clients
  per class (L61–64, L162); close every socket on every exit path, never rely on
  test-context teardown (L64); flake fixes must subtract nondeterminism, never
  simultaneously tighten unrelated assertions (L101–103); 50-iteration stress loop before
  claiming deterministic (L167); misrouted-404 family documentation (L109–128).

**Consequence:** this is not "fix the reported race" — it is "remove the verified
documented-hazard defects, add misrouting diagnosability, and produce stress evidence."
The three issues get corrected mechanism analyses when closed.

## 3. Slice plan

Flake-fix nature: red→green does not apply (the defects are nondeterministic by
definition; testing.md L101–103 and L167 define the proof shape instead — behavior-
preserving edits + stress-loop evidence). Every slice's proof obligation is:
(a) the named mechanical checks below, (b) the class passes its module's stress loop
(50 iterations) after the change, (c) full suite green at the end.

**S0 — persist plan** (`docs: add implementation plan for flaky-IT determinism pass`)
Copy this file to `docs/plans/fix-flaky-it-determinism.md` in the worktree; first commit
on the branch.

**S1 — rest-jaxrs: harden `FailureHandlerRouteIdentityCharacterizationIT`** — routine
`test(rest-jaxrs): remove shared-client and leak hazards from failure-handler characterization IT`
1. Hoist `HttpClient` to `static` created in `@BeforeAll`, closed awaited in `@AfterAll`
   (canonical shape: `ActionOnlyRouteClaimsValidatorIT` L126–140/240–245).
2. Close the narrow bind-vs-callback leak window in `run(...)` and the inline duplicate
   in `middleware404_catchAll` (L238–249): assign `server` in an `onSuccess` attached
   directly to the `listen` future (before any composed stage can fail), or apply the
   `Future.eventually(...)` teardown pattern (`OpenApiContractStrategyIT:123`).
3. Bind to and request `127.0.0.1` explicitly (removes the wildcard-bind/`localhost`
   address-family mechanism from §2).
4. Null-guard the three unguarded derefs (L173, L183, L193): fail with
   `assertNotNull(o, "no failure handler recorded for <path>; observations=" + observations)`
   before the field assertions — converts a no-observation recurrence from NPE into a
   diagnosable message. (Diagnosability on the already-failing path; not a tightened
   assertion — testing.md L101–103 respected.)
5. Adjacent hardening (committed as such, not as flake fixes): replace
   `getOrDefault`+`put` with `observations.merge(path, newObs, combiner)` at both sites
   (L96–99, L137–138) with a flag-preserving combiner derived from the two existing
   write sites (no asserted value changes); remove the dead `fallbackTag` parameter
   (L92). Drain response bodies in the request path if any helper leaves them unread.

**S2 — rest-core: harden `RestRequestCompletionEmitterTest` (+ sibling client hoist)** — routine
`test(rest-core): replace fixed sleeps with lifecycle barrier in completion-emitter tests`
1. **Causal barrier instead of polling** (debate outcome — dissolves the
   positive/negative classification problem and keeps exact-count assertions strong):
   the `router(...)` helper registers a barrier middleware between the emitter and the
   terminal route that, during the request, registers
   `RequestContextLifecycle.Handle.fromRoutingContext(rc).afterClose(() -> barrier.complete())`.
   Because the lifecycle's end handler fires last (verified §2), the barrier completes
   strictly after all emitter/listener work. Tests await the barrier future (own
   descriptive timeout, e.g. 5 s, message naming the barrier — a lifecycle defect must
   not present as an opaque class timeout), then assert **exact** counts. Both positive
   and negative halves become deterministic with no settle window.
2. Replace the fixed `setTimer(50/80)` sites (L236, 270, 329, 367, 403, 432, 521, 571,
   693, 727, 748, 773, 847, 876, 902, 930, 1001, 1086, 1132) with the barrier await.
   Sites where the lifecycle contractually does NOT close (server-free
   `completeNowDoesNotFireEndHandlers` L626–665; WebSocket-exclusion tests) keep their
   existing shape with a one-line `// settle window: lifecycle does not close here`
   comment.
3. **Conditional capture-list rule (no executor fork):** any capture list whose reads
   happen only after the barrier future completes stays a plain `ArrayList` (the future
   completion supplies the happens-before edge); any site that keeps a timer or is read
   outside a completion handoff becomes `CopyOnWriteArrayList` (rationale pattern at
   L953).
4. `noListenersCompleteSilently` (L443–454): drain `resp.body()` before asserting/
   completing so the shared client's pooled connection is not left with an unread
   response.
5. Route `capturesSecurityAndCorrelationContext` (L513–516) through `startServer` so the
   server field is assigned on the same path as every other test.
6. Bind/connect explicitly to `127.0.0.1` (both classes).
7. Sibling `RestRequestCompletionExactlyOnceIT` (L186–189): hoist the per-test client to
   `@BeforeAll`/`@AfterAll` **and drain response bodies in its `get`/`getWithHeader`
   helpers** (hoisting without draining would create the exact pooled-connection hazard
   item 4 removes).

**S3 — rest-security: misrouting diagnosability in `ActionOnlyRouteAuthIT`** — routine
`test(rest-security): identify foreign responses in action-only route auth IT`
1. Root router gains a first-position handler stamping a marker header
   (`rc.response().putHeader("x-vq-test-server", "action-only-route-auth"); rc.next();`).
2. **Diagnostic form, not a new assertion** (debate outcome — keeps pass/fail semantics
   strictly unchanged per testing.md L101–103): the `get(...)` helper returns status
   *plus* marker presence, and the existing status assertions' failure messages include
   it — a recurrence reads "404, marker absent → foreign response" vs "404, marker
   present → this router, route unmatched." No independent marker assertion.
3. Drain the response body in `get(...)` (currently maps only `statusCode()`, L289) and
   bind/connect explicitly to `127.0.0.1`. No lifecycle changes — the class is already
   canonical.

**S4 — verification & merge** (no code)
Stress loops + full `clean verify` (delegated; see §7), local merge to `main`, plan file
removed in the final docs commit per planning.md § Plan persistence.

Slice order is prescriptive: S0 → S1 → S2 → S3 → S4 (independent classes; ordered by
defect density so the highest-value hardening lands even if the batch is interrupted).

## 4. Artifact manifest

**New:**
- `docs/plans/fix-flaky-it-determinism.md` (S0; removed again in S4's final docs commit)

**Modified:**
- `vertique-rest/vertique-rest-jaxrs/src/test/java/dev/vertique/rest/jaxrs/FailureHandlerRouteIdentityCharacterizationIT.java` (S1)
- `vertique-rest/vertique-rest-core/src/test/java/dev/vertique/rest/core/events/RestRequestCompletionEmitterTest.java` (S2)
- `vertique-rest/vertique-rest-core/src/test/java/dev/vertique/rest/core/events/RestRequestCompletionExactlyOnceIT.java` (S2)
- `vertique-rest/vertique-rest-security/src/test/java/dev/vertique/rest/security/ActionOnlyRouteAuthIT.java` (S3)

**Deleted:** none.

**Module-doc decisions (test-only change):**
- `vertique-rest-jaxrs`: no documentation impact — test-source-only edits.
- `vertique-rest-core`: no documentation impact — test-source-only edits.
- `vertique-rest-security`: no documentation impact — test-source-only edits.

## 5. ADRs to write

None — no load-bearing production decision. The corrected mechanism analyses live in the
three issue closures (see §8), not an ADR.

## 6. Risks & edge cases

- **The foreign-response cause for #186/#200 remains unproven.** The `127.0.0.1` change
  removes the one constructible mechanism; the marker makes any recurrence
  self-identifying. Cannot guarantee non-recurrence — watch-item, accepted. Issue
  closures say "historical mechanism refuted, present hazards removed, recurrence
  diagnostics improved," never "root cause fixed."
- **Barrier coupling (S2.1)**: the emitter tests become coupled to
  `RequestContextLifecycle`'s end-handler ordering. Accepted deliberately — the
  emitter's production contract already depends on exactly that ordering, so the
  coupling tests the real contract. If a barrier-converted site fails `size == 1`
  post-conversion, the §2 refutation analysis is wrong — reopen, don't patch.
- **Sites where the lifecycle doesn't close** must keep their settle-timer shape; the
  executor identifies them by the class's own documentation (server-free and
  WebSocket-exclusion tests), each carrying the required comment.
- **`merge` semantics (S1.5)**: the combiner must preserve the existing "per-route flags
  and catch-all flags accumulate into one Observation" behavior; derived from the two
  existing write sites, changing no asserted values.
- Barrier timeout (5 s) is far below the class `@Timeout(20 s)` — no interaction; its
  failure message names the barrier.
- **Falsification handles** (from the debate): a post-change stress/CI failure showing
  404-with-marker-absent → foreign response confirmed, escalate to network-level
  attribution; a `size != 1` failure after barrier conversion → reopen the analysis.

## 7. Verification

Per slice (executor, in the worktree):
- S1: `./mvnw -ntp -pl vertique-rest/vertique-rest-jaxrs -am verify -Dit.test=FailureHandlerRouteIdentityCharacterizationIT`
- S2: `./mvnw -ntp -pl vertique-rest/vertique-rest-core -am verify -Dtest=RestRequestCompletionEmitterTest -Dit.test=RestRequestCompletionExactlyOnceIT`
- S3: `./mvnw -ntp -pl vertique-rest/vertique-rest-security -am verify -Dit.test=ActionOnlyRouteAuthIT`

S4 (delegated to `test-runner` / `build-validator`):
- Stress loops per testing.md L167: 50 iterations per touched module
  (`for i in $(seq 50); do ./mvnw -ntp -pl <module> verify || break; done`), ideally with
  a concurrent load source; report iteration counts.
- Full `./mvnw -ntp clean verify` on the whole reactor.
- `./mvnw -ntp spotless:apply` before each commit (hard rule).

Mechanical completeness checks:
- `grep -n "setTimer(50\|setTimer(80" RestRequestCompletionEmitterTest.java` → only
  sites carrying the `settle window` comment remain.
- `grep -n "\"localhost\"" <all four touched files>` → no request/bind hits (127.0.0.1
  everywhere).
- `grep -n "createHttpClient" FailureHandlerRouteIdentityCharacterizationIT.java` → one
  hit, in `@BeforeAll`. Same check on `RestRequestCompletionExactlyOnceIT.java`.
- `grep -n "new ArrayList" RestRequestCompletionEmitterTest.java` → every remaining
  capture-list hit is in a barrier-converted site (reads after barrier completion);
  timer-kept sites show `CopyOnWriteArrayList`.

Acceptance walkthrough: #194 — NPE shape impossible (null-guard) and client shared;
#200 — no fixed sleeps outside documented settle sites, exact-count assertions behind
the lifecycle barrier, no undrained responses; #186 — a misrouted response now
self-identifies in the failure message. All three: stress-loop evidence recorded in the
merge summary.

## 8. Out-of-scope & deferral routing

- **Module-wide shared-client sweep** (`FailureHandlerChainProbeIT`,
  `AnnotationDrivenRoutingIT`, and any other per-test-client classes beyond the two fixed
  here) → recorded in the release-triage ledger under the Group-6 test-uplift PRs (this
  session's triage), not expanded here.
- **Shared REST test-fixture / poll-helper artifact** → owned by the #210/#56 decision
  (task 7 of this batch).
- **testing.md gap** (no route-registration/marker-route guidance; the WireMock rule is
  the only registration-race entry) → one-paragraph rule addition proposed alongside the
  batch's docs work, meta-repo docs-only change, after this PR merges.
- **Issue closure comments** on mikakoivisto/vertique #186/#194/#200 with the corrected
  mechanism analyses — outward-facing; done only on explicit user go-ahead at S4.

## Debate outcome (codex-architect, gpt-5.6-sol, session 019fa265-a21f-74c1-a9f8-068aad5e4a74)

Verdict: approve-with-required-changes; all three amendments adopted after verification.

**Adopted:**
- **S2 redesign** — Codex showed my poll-until policy would weaken exactly-once proofs
  (completing at `size >= 1` lets a late duplicate escape the `size == 1` assertion the
  fixed sleeps accidentally caught). Its `Handle.afterClose` causal barrier is strictly
  better: no settle windows, exact counts stay strong, positive/negative classification
  dissolves. I verified the mechanism in `RequestContextLifecycle.java` myself.
- **S3 reshaped** — Codex is right that asserting the marker independently IS a
  tightened assertion under testing.md L101–103; the diagnostic-message form yields
  identical evidence for the observed symptom at zero rule risk.
- **`127.0.0.1` bind/connect** — both sides independently converged on the wildcard-
  bind/`localhost`→`::1` address-family split as the only surviving foreign-response
  mechanism (Codex source-verified the refutations of shared-server merge, SO_REUSEPORT,
  and pool retargeting against Vert.x 5.1.2 sources).
- Response-body draining in every touched shared-client helper; §2 corrections (S1 leak
  claim was overstated; #194 softened to "expected path did not execute").

**Rejected with reason:**
- Codex's cut of `map.merge` + dead-parameter removal — generic proportionality advice
  that conflicts with this repo's Adjacent Defects Rule; kept, but committed and
  described as adjacent hardening, not flake fixes.
- Blanket COWAL cut — accepted only conditionally: barrier-handoff sites keep plain
  lists; any surviving timer/poll site must use COWAL (rule stated in S2.3 so the
  executor has no fork).

**Unresolved/watch:** whether undrained small bodies actually block Vert.x 5 pool reuse
(unverified by either side — draining is cheap regardless); the historical cause of the
2026-07 observations is attributed to eliminated old-tree hazards, not proven.
